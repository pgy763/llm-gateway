package com.pgy.gateway.infrastructure.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * 虚拟密钥。
 *
 * 【什么是"虚拟密钥"？】
 * 业务方拿到的是网关签发的 key（比如 sk-demo-0001），而不是 DeepSeek 的真密钥。
 * 好处有三个：
 *   1. 真密钥只存在于网关，不会被复制到五个业务系统里
 *   2. 网关知道"是谁在调用"，才能做限流和计费
 *   3. 某个业务方出问题，单独禁用它的 key 就行，不影响别人
 */
@Entity
@Table(name = "api_key")
@Getter
@Setter
public class ApiKeyEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "key_value", nullable = false, unique = true)
    private String keyValue;

    @Column(name = "display_name", nullable = false)
    private String displayName;

    @Column(name = "enabled", nullable = false)
    private boolean enabled = true;

    /** 总配额（token 数） */
    @Column(name = "quota_tokens", nullable = false)
    private long quotaTokens;

    /** 已消耗（token 数） */
    @Column(name = "used_tokens", nullable = false)
    private long usedTokens;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    // ------------------------------------------------------------------
    // 领域行为
    // ------------------------------------------------------------------

    /** 剩余可用配额 */
    public long remainingTokens() {
        return Math.max(0L, quotaTokens - usedTokens);
    }

    /** 是否还能负担得起这次消耗 */
    public boolean hasQuotaFor(long tokens) {
        return usedTokens + tokens <= quotaTokens;
    }

    /** 记账：消耗掉一些额度 */
    public void consume(long tokens) {
        if (tokens <= 0) {
            return;
        }
        this.usedTokens += tokens;
        this.updatedAt = Instant.now();
    }

    /** 使用率，0~1 之间，管理接口用来展示"这个密钥快用完了" */
    public double usageRatio() {
        if (quotaTokens <= 0) {
            return 1.0;
        }
        return Math.min(1.0, (double) usedTokens / quotaTokens);
    }
}
