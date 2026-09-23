package com.pgy.gateway.domain.chat;

import lombok.Builder;
import lombok.Data;

/**
 * 网关对外返回的统一响应格式。
 */
@Data
@Builder(toBuilder = true)
public class ChatResponse {

    /** 本次请求的唯一编号，排查问题时用它串起所有日志 */
    private String requestId;

    /** 模型生成的正文 */
    private String content;

    /** 实际使用的模型 */
    private String model;

    /** 实际服务的提供方名字 */
    private String provider;

    /** 用量统计 */
    private TokenUsage usage;

    /** 本次是否发生了降级（主模型失败、换了备用模型） */
    private boolean fallbackUsed;

    /** 已经尝试过几个提供方 */
    private int attemptCount;

    /** 端到端耗时（毫秒） */
    private long latencyMs;
}
