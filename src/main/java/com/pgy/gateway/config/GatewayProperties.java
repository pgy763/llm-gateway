package com.pgy.gateway.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * 对应 application.yml 里 gateway.* 这一整块配置。
 *
 * 把"接了哪些厂商、优先级怎么排、限流多大"全部做成配置，
 * 改行为不需要重新编译代码。
 */
@Data
@ConfigurationProperties(prefix = "gateway")
public class GatewayProperties {

    /** 上游模型提供方列表 */
    private List<ProviderConfig> providers = new ArrayList<>();

    /** 路由与降级策略 */
    private RouteConfig route = new RouteConfig();

    /** 限流参数 */
    private RateLimitConfig rateLimit = new RateLimitConfig();

    /** 任务编排参数 */
    private TaskConfig task = new TaskConfig();

    @Data
    public static class ProviderConfig {

        /** 提供方唯一名字 */
        private String name;

        /** 适配器类型：openai-compatible / mock */
        private String type;

        /** 接口地址 */
        private String baseUrl;

        /** 真实密钥。留空则该提供方会被自动跳过 */
        private String apiKey;

        /** 该提供方支持的模型名列表 */
        private List<String> models = new ArrayList<>();

        /** 优先级，数字越小越优先 */
        private int priority = 100;

        /** 是否启用 */
        private boolean enabled = true;

        /** 单次调用超时（毫秒） */
        private long timeoutMs = 60_000;

        /** 是否具备可用的凭据（mock 类型不需要） */
        public boolean hasCredentials() {
            return "mock".equalsIgnoreCase(type)
                    || (apiKey != null && !apiKey.isBlank());
        }
    }

    @Data
    public static class RouteConfig {

        /** 一次请求最多尝试几个提供方 */
        private int maxAttempts = 3;

        /** 连续失败多少次后，把该提供方标记为"不健康" */
        private int unhealthyThreshold = 3;

        /** 标记为不健康后，多少秒内不再尝试它 */
        private int cooldownSeconds = 30;
    }

    @Data
    public static class RateLimitConfig {

        private boolean enabled = true;

        /** 令牌桶容量：允许瞬间打满多少个请求 */
        private int capacity = 20;

        /** 每秒补充多少个令牌：即长期平均速率上限 */
        private int refillPerSecond = 5;
    }

    @Data
    public static class TaskConfig {

        /** 审查不通过时，最多重新生成几次 */
        private int maxRetry = 2;

        /** 是否开启审查环节。关掉后任务生成完就直接定稿 */
        private boolean reviewEnabled = true;

        /** 后台执行任务的线程数 */
        private int workerPoolSize = 4;

        /** 补偿扫描的间隔（毫秒） */
        private long pollIntervalMs = 2000;
    }
}
