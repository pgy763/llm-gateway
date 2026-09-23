package com.pgy.gateway.infrastructure.persistence.entity;

import com.pgy.gateway.domain.task.AiTask;
import com.pgy.gateway.domain.task.TaskState;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * AI 任务的数据库表示。
 *
 * 【为什么领域对象和数据库实体要分开？】
 * AiTask 那一边是业务规则的表达，它不该知道"字段叫什么名字""有没有索引"。
 * 这一边只关心怎么存。分开之后，改表结构不会牵动业务逻辑，改业务逻辑也不会影响表结构。
 * 两边的转换集中放在下面的 fromDomain / applyDomain / toDomain 三个方法里。
 */
@Entity
@Table(name = "ai_task")
@Getter
@Setter
public class AiTaskEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "task_id", nullable = false, unique = true)
    private String taskId;

    @Column(name = "api_key_id")
    private Long apiKeyId;

    @Column(name = "goal", nullable = false, columnDefinition = "text")
    private String goal;

    /**
     * 当前状态。
     * 用 EnumType.STRING 存字符串而不是数字序号 ——
     * 如果存数字，以后往枚举中间插入一个新状态，所有历史数据都会错位。
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "state", nullable = false, length = 32)
    private TaskState state;

    @Column(name = "retry_count", nullable = false)
    private int retryCount;

    @Column(name = "max_retry", nullable = false)
    private int maxRetry;

    @Column(name = "draft_content", columnDefinition = "text")
    private String draftContent;

    @Column(name = "final_content", columnDefinition = "text")
    private String finalContent;

    @Column(name = "last_error", columnDefinition = "text")
    private String lastError;

    @Column(name = "last_review_comment", columnDefinition = "text")
    private String lastReviewComment;

    /**
     * 乐观锁版本号。
     *
     * 两个线程同时想改同一个任务时，谁先提交谁成功，
     * 后提交的那个会被数据库拒绝（抛乐观锁异常），而不是让两次修改互相覆盖。
     * 这也正是"用户连点两下，不会生成两份内容"的技术保障。
     */
    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    // ------------------------------------------------------------------
    // 领域对象 <-> 数据库实体 的转换
    // ------------------------------------------------------------------

    /** 新建：把领域对象落成一行记录 */
    public static AiTaskEntity fromDomain(AiTask task, Long apiKeyId) {
        Instant now = Instant.now();
        AiTaskEntity entity = new AiTaskEntity();
        entity.taskId = task.getTaskId();
        entity.apiKeyId = apiKeyId;
        entity.goal = task.getGoal();
        entity.state = task.getState();
        entity.retryCount = task.getRetryCount();
        entity.maxRetry = task.getMaxRetry();
        entity.draftContent = task.getDraftContent();
        entity.finalContent = task.getFinalContent();
        entity.lastError = task.getLastError();
        entity.lastReviewComment = task.getLastReviewComment();
        entity.createdAt = now;
        entity.updatedAt = now;
        return entity;
    }

    /** 更新：把领域对象的最新状态刷回本行 */
    public void applyDomain(AiTask task) {
        this.state = task.getState();
        this.retryCount = task.getRetryCount();
        this.draftContent = task.getDraftContent();
        this.finalContent = task.getFinalContent();
        this.lastError = task.getLastError();
        this.lastReviewComment = task.getLastReviewComment();
        this.updatedAt = Instant.now();
    }

    /** 读取：从数据库记录还原出领域对象 */
    public AiTask toDomain() {
        return new AiTask(taskId, goal, state, retryCount, maxRetry,
                draftContent, finalContent, lastError, lastReviewComment);
    }
}
