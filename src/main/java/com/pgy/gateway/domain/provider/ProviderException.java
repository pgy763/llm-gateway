package com.pgy.gateway.domain.provider;

/**
 * 调用上游模型失败时抛出。
 *
 * 降级链会捕获这个异常，并据此决定是否切换到下一家提供方：
 *  - retryable = true  ：属于"换一家可能就好了"的故障（超时、5xx、限流），继续降级
 *  - retryable = false ：属于"换一家也没用"的问题（自己的请求参数写错了），直接失败
 *
 * 这个区分很重要。如果参数写错还一路重试下去，只会白白烧掉三家的额度。
 */
public class ProviderException extends RuntimeException {

    private final String providerName;

    private final boolean retryable;

    public ProviderException(String providerName, String message) {
        this(providerName, message, true, null);
    }

    public ProviderException(String providerName, String message, Throwable cause) {
        this(providerName, message, true, cause);
    }

    public ProviderException(String providerName, String message, boolean retryable, Throwable cause) {
        super("[" + providerName + "] " + message, cause);
        this.providerName = providerName;
        this.retryable = retryable;
    }

    public String getProviderName() {
        return providerName;
    }

    public boolean isRetryable() {
        return retryable;
    }
}
