package com.pgy.gateway.application;

import com.pgy.gateway.common.BizException;
import com.pgy.gateway.config.GatewayProperties;
import com.pgy.gateway.domain.chat.ChatRequest;
import com.pgy.gateway.domain.chat.ChatResponse;
import com.pgy.gateway.domain.chat.TokenUsage;
import com.pgy.gateway.domain.provider.ChatProvider;
import com.pgy.gateway.domain.provider.ProviderException;
import com.pgy.gateway.domain.provider.ProviderRegistry;
import com.pgy.gateway.domain.route.ProviderHealthTracker;
import com.pgy.gateway.domain.route.RoutePlanner;
import com.pgy.gateway.infrastructure.persistence.entity.ApiKeyEntity;
import com.pgy.gateway.infrastructure.ratelimit.TokenBucketRateLimiter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.task.TaskExecutor;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 网关主流程 —— 把前面所有模块串成一条完整链路。
 *
 * 一次请求会依次经过：
 *   1. 鉴权        这个密钥是谁的？有没有被禁用？
 *   2. 限流        他是不是刷得太快了？
 *   3. 配额        他这个月的额度还剩多少？
 *   4. 路由        按什么顺序去尝试哪些提供方？（降级链）
 *   5. 调用        真正去打上游模型
 *   6. 熔断记录    成功了清计数，失败了累加计数
 *   7. 记账        写日志、扣配额
 */
@Slf4j
@Service
public class ChatService {

    /** SSE 连接最长存活时间（毫秒） */
    private static final long SSE_TIMEOUT_MS = 180_000L;

    private final ProviderRegistry providerRegistry;

    private final RoutePlanner routePlanner;

    private final ProviderHealthTracker healthTracker;

    private final TokenBucketRateLimiter rateLimiter;

    private final ApiKeyService apiKeyService;

    private final MeteringService meteringService;

    private final GatewayProperties properties;

    private final TaskExecutor gatewayTaskExecutor;

    public ChatService(ProviderRegistry providerRegistry,
                       RoutePlanner routePlanner,
                       ProviderHealthTracker healthTracker,
                       TokenBucketRateLimiter rateLimiter,
                       ApiKeyService apiKeyService,
                       MeteringService meteringService,
                       GatewayProperties properties,
                       TaskExecutor gatewayTaskExecutor) {
        this.providerRegistry = providerRegistry;
        this.routePlanner = routePlanner;
        this.healthTracker = healthTracker;
        this.rateLimiter = rateLimiter;
        this.apiKeyService = apiKeyService;
        this.meteringService = meteringService;
        this.properties = properties;
        this.gatewayTaskExecutor = gatewayTaskExecutor;
    }

    // ==================================================================
    // 非流式
    // ==================================================================

    public ChatResponse chat(String apiKey, ChatRequest request) {
        ApiKeyEntity keyEntity = prepare(apiKey);

        String requestId = newRequestId();
        long start = System.currentTimeMillis();

        List<ChatProvider> chain = routePlanner.plan(request.getModel());
        List<String> errors = new ArrayList<>();

        log.info("请求 {} 降级链：{}", requestId,
                chain.stream().map(ChatProvider::name).toList());

        for (ChatProvider provider : chain) {
            String model = providerRegistry.pickModel(provider.name(), request.getModel());
            long attemptStart = System.currentTimeMillis();

            try {
                ChatResponse response = provider.chat(request, model);
                healthTracker.recordSuccess(provider.name());

                ChatResponse finalResponse = response.toBuilder()
                        .requestId(requestId)
                        .provider(provider.name())
                        .fallbackUsed(!errors.isEmpty())
                        .attemptCount(errors.size() + 1)
                        .latencyMs(System.currentTimeMillis() - start)
                        .build();

                meteringService.recordSuccess(requestId, keyEntity.getId(), provider.name(), model,
                        finalResponse.getUsage(), finalResponse.getLatencyMs(), finalResponse.isFallbackUsed());

                log.info("请求 {} 成功，由 [{}] 用模型 [{}] 完成，耗时 {}ms，尝试 {} 次",
                        requestId, provider.name(), model,
                        finalResponse.getLatencyMs(), finalResponse.getAttemptCount());
                return finalResponse;

            } catch (ProviderException e) {
                errors.add(e.getMessage());
                healthTracker.recordFailure(provider.name());

                log.warn("请求 {} 由 [{}] 调用失败（耗时 {}ms，可重试={}）：{}",
                        requestId, provider.name(), System.currentTimeMillis() - attemptStart,
                        e.isRetryable(), e.getMessage());

                if (!e.isRetryable()) {
                    // 参数错、密钥无效这类问题，换一家也是一样的结果，不必浪费额度
                    log.warn("请求 {} 遇到不可重试的错误，终止降级", requestId);
                    break;
                }
            }
        }

        String message = "所有模型提供方均调用失败：" + String.join(" ｜ ", errors);
        meteringService.recordFailure(requestId, keyEntity.getId(), message, System.currentTimeMillis() - start);
        throw new BizException(503, message);
    }

    // ==================================================================
    // 流式
    // ==================================================================

    /**
     * 流式对话。
     *
     * 返回 SseEmitter 后立即交给后台线程去跑，HTTP 线程被释放出来接别的请求。
     * 模型每生成一小段，就通过 SSE 推给客户端。
     */
    public SseEmitter chatStream(String apiKey, ChatRequest request) {
        ApiKeyEntity keyEntity = prepare(apiKey);

        String requestId = newRequestId();
        long start = System.currentTimeMillis();

        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);
        emitter.onTimeout(() -> {
            log.warn("请求 {} 的 SSE 连接超时", requestId);
            emitter.complete();
        });
        emitter.onError(e -> log.warn("请求 {} 的 SSE 连接异常：{}", requestId, e.getMessage()));

        gatewayTaskExecutor.execute(() -> streamWithFallback(emitter, request, requestId, keyEntity, start));

        return emitter;
    }

    private void streamWithFallback(SseEmitter emitter, ChatRequest request, String requestId,
                                    ApiKeyEntity keyEntity, long start) {
        List<ChatProvider> chain = routePlanner.plan(request.getModel());
        List<String> errors = new ArrayList<>();

        for (ChatProvider provider : chain) {
            String model = providerRegistry.pickModel(provider.name(), request.getModel());

            // 记录"这次尝试有没有已经往外吐过内容"。
            // 这一点至关重要，见下面 catch 块里的说明。
            AtomicBoolean alreadyEmitted = new AtomicBoolean(false);

            try {
                TokenUsage usage = provider.chatStream(request, model, delta -> {
                    alreadyEmitted.set(true);
                    sendEvent(emitter, "delta", Map.of("text", delta));
                });

                healthTracker.recordSuccess(provider.name());

                long latency = System.currentTimeMillis() - start;
                boolean fallbackUsed = !errors.isEmpty();

                meteringService.recordSuccess(requestId, keyEntity.getId(), provider.name(), model,
                        usage, latency, fallbackUsed);

                Map<String, Object> done = new LinkedHashMap<>();
                done.put("requestId", requestId);
                done.put("provider", provider.name());
                done.put("model", model);
                done.put("fallbackUsed", fallbackUsed);
                done.put("latencyMs", latency);
                done.put("promptTokens", usage.getPromptTokens());
                done.put("completionTokens", usage.getCompletionTokens());
                done.put("totalTokens", usage.getTotalTokens());
                sendEvent(emitter, "done", done);

                emitter.complete();
                return;

            } catch (ProviderException e) {
                healthTracker.recordFailure(provider.name());
                errors.add(e.getMessage());

                if (alreadyEmitted.get()) {
                    // 【流式降级的边界】
                    // 一旦已经有内容推给了客户端，就不能再换一家重来了 ——
                    // 否则用户会看到"前半段是 A 模型写的、后半段是 B 模型写的"，
                    // 上下文接不上，读起来是断裂的。这种情况下只能如实终止。
                    log.warn("请求 {} 在已输出内容后失败，无法降级：{}", requestId, e.getMessage());
                    sendEvent(emitter, "error", Map.of(
                            "message", "生成过程中断：" + e.getMessage(),
                            "alreadyEmitted", true));
                    emitter.complete();
                    return;
                }

                log.warn("请求 {} 由 [{}] 流式调用失败，准备降级：{}",
                        requestId, provider.name(), e.getMessage());

                if (!e.isRetryable()) {
                    break;
                }
            }
        }

        String message = "所有模型提供方均调用失败：" + String.join(" ｜ ", errors);
        meteringService.recordFailure(requestId, keyEntity.getId(), message, System.currentTimeMillis() - start);
        sendEvent(emitter, "error", Map.of("message", message, "alreadyEmitted", false));
        emitter.complete();
    }

    /**
     * 推送一个 SSE 事件。
     * 事件名含义：
     *   delta  一小段正文
     *   done   全部结束，附带本次的用量与提供方信息
     *   error  出错了
     */
    private void sendEvent(SseEmitter emitter, String name, Object data) {
        try {
            emitter.send(SseEmitter.event().name(name).data(data, MediaType.APPLICATION_JSON));
        } catch (IOException | IllegalStateException e) {
            // 客户端提前断开是常见情况（用户关掉页面），不值得当成错误刷屏
            throw new ProviderException("sse", "向客户端发送数据失败（连接可能已关闭）", false, e);
        }
    }

    // ==================================================================
    // 前置校验
    // ==================================================================

    /** 鉴权 → 限流 → 配额，三步都过了才允许往下走 */
    private ApiKeyEntity prepare(String apiKey) {
        ApiKeyEntity keyEntity = apiKeyService.authenticate(apiKey);

        if (!rateLimiter.tryAcquire(keyEntity.getKeyValue())) {
            throw BizException.rateLimited(String.format(
                    "请求过于频繁，已触发限流。当前配置：桶容量 %d，每秒补充 %d 个令牌",
                    properties.getRateLimit().getCapacity(),
                    properties.getRateLimit().getRefillPerSecond()));
        }

        if (keyEntity.remainingTokens() <= 0) {
            throw BizException.quotaExceeded(String.format(
                    "配额已用尽（已用 %d / 总额 %d）",
                    keyEntity.getUsedTokens(), keyEntity.getQuotaTokens()));
        }

        return keyEntity;
    }

    private String newRequestId() {
        return "req-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }
}
