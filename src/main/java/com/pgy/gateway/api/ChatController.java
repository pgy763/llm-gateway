package com.pgy.gateway.api;

import com.pgy.gateway.application.ChatService;
import com.pgy.gateway.common.ApiResponse;
import com.pgy.gateway.domain.chat.ChatRequest;
import com.pgy.gateway.domain.chat.ChatResponse;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 对话接口。
 *
 * 调用方用虚拟密钥（请求头 X-Api-Key）来鉴权，
 * 不需要知道背后到底用的是哪家模型。
 */
@RestController
@RequestMapping("/v1/chat")
public class ChatController {

    /** 携带虚拟密钥的请求头 */
    public static final String API_KEY_HEADER = "X-Api-Key";

    private final ChatService chatService;

    public ChatController(ChatService chatService) {
        this.chatService = chatService;
    }

    /**
     * 普通对话：等模型全部生成完，一次性返回。
     *
     * 示例：
     *   curl -X POST http://localhost:8080/v1/chat \
     *        -H "X-Api-Key: sk-demo-0001" \
     *        -H "Content-Type: application/json" \
     *        -d '{"messages":[{"role":"user","content":"用一句话解释限流"}]}'
     */
    @PostMapping
    public ApiResponse<ChatResponse> chat(
            @RequestHeader(value = API_KEY_HEADER, required = false) String apiKey,
            @Valid @RequestBody ChatRequest request) {
        return ApiResponse.ok(chatService.chat(apiKey, request));
    }

    /**
     * 流式对话：模型每生成一小段就推送一次，前端可以立刻渲染出"打字机"效果。
     *
     * 返回的是 SSE（Server-Sent Events）流，事件有三种：
     *   delta  一小段正文    {"text": "限流就"}
     *   done   结束          {"requestId":"...","provider":"...","totalTokens":123}
     *   error  出错          {"message":"...","alreadyEmitted":false}
     *
     * 用 curl 观察效果：
     *   curl -N -X POST http://localhost:8080/v1/chat/stream \
     *        -H "X-Api-Key: sk-demo-0001" \
     *        -H "Content-Type: application/json" \
     *        -d '{"messages":[{"role":"user","content":"讲个笑话"}],"stream":true}'
     */
    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(
            @RequestHeader(value = API_KEY_HEADER, required = false) String apiKey,
            @Valid @RequestBody ChatRequest request) {
        return chatService.chatStream(apiKey, request);
    }
}
