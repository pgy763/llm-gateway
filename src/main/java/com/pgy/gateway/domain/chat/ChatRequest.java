package com.pgy.gateway.domain.chat;

import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.util.List;

/**
 * 网关对外暴露的统一请求格式。
 *
 * 这是"统一协议"的关键：不管背后接的是 DeepSeek、Kimi 还是别的厂商，
 * 调用方都只用这一套格式，不需要知道各家的差异。
 */
@Data
public class ChatRequest {

    /** 对话消息列表，最后一条通常是用户本次输入 */
    @NotEmpty(message = "messages 不能为空")
    private List<ChatMessage> messages;

    /**
     * 期望使用的模型名，可为空。
     * 留空时由路由器按优先级自动挑选。
     */
    private String model;

    /** 是否使用流式返回（打字机效果） */
    private Boolean stream = Boolean.FALSE;

    /** 采样温度，越大输出越随机 */
    private Double temperature;

    /** 本次回答的最大长度 */
    private Integer maxTokens;

    public boolean isStreaming() {
        return Boolean.TRUE.equals(stream);
    }
}
