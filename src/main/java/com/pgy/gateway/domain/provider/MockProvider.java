package com.pgy.gateway.domain.provider;

import com.pgy.gateway.config.GatewayProperties;
import com.pgy.gateway.domain.chat.ChatMessage;
import com.pgy.gateway.domain.chat.ChatRequest;
import com.pgy.gateway.domain.chat.ChatResponse;
import com.pgy.gateway.domain.chat.TokenUsage;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * 模拟提供方。
 *
 * 【为什么要有它？】
 * 1. 让别人 clone 下项目后不用申请任何密钥就能把全流程跑通 —— 这是能不能被"快速验证"的关键。
 * 2. 单元测试里需要它来制造各种故障场景。
 *
 * 【三个特殊模型名】
 *   mock-fast    正常返回，流式时切分得比较碎，用来演示打字机效果
 *   mock-normal  正常返回
 *   mock-flaky   前两次调用必定失败，用来演示"降级链自动切换备用模型"
 */
@Slf4j
public class MockProvider implements ChatProvider {

    private static final String MODEL_FAST = "mock-fast";
    private static final String MODEL_FLAKY = "mock-flaky";

    /** 记录 mock-flaky 被调用了几次，前两次故意失败 */
    private final AtomicInteger flakyCallCount = new AtomicInteger(0);

    private final GatewayProperties.ProviderConfig config;

    public MockProvider(GatewayProperties.ProviderConfig config) {
        this.config = config;
    }

    @Override
    public String name() {
        return config.getName();
    }

    @Override
    public boolean supports(String model) {
        return config.getModels().contains(model);
    }

    @Override
    public ChatResponse chat(ChatRequest request, String model) {
        simulateFailureIfNeeded(model);

        String content = buildContent(request, model);

        return ChatResponse.builder()
                .content(content)
                .model(model)
                .provider(name())
                .usage(estimateUsage(request, content))
                .attemptCount(1)
                .build();
    }

    @Override
    public TokenUsage chatStream(ChatRequest request, String model, Consumer<String> onDelta) {
        simulateFailureIfNeeded(model);

        String content = buildContent(request, model);
        int step = MODEL_FAST.equals(model) ? 2 : 6;

        for (int i = 0; i < content.length(); i += step) {
            onDelta.accept(content.substring(i, Math.min(content.length(), i + step)));
            sleepQuietly(25);
        }
        return estimateUsage(request, content);
    }

    // ------------------------------------------------------------------
    // 内部实现
    // ------------------------------------------------------------------

    /**
     * 模拟上游故障。
     * mock-flaky 的前两次调用会抛出"可重试"异常，
     * 降级链捕获后就会自动切到下一家提供方。
     */
    private void simulateFailureIfNeeded(String model) {
        if (!MODEL_FLAKY.equals(model)) {
            return;
        }
        int count = flakyCallCount.incrementAndGet();
        if (count <= 2) {
            throw new ProviderException(name(),
                    "模拟故障：上游返回 503 Service Unavailable（第 " + count + " 次调用）",
                    true, null);
        }
    }

    private String buildContent(ChatRequest request, String model) {
        String userInput = request.getMessages().stream()
                .filter(m -> "user".equals(m.getRole()))
                .reduce((first, second) -> second)
                .map(ChatMessage::getContent)
                .orElse("（空输入）");

        String preview = userInput.length() > 50 ? userInput.substring(0, 50) + "…" : userInput;

        return "【模拟回答 · " + model + "】针对「" + preview + "」这个问题，"
                + "这是一段由模拟提供方生成的演示内容。"
                + "它的作用是让你在没有真实密钥的情况下，"
                + "也能完整跑通鉴权、限流、路由、计量、任务编排这一整套链路。"
                + "把 application.yml 里对应提供方的 api-key 填上真实密钥，"
                + "它就会自动切换过去。";
    }

    private TokenUsage estimateUsage(ChatRequest request, String content) {
        int promptChars = request.getMessages().stream()
                .mapToInt(m -> m.getContent() == null ? 0 : m.getContent().length())
                .sum();
        int promptTokens = Math.max(1, promptChars / 2);
        int completionTokens = Math.max(1, content.length() / 2);
        return TokenUsage.of(promptTokens, completionTokens);
    }

    private void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
