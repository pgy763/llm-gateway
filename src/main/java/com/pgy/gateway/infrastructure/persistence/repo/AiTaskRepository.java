package com.pgy.gateway.infrastructure.persistence.repo;

import com.pgy.gateway.domain.task.TaskState;
import com.pgy.gateway.infrastructure.persistence.entity.AiTaskEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface AiTaskRepository extends JpaRepository<AiTaskEntity, Long> {

    Optional<AiTaskEntity> findByTaskId(String taskId);

    /**
     * 找出所有"还在进行中"的任务。
     * 后台的补偿扫描会定时调用它，把因为重启等原因中断的任务重新推进。
     */
    List<AiTaskEntity> findTop50ByStateInOrderByCreatedAtAsc(Collection<TaskState> states);

    List<AiTaskEntity> findTop50ByOrderByCreatedAtDesc();
}
