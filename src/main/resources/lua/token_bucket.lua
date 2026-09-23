--[[
令牌桶限流算法（在 Redis 里原子执行）

【为什么不用简单的计数器？】
"每分钟最多 60 次"这种计数器有个毛病：用户可以在 12:00:59 打满 60 次，
12:01:00 又打满 60 次，两秒内灌进来 120 次请求。这叫"临界问题"。
令牌桶没有这个问题：令牌按固定速率慢慢滴进桶里，桶满了就不再积攒，
所以任何时间窗口内的请求量都被真实限制住了。

【参数】
KEYS[1]  桶的键名
ARGV[1]  桶容量（允许瞬间打满多少）
ARGV[2]  每秒补充多少令牌（长期平均速率）
ARGV[3]  当前时间戳（毫秒）
ARGV[4]  本次请求需要消耗几个令牌
ARGV[5]  键的过期时间（秒）

【返回】
1 = 放行   0 = 拒绝

【为什么必须写在 Lua 里？】
"读出剩余令牌 → 判断够不够 → 扣减 → 写回"这几步如果分成多条 Redis 命令发送，
两个并发请求可能同时读到"还剩 1 个"，然后都以为能用，结果超发。
Redis 执行 Lua 脚本是原子的，中间不会被打断，从根本上避免了这个问题。
]]

local capacity   = tonumber(ARGV[1])
local refillRate = tonumber(ARGV[2])
local now        = tonumber(ARGV[3])
local requested  = tonumber(ARGV[4])
local ttl        = tonumber(ARGV[5])

local bucket = redis.call('HMGET', KEYS[1], 'tokens', 'lastRefill')
local tokens = tonumber(bucket[1])
local lastRefill = tonumber(bucket[2])

-- 第一次访问这个桶：装满令牌
if tokens == nil then
    tokens = capacity
    lastRefill = now
end

-- 按距离上次补充过去了多久，往桶里加令牌
local elapsed = now - lastRefill
if elapsed < 0 then
    elapsed = 0
end
tokens = math.min(capacity, tokens + elapsed * refillRate / 1000)

local allowed = 0
if tokens >= requested then
    tokens = tokens - requested
    allowed = 1
end

redis.call('HMSET', KEYS[1], 'tokens', tokens, 'lastRefill', now)
redis.call('EXPIRE', KEYS[1], ttl)

return allowed
