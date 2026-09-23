package com.pgy.gateway.domain.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pgy.gateway.config.GatewayProperties;
import com.pgy.gateway.domain.chat.ChatMessage;
import com.pgy.gateway.domain.chat.ChatRequest;
import com.pgy.gateway.domain.chat.ChatResponse;
import com.pgy.gateway.domain.chat.TokenUsage;
import lombok.extern.slf4j.Slf4j;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * 「OpenAI 兼容协议」适配器。
 *
 * 【为什么叫"兼容"？】
 * 现在国内主流厂商（DeepSeek、Kimi、智谱、通义等）都提供了与 OpenAI 一致的接口：
 * 地址格式一样、请求体一样、返回体一样。所以一份代码就能同时对接它们，
 * 只需要在配置里换 base-url 和 api-key。
 *
 * 【这里用 JDK 自带的 HttpClient，没有引入额外的 HTTP 库】
 * 原因是：流式转发本质上就是"读一段文本、马上吐一段文本"，
 * 用标准库反而更容易讲清楚它到底在做什么，也少一层依赖。
 *
 * 【流式响应长什么样？】
 * 上游返回的是一串这样的文本（SSE 格式，每段以 data: 开头，空行分隔）：
 *     data: {"choices":[{"delta":{"content":"你"}}]}
 *     data: {"choices":[{"delta":{"content":"好"}}]}
 *     data: [DONE]
 * 所以下面的解析逻辑就是：逐行读 → 去掉 "data: " 前缀 → 取出 delta.content → 立刻回调出去。
 */
@Slf4j
public class OpenAiCompatibleProvider implements ChatProvider {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String CHAT_COMPLETIONS_PATH = "/chat/completions";

    private final GatewayProperties.ProviderConfig config;

    private final HttpClient httpClient;

    public OpenAiCompatibleProvider(GatewayProperties.ProviderConfig config) {
        this.config = config;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    @Override
    public String name() {
        return config.getName();
    }

    @Override
    public boolean supports(String model) {
        return config.getModels().contains(model);
    }

    // ------------------------------------------------------------------
    // 非流式
    // ------------------------------------------------------------------

    @Override
    public ChatResponse chat(ChatRequest request, String model) {
        long start = System.currentTimeMillis();
        String body = buildRequestBody(request, model, false);

        JsonNode root = readJson(send(body).body());
        String content = root.path("choices").path(0).path("message").path("content").asText("");

        return ChatResponse.builder()
                .content(content)
                .model(model)
                .provider(name())
                .usage(parseUsage(root))
                .attemptCount(1)
                .latencyMs(System.currentTimeMillis() - start)
                .build();
    }

    // ------------------------------------------------------------------
    // 流式
    // ------------------------------------------------------------------

    @Override
    public TokenUsage chatStream(ChatRequest request, String model, Consumer<String> onDelta) {
        String body = buildRequestBody(request, model, true);
        HttpRequest httpRequest = buildHttpRequest(body);

        AtomicReference<TokenUsage> usageRef = new AtomicReference<>(TokenUsage.empty());

        try {
            HttpResponse<Stream<String>> response =
                    httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofLines());

            if (response.statusCode() >= 400) {
                String errorBody;
                try (Stream<String> lines = response.body()) {
                    errorBody = String.join("\n", lines.toList());
                }
                throw toProviderException(response.statusCode(), errorBody);
            }

            try (Stream<String> lines = response.body()) {
                lines.forEach(line -> consumeSseLine(line, onDelta, usageRef));
            }
        } catch (ProviderException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ProviderException(name(), "请求被中断", true, e);
        } catch (Exception e) {
            throw new ProviderException(name(), "流式请求失败：" + e.getMessage(), true, e);
        }

        return usageRef.get();
    }

    /** 处理流式响应中的一行 */
    private void consumeSseLine(String rawLine, Consumer<String> onDelta, AtomicReference<TokenUsage> usageRef) {
        if (rawLine == null) {
            return;
        }
        String line = rawLine.trim();

        // SSE 里空行是分隔符，其他字段（如 event:）我们不关心
        if (line.isEmpty() || !line.startsWith("data:")) {
            return;
        }

        String payload = line.substring("data:".length()).trim();
        if (payload.isEmpty() || "[DONE]".equals(payload)) {
            return;
        }

        JsonNode node;
        try {
            node = MAPPER.readTree(payload);
        } catch (Exception e) {
            // 单个分片解析失败不应该中断整个流，跳过即可
            log.warn("[{}] 流式分片解析失败，已跳过：{}", name(), abbreviate(payload, 120));
            return;
        }

        String delta = node.path("choices").path(0).path("delta").path("content").asText("");
        if (!delta.isEmpty()) {
            // 注意：这一行故意不包在 try 里面。
            // 如果下游抛出异常（例如客户端已经断开连接），必须让它一路冒泡出去，
            // 否则会被当成"某个分片解析失败"悄悄吞掉 ——
            // 结果是连接早就断了，后台还在傻傻地跑完整个生成过程。
            onDelta.accept(delta);
        }

        // 用量统计通常只在最后一个分片里返回
        JsonNode usage = node.get("usage");
        if (usage != null && !usage.isNull()) {
            usageRef.set(parseUsage(node));
        }
    }

    // ------------------------------------------------------------------
    // 请求构造与响应解析
    // ------------------------------------------------------------------

    private String buildRequestBody(ChatRequest request, String model, boolean stream) {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("model", model);
        root.put("stream", stream);

        ArrayNode messages = root.putArray("messages");
        for (ChatMessage message : request.getMessages()) {
            ObjectNode node = messages.addObject();
            node.put("role", message.getRole());
            node.put("content", message.getContent());
        }

        if (request.getTemperature() != null) {
            root.put("temperature", request.getTemperature());
        }
        if (request.getMaxTokens() != null) {
            root.put("max_tokens", request.getMaxTokens());
        }
        if (stream) {
            // 请求上游在流式模式下一并返回用量，否则我们只能自己估算 token
            root.putObject("stream_options").put("include_usage", true);
        }

        try {
            return MAPPER.writeValueAsString(root);
        } catch (Exception e) {
            throw new ProviderException(name(), "构造请求体失败", false, e);
        }
    }

    private HttpRequest buildHttpRequest(String body) {
        return HttpRequest.newBuilder()
                .uri(URI.create(normalizeBaseUrl() + CHAT_COMPLETIONS_PATH))
                .timeout(Duration.ofMillis(config.getTimeoutMs()))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + config.getApiKey())
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
    }

    /** 去掉 base-url 结尾多余的斜杠 */
    private String normalizeBaseUrl() {
        String base = config.getBaseUrl() == null ? "" : config.getBaseUrl().trim();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return base;
    }

    private HttpResponse<String> send(String body) {
        try {
            HttpResponse<String> response =
                    httpClient.send(buildHttpRequest(body), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

            if (response.statusCode() >= 400) {
                throw toProviderException(response.statusCode(), response.body());
            }
            return response;
        } catch (ProviderException e) {
            throw e;
        } catch (HttpTimeoutException e) {
            throw new ProviderException(name(), "请求超时（超过 " + config.getTimeoutMs() + "ms）", true, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ProviderException(name(), "请求被中断", true, e);
        } catch (Exception e) {
            throw new ProviderException(name(), "请求失败：" + e.getMessage(), true, e);
        }
    }

    /**
     * 把 HTTP 错误码翻译成"要不要换一家试试"。
     *
     * 这个判断是降级链能否正确工作的前提：
     *   - 429（限流）、5xx（服务端故障）→ 可重试，换下一家
     *   - 400（参数错）、401（密钥无效）、403（无权限）→ 不可重试，换了也一样
     */
    private ProviderException toProviderException(int statusCode, String body) {
        boolean retryable = statusCode == 429 || statusCode >= 500;
        String message = "上游返回 HTTP " + statusCode + "：" + abbreviate(body, 300);
        return new ProviderException(name(), message, retryable, null);
    }

    private JsonNode readJson(String body) {
        try {
            return MAPPER.readTree(body);
        } catch (Exception e) {
            throw new ProviderException(name(), "响应不是合法 JSON：" + abbreviate(body, 200), false, e);
        }
    }

    private TokenUsage parseUsage(JsonNode node) {
        JsonNode usage = node.path("usage");
        if (usage.isMissingNode() || usage.isNull()) {
            return TokenUsage.empty();
        }
        return TokenUsage.of(
                usage.path("prompt_tokens").asInt(0),
                usage.path("completion_tokens").asInt(0)
        );
    }

    private String abbreviate(String text, int maxLength) {
        if (text == null) {
            return "";
        }
        return text.length() <= maxLength ? text : text.substring(0, maxLength) + "…";
    }
}
