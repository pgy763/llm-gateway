package com.pgy.gateway.infrastructure.health;

import com.pgy.gateway.config.GatewayProperties;
import com.pgy.gateway.domain.route.ProviderHealthTracker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 基于 Redis 的健康状态实现。
 *
 * 【为什么用 Redis 而不是本地 Map？】
 * 如果网关部署了多个实例，用本地 Map 的话，A 实例发现 DeepSeek 挂了，
 * B 实例并不知道，还会继续往那边发请求。放在 Redis 里就是所有实例共享一份判断。
 *
 * 【一个重要细节：Redis 挂了怎么办？】
 * 下面每个方法都包了 try-catch，失败时按"健康"处理。
 * 这是刻意的：健康检查只是优化手段，不能让它变成单点故障。
 * 宁可多试几次慢一点，也不能因为缓存挂了导致整个网关不可用。
 */
@Slf4j
@Component
public class RedisProviderHealthTracker implements ProviderHealthTracker {

    private static final String KEY_PREFIX = "gw:provider:fail:";

    private final StringRedisTemplate redisTemplate;

    private final GatewayProperties properties;

    public RedisProviderHealthTracker(StringRedisTemplate redisTemplate, GatewayProperties properties) {
        this.redisTemplate = redisTemplate;
        this.properties = properties;
    }

    @Override
    public boolean isUnhealthy(String providerName) {
        try {
            String value = redisTemplate.opsForValue().get(key(providerName));
            if (value == null) {
                return false;
            }
            return Integer.parseInt(value) >= properties.getRoute().getUnhealthyThreshold();
        } catch (Exception e) {
            log.warn("读取提供方 [{}] 健康状态失败，本次按健康处理：{}", providerName, e.getMessage());
            return false;
        }
    }

    @Override
    public void recordFailure(String providerName) {
        try {
            String key = key(providerName);
            Long count = redisTemplate.opsForValue().increment(key);
            // 第一次失败时设置过期时间，形成"冷却期"：
            // 冷却期内计数保留，过期后自动清零，等于给它一次重试的机会
            if (count != null && count == 1L) {
                redisTemplate.expire(key, Duration.ofSeconds(properties.getRoute().getCooldownSeconds()));
            }
            log.debug("提供方 [{}] 累计失败 {} 次", providerName, count);
        } catch (Exception e) {
            log.warn("记录提供方 [{}] 失败次数失败：{}", providerName, e.getMessage());
        }
    }

    @Override
    public void recordSuccess(String providerName) {
        try {
            redisTemplate.delete(key(providerName));
        } catch (Exception e) {
            log.warn("清除提供方 [{}] 失败计数失败：{}", providerName, e.getMessage());
        }
    }

    private String key(String providerName) {
        return KEY_PREFIX + providerName;
    }
}
