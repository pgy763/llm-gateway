package com.pgy.gateway.domain.provider;

import com.pgy.gateway.config.GatewayProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * 提供方注册表。
 *
 * 启动时读取 application.yml 里的 gateway.providers 配置，
 * 按优先级排好序，并实例化出对应的适配器。
 *
 * 之后整个系统要"有哪些模型可用"，都来这里问。
 */
@Slf4j
@Component
public class ProviderRegistry {

    private final GatewayProperties properties;

    /** 已按优先级升序排好（数字小的在前） */
    private final List<ChatProvider> providers;

    public ProviderRegistry(GatewayProperties properties) {
        this.properties = properties;
        this.providers = buildProviders();
    }

    private List<ChatProvider> buildProviders() {
        List<ChatProvider> result = properties.getProviders().stream()
                .sorted(Comparator.comparingInt(GatewayProperties.ProviderConfig::getPriority))
                .filter(GatewayProperties.ProviderConfig::isEnabled)
                .filter(this::hasUsableCredentials)
                .map(this::create)
                .toList();

        if (result.isEmpty()) {
            throw new IllegalStateException(
                    "没有任何可用的模型提供方。请检查 gateway.providers 配置，"
                            + "至少要保留一个 type=mock 的提供方作为兜底。");
        }

        log.info("已注册 {} 个模型提供方（按优先级）：{}",
                result.size(), result.stream().map(ChatProvider::name).toList());
        return result;
    }

    /**
     * 检查提供方是否有可用凭据。
     * 没配 api-key 的提供方会被跳过 —— 这样"还没申请密钥"不会导致启动失败。
     */
    private boolean hasUsableCredentials(GatewayProperties.ProviderConfig config) {
        if (config.hasCredentials()) {
            return true;
        }
        log.warn("提供方 [{}] 未配置 api-key，已跳过。配置好密钥后重启即可启用。", config.getName());
        return false;
    }

    private ChatProvider create(GatewayProperties.ProviderConfig config) {
        String type = config.getType() == null ? "" : config.getType().trim().toLowerCase();
        return switch (type) {
            case "mock" -> new MockProvider(config);
            case "openai-compatible" -> new OpenAiCompatibleProvider(config);
            default -> throw new IllegalStateException(
                    "提供方 [" + config.getName() + "] 的 type 不支持：" + config.getType()
                            + "（可选值：mock / openai-compatible）");
        };
    }

    /** 全部提供方，已按优先级排序 */
    public List<ChatProvider> all() {
        return providers;
    }

    public Optional<ChatProvider> findByName(String name) {
        return providers.stream().filter(p -> p.name().equals(name)).findFirst();
    }

    public GatewayProperties.ProviderConfig configOf(String providerName) {
        return properties.getProviders().stream()
                .filter(c -> c.getName().equals(providerName))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("找不到提供方配置：" + providerName));
    }

    /**
     * 决定"这次请求该用哪个模型名"。
     *
     * 情况一：调用方指定了模型，且这家提供方支持 → 用指定的
     * 情况二：调用方没指定，或者指定的模型这家不支持 → 用这家提供方的第一个模型
     *
     * 情况二正是降级链能工作的关键：主模型是 deepseek-chat，降级到 Kimi 时，
     * 模型名必须换成 Kimi 认识的 moonshot-v1-8k，否则对方会返回 400。
     */
    public String pickModel(String providerName, String requestedModel) {
        GatewayProperties.ProviderConfig config = configOf(providerName);

        if (requestedModel != null && config.getModels().contains(requestedModel)) {
            return requestedModel;
        }
        if (config.getModels().isEmpty()) {
            return requestedModel;
        }
        return config.getModels().get(0);
    }
}
