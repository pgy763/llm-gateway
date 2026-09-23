package com.pgy.gateway.infrastructure.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * 调用日志：每一次对上游模型的调用都留下一行。
 *
 * 这张表有三个用途：
 *   1. 计费 —— 每个密钥用了多少 token，从这张表汇总
 *   2. 排查 —— 用户说"刚才那次回答不对"，用 requestId 一查就知道当时怎么处理的
 *   3. 观测 —— 成功率、平均耗时、降级比例，都从这里统计
 */
@Entity
@Table(name = "usage_log")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UsageLogEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 请求编号，串联一次请求涉及的所有日志 */
    @Column(name = "request_id", nullable = false)
    private String requestId;

    @Column(name = "api_key_id")
    private Long apiKeyId;

    /** 最终实际服务的提供方 */
    @Column(name = "provider_name")
    private String providerName;

    /** 最终实际使用的模型名 */
    @Column(name = "model")
    private String model;

    @Column(name = "prompt_tokens", nullable = false)
    private int promptTokens;

    @Column(name = "completion_tokens", nullable = false)
    private int completionTokens;

    @Column(name = "total_tokens", nullable = false)
    private int totalTokens;

    @Column(name = "latency_ms", nullable = false)
    private long latencyMs;

    @Column(name = "success", nullable = false)
    private boolean success;

    /** 是否走了降级（换过提供方） */
    @Column(name = "fallback_used", nullable = false)
    private boolean fallbackUsed;

    @Column(name = "error_message")
    private String errorMessage;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
}
