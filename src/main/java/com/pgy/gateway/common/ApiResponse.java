package com.pgy.gateway.common;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 统一的接口返回外壳。
 *
 * 所有接口都返回这个结构，前端和调用方只需要写一次解析逻辑：
 *   code = 0     成功，读 data
 *   code != 0    失败，读 message
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ApiResponse<T> {

    /** 0 表示成功，其他值表示失败 */
    private int code;

    /** 提示信息 */
    private String message;

    /** 真正的业务数据 */
    private T data;

    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(0, "ok", data);
    }

    public static <T> ApiResponse<T> fail(int code, String message) {
        return new ApiResponse<>(code, message, null);
    }
}
