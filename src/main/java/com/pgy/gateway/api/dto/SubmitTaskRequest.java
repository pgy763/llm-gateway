package com.pgy.gateway.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 提交任务的请求体。
 *
 * 例如：{"goal": "写一篇 800 字的文章，介绍限流算法"}
 */
public record SubmitTaskRequest(

        @NotBlank(message = "任务目标不能为空")
        @Size(max = 2000, message = "任务目标不能超过 2000 字")
        String goal
) {
}
