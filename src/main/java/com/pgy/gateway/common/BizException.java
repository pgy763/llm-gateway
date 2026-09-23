package com.pgy.gateway.common;

/**
 * 业务异常。
 *
 * 携带一个业务码，会被 GlobalExceptionHandler 翻译成对应的 HTTP 状态码。
 * 这样抛异常的地方只关心"出了什么事"，不用操心"该回什么 HTTP 码"。
 */
public class BizException extends RuntimeException {

    private final int code;

    public BizException(int code, String message) {
        super(message);
        this.code = code;
    }

    public BizException(String message) {
        this(400, message);
    }

    public int getCode() {
        return code;
    }

    /** 触发限流 */
    public static BizException rateLimited(String message) {
        return new BizException(429, message);
    }

    /** 配额不足 */
    public static BizException quotaExceeded(String message) {
        return new BizException(402, message);
    }

    /** 密钥无效 */
    public static BizException unauthorized(String message) {
        return new BizException(401, message);
    }

    /** 资源不存在 */
    public static BizException notFound(String message) {
        return new BizException(404, message);
    }
}
