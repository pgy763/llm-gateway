package com.pgy.gateway.api.dto;

import com.pgy.gateway.infrastructure.persistence.entity.TaskStateLogEntity;

import java.time.Instant;

/**
 * 一条状态变迁记录，用于展示任务的完整流转轨迹。
 */
public record StateHistoryView(
        Long id,
        String fromState,
        String toState,
        String event,
        String detail,
        Instant createdAt
) {

    public static StateHistoryView from(TaskStateLogEntity entity) {
        return new StateHistoryView(
                entity.getId(),
                entity.getFromState(),
                entity.getToState(),
                entity.getEvent(),
                entity.getDetail(),
                entity.getCreatedAt()
        );
    }
}
