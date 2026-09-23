package com.pgy.gateway.api.dto;

import com.pgy.gateway.infrastructure.persistence.entity.ApiKeyEntity;

import java.time.Instant;

/**
 * 虚拟密钥的对外视图。
 */
public record ApiKeyView(
        Long id,
        String keyValue,
        String displayName,
        boolean enabled,
        long quotaTokens,
        long usedTokens,
        long remainingTokens,
        double usageRatio,
        Instant createdAt
) {

    public static ApiKeyView from(ApiKeyEntity entity) {
        return new ApiKeyView(
                entity.getId(),
                entity.getKeyValue(),
                entity.getDisplayName(),
                entity.isEnabled(),
                entity.getQuotaTokens(),
                entity.getUsedTokens(),
                entity.remainingTokens(),
                entity.usageRatio(),
                entity.getCreatedAt()
        );
    }
}
