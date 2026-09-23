package com.pgy.gateway.api.dto;

import com.pgy.gateway.infrastructure.persistence.entity.UsageLogEntity;

import java.time.Instant;

/**
 * 调用日志的对外视图。
 */
public record UsageLogView(
        String requestId,
        String providerName,
        String model,
        int promptTokens,
        int completionTokens,
        int totalTokens,
        long latencyMs,
        boolean success,
        boolean fallbackUsed,
        String errorMessage,
        Instant createdAt
) {

    public static UsageLogView from(UsageLogEntity entity) {
        return new UsageLogView(
                entity.getRequestId(),
                entity.getProviderName(),
                entity.getModel(),
                entity.getPromptTokens(),
                entity.getCompletionTokens(),
                entity.getTotalTokens(),
                entity.getLatencyMs(),
                entity.isSuccess(),
                entity.isFallbackUsed(),
                entity.getErrorMessage(),
                entity.getCreatedAt()
        );
    }
}
