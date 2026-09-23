package com.pgy.gateway.infrastructure.ratelimit;

import com.pgy.gateway.config.GatewayProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 令牌桶限流器（算法实现在 resources/lua/token_bucket.lua）。
 *
 * 限流维度是"虚拟密钥"，也就是每个调用方一个桶。
 * 这样某个业务方疯狂刷接口，只会把自己的桶掏空，不会影响别人。
 */
@Slf4j
@Component
public class TokenBucketRateLimiter {

    private static final String KEY_PREFIX = "gw:ratelimit:";

    private static final DefaultRedisScript<Long> TOKEN_BUCKET_SCRIPT = new DefaultRedisScript<>();

    static {
        TOKEN_BUCKET_SCRIPT.setLocation(new ClassPathResource("lua/token_bucket.lua"));
        TOKEN_BUCKET_SCRIPT.setResultType(Long.class);
    }

    private final StringRedisTemplate redisTemplate;

    private final GatewayProperties properties;

    public TokenBucketRateLimiter(StringRedisTemplate redisTemplate, GatewayProperties properties) {
        this.redisTemplate = redisTemplate;
        this.properties = properties;
    }

    /**
     * 尝试取一个令牌。
     *
     * @param bucketKey 限流维度标识（这里传虚拟密钥的值）
     * @return true 放行 / false 拒绝
     */
    public boolean tryAcquire(String bucketKey) {
        if (!properties.getRateLimit().isEnabled()) {
            return true;
        }

        try {
            Long result = redisTemplate.execute(
                    TOKEN_BUCKET_SCRIPT,
                    List.of(KEY_PREFIX + bucketKey),
                    String.valueOf(properties.getRateLimit().getCapacity()),
                    String.valueOf(properties.getRateLimit().getRefillPerSecond()),
                    String.valueOf(System.currentTimeMillis()),
                    "1",
                    String.valueOf(computeTtlSeconds())
            );
            return result != null && result == 1L;
        } catch (Exception e) {
            // fail-open：限流是保护措施，不能让它反过来成为单点故障。
            // Redis 出问题时放行请求，最多是短暂失去限流能力，但服务本身是活的。
            log.warn("限流检查失败，本次放行：{}", e.getMessage());
            return true;
        }
    }

    /** 计算桶键的过期时间：从空到满所需时间的两倍，避免键过早失效 */
    private long computeTtlSeconds() {
        int capacity = Math.max(1, properties.getRateLimit().getCapacity());
        int refillPerSecond = Math.max(1, properties.getRateLimit().getRefillPerSecond());
        return Math.max(60L, (long) Math.ceil((double) capacity / refillPerSecond * 2));
    }
}
