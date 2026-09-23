package com.pgy.gateway.api.dto;

import com.pgy.gateway.domain.task.TaskStateMachine;
import com.pgy.gateway.infrastructure.persistence.entity.AiTaskEntity;

import java.time.Instant;

/**
 * 任务对外展示的视图对象。
 *
 * 【为什么不让接口直接返回数据库实体？】
 * 因为实体里可能有不该暴露给外部的字段（比如 api_key_id、version 版本号）。
 * 专门定一个视图对象，想返回什么就放什么，改动起来也不会误伤数据库结构。
 */
public record TaskView(
        String taskId,
        String goal,
        String state,
        String stateLabel,
        boolean finished,
        int retryCount,
        int maxRetry,
        String draftContent,
        String finalContent,
        String lastError,
        String lastReviewComment,
        Instant createdAt,
        Instant updatedAt
) {

    public static TaskView from(AiTaskEntity entity) {
        return new TaskView(
                entity.getTaskId(),
                entity.getGoal(),
                entity.getState().name(),
                TaskStateMachine.describe(entity.getState()),
                entity.getState().isTerminal(),
                entity.getRetryCount(),
                entity.getMaxRetry(),
                entity.getDraftContent(),
                entity.getFinalContent(),
                entity.getLastError(),
                entity.getLastReviewComment(),
                entity.getCreatedAt(),
                entity.getUpdatedAt()
        );
    }
}
