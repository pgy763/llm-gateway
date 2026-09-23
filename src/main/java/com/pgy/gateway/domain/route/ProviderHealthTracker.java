package com.pgy.gateway.domain.route;

/**
 * 提供方健康状态跟踪器。
 *
 * 【为什么需要"熔断"？】
 * 假设 DeepSeek 挂了，每次请求都要等它超时 60 秒才切换备用模型，
 * 用户就得干等一分钟。这比直接失败还难受。
 *
 * 所以做法是：连续失败到一定次数，就把它标记为"不健康"，
 * 在一段时间内（冷却期）直接跳过它，不再浪费用户的时间。
 * 冷却期结束后自动重新尝试 —— 万一它已经恢复了。
 *
 * 这就是熔断器（Circuit Breaker）的基本思路，只不过实现得很朴素。
 */
public interface ProviderHealthTracker {

    /** 该提供方当前是否处于"不健康"状态（应当被跳过） */
    boolean isUnhealthy(String providerName);

    /** 调用成功，清除失败计数 */
    void recordSuccess(String providerName);

    /** 调用失败，累加失败计数 */
    void recordFailure(String providerName);
}
