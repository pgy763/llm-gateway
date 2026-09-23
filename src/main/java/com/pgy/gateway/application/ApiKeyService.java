package com.pgy.gateway.application;

import com.pgy.gateway.common.BizException;
import com.pgy.gateway.infrastructure.persistence.entity.ApiKeyEntity;
import com.pgy.gateway.infrastructure.persistence.repo.ApiKeyRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 虚拟密钥的鉴权与管理。
 */
@Slf4j
@Service
public class ApiKeyService {

    private final ApiKeyRepository apiKeyRepository;

    public ApiKeyService(ApiKeyRepository apiKeyRepository) {
        this.apiKeyRepository = apiKeyRepository;
    }

    /**
     * 校验密钥。
     * 三种失败情况分别给出不同的提示，方便调用方自己排查 ——
     * "没带密钥"和"密钥无效"完全是两回事。
     */
    public ApiKeyEntity authenticate(String keyValue) {
        if (keyValue == null || keyValue.isBlank()) {
            throw BizException.unauthorized("缺少 API Key，请在请求头 X-Api-Key 中携带");
        }

        ApiKeyEntity entity = apiKeyRepository.findByKeyValue(keyValue.trim())
                .orElseThrow(() -> BizException.unauthorized("无效的 API Key"));

        if (!entity.isEnabled()) {
            throw BizException.unauthorized("该 API Key 已被禁用");
        }
        return entity;
    }

    public List<ApiKeyEntity> listAll() {
        return apiKeyRepository.findAll();
    }

    @Transactional
    public ApiKeyEntity create(String displayName, long quotaTokens) {
        ApiKeyEntity entity = new ApiKeyEntity();
        entity.setKeyValue("sk-" + UUID.randomUUID().toString().replace("-", "").substring(0, 24));
        entity.setDisplayName(displayName);
        entity.setEnabled(true);
        entity.setQuotaTokens(quotaTokens);
        entity.setUsedTokens(0L);
        entity.setCreatedAt(Instant.now());
        entity.setUpdatedAt(Instant.now());

        ApiKeyEntity saved = apiKeyRepository.save(entity);
        log.info("新建虚拟密钥：{}（{}）", saved.getDisplayName(), saved.getKeyValue());
        return saved;
    }

    @Transactional
    public ApiKeyEntity setEnabled(Long id, boolean enabled) {
        ApiKeyEntity entity = apiKeyRepository.findById(id)
                .orElseThrow(() -> BizException.notFound("密钥不存在：" + id));
        entity.setEnabled(enabled);
        entity.setUpdatedAt(Instant.now());
        return apiKeyRepository.save(entity);
    }

    @Transactional
    public ApiKeyEntity resetUsage(Long id) {
        ApiKeyEntity entity = apiKeyRepository.findById(id)
                .orElseThrow(() -> BizException.notFound("密钥不存在：" + id));
        entity.setUsedTokens(0L);
        entity.setUpdatedAt(Instant.now());
        return apiKeyRepository.save(entity);
    }
}
