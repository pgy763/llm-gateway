package com.pgy.gateway.domain.route;

import com.pgy.gateway.config.GatewayProperties;
import com.pgy.gateway.domain.provider.ChatProvider;
import com.pgy.gateway.domain.provider.ProviderRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 路由器 —— 决定"这次请求按什么顺序去尝试哪些提供方"。
 *
 * 它产出的列表就是一条【降级链】。例如：
 *     [deepseek, kimi, mock]
 * 含义是：先找 DeepSeek；它失败就找 Kimi；再失败就用 mock 兜底。
 * 只要链条上任何一环成功，请求就算成功，用户完全无感知。
 *
 * 规划时会做三件事：
 *   1. 按优先级排序
 *   2. 剔除当前"不健康"的提供方（熔断）
 *   3. 截断到配置的最大尝试次数，避免一个请求拖太久
 */
@Slf4j
@Component
public class RoutePlanner {

    private final ProviderRegistry registry;

    private final ProviderHealthTracker healthTracker;

    private final GatewayProperties properties;

    public RoutePlanner(ProviderRegistry registry,
                        ProviderHealthTracker healthTracker,
                        GatewayProperties properties) {
        this.registry = registry;
        this.healthTracker = healthTracker;
        this.properties = properties;
    }

    /**
     * 规划降级链。
     *
     * @param requestedModel 调用方指定的模型名，可为 null
     * @return 有序的提供方列表，越靠前越先尝试
     */
    public List<ChatProvider> plan(String requestedModel) {
        int maxAttempts = properties.getRoute().getMaxAttempts();

        List<ChatProvider> healthy = registry.all().stream()
                .filter(provider -> !healthTracker.isUnhealthy(provider.name()))
                .toList();

        List<ChatProvider> candidates = healthy;
        if (healthy.isEmpty()) {
            // 全部被熔断了。直接失败对用户没有好处，不如再试一轮 ——
            // 万一上游刚好恢复，或者一开始就被误判了。
            log.warn("所有提供方当前都处于不健康状态，本次仍然按优先级尝试一轮");
            candidates = registry.all();
        }

        // 优先选择"支持指定模型"的提供方
        List<ChatProvider> matched = candidates.stream()
                .filter(provider -> requestedModel == null || provider.supports(requestedModel))
                .limit(maxAttempts)
                .toList();

        if (!matched.isEmpty()) {
            return matched;
        }

        // 指定的模型谁都提供不了。与其直接报错，不如退化为可用提供方，
        // 由 pickModel 把它们换成各自支持的模型名。
        if (requestedModel != null) {
            log.warn("没有可用的提供方能提供模型 [{}]，退化为使用全部健康提供方", requestedModel);
        }
        return candidates.stream().limit(maxAttempts).toList();
    }
}
