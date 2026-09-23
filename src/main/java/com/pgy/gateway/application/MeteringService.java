package com.pgy.gateway.application;

import com.pgy.gateway.domain.chat.TokenUsage;
import com.pgy.gateway.infrastructure.persistence.entity.UsageLogEntity;
import com.pgy.gateway.infrastructure.persistence.repo.ApiKeyRepository;
import com.pgy.gateway.infrastructure.persistence.repo.UsageLogRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * 计量与记账。
 *
 * 每次调用结束后做两件事：
 *   1. 往 usage_log 写一条明细（谁、什么时候、用了哪个模型、花了多少 token、耗时多久）
 *   2. 把这次消耗累加到密钥的已用量上
 */
@Slf4j
@Service
public class MeteringService {

    private final UsageLogRepository usageLogRepository;

    private final ApiKeyRepository apiKeyRepository;

    public MeteringService(UsageLogRepository usageLogRepository, ApiKeyRepository apiKeyRepository) {
        this.usageLogRepository = usageLogRepository;
        this.apiKeyRepository = apiKeyRepository;
    }

    /** 记录一次成功的调用 */
    @Transactional
    public void recordSuccess(String requestId, Long apiKeyId, String providerName, String model,
                              TokenUsage usage, long latencyMs, boolean fallbackUsed) {
        save(UsageLogEntity.builder()
                .requestId(requestId)
                .apiKeyId(apiKeyId)
                .providerName(providerName)
                .model(model)
                .promptTokens(usage.getPromptTokens())
                .completionTokens(usage.getCompletionTokens())
                .totalTokens(usage.getTotalTokens())
                .latencyMs(latencyMs)
                .success(true)
                .fallbackUsed(fallbackUsed)
                .createdAt(Instant.now())
                .build());

        if (apiKeyId != null && usage.getTotalTokens() > 0) {
            apiKeyRepository.findById(apiKeyId).ifPresent(key -> {
                key.consume(usage.getTotalTokens());
                apiKeyRepository.save(key);
            });
        }
    }

    /** 记录一次失败的调用 */
    @Transactional
    public void recordFailure(String requestId, Long apiKeyId, String errorMessage, long latencyMs) {
        save(UsageLogEntity.builder()
                .requestId(requestId)
                .apiKeyId(apiKeyId)
                .success(false)
                .fallbackUsed(false)
                .errorMessage(errorMessage)
                .latencyMs(latencyMs)
                .createdAt(Instant.now())
                .build());
    }

    /**
     * 落库时兜住异常。
     *
     * 理由：用户已经拿到回答了，不能因为"日志写不进去"就把它变成一次失败。
     * 记账是辅助功能，可用性优先级低于主流程。
     */
    private void save(UsageLogEntity entity) {
        try {
            usageLogRepository.save(entity);
        } catch (Exception e) {
            log.error("写入调用日志失败，requestId={}", entity.getRequestId(), e);
        }
    }
}
