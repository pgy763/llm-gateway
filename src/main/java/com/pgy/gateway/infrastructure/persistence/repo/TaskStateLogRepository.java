package com.pgy.gateway.infrastructure.persistence.repo;

import com.pgy.gateway.infrastructure.persistence.entity.TaskStateLogEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TaskStateLogRepository extends JpaRepository<TaskStateLogEntity, Long> {

    /** 按发生顺序取出某个任务的完整状态变迁历史 */
    List<TaskStateLogEntity> findByTaskIdOrderByIdAsc(String taskId);
}
