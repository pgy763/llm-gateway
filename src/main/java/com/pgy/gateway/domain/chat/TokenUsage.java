package com.pgy.gateway.domain.chat;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 一次调用的用量统计。
 *
 * 大模型按 token 计费（输入和输出分别计价），所以"用了多少 token"
 * 是网关必须替业务方记清楚的一笔账。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class TokenUsage {

    /** 输入消耗的 token */
    private int promptTokens;

    /** 输出消耗的 token */
    private int completionTokens;

    /** 合计 */
    private int totalTokens;

    public static TokenUsage of(int promptTokens, int completionTokens) {
        return new TokenUsage(promptTokens, completionTokens, promptTokens + completionTokens);
    }

    public static TokenUsage empty() {
        return new TokenUsage(0, 0, 0);
    }
}
