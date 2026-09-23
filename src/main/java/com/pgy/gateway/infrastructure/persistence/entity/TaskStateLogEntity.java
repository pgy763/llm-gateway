package com.pgy.gateway.infrastructure.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * 状态流转日志 —— 任务的"行车记录仪"。
 *
 * 每次状态变化追加一行，永不修改。
 * 用户说"我这个任务怎么卡住了"，把这行记录拉出来一看：
 *     CREATED → GENERATING   开始处理
 *     GENERATING → REVIEWING 初稿完成
 *     REVIEWING → RETRYING   审查未通过
 *     RETRYING → GENERATING  重新生成
 *     ...
 * 一眼就知道它卡在哪一步、卡了几轮。
 *
 * 【为什么不全靠 ai_task 表的当前状态？】
 * 因为那只是"现在在哪"，丢失了"怎么走过来的"。
 * 排查问题时，过程往往比结果更重要。
 */
@Entity
@Table(name = "task_state_log")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TaskStateLogEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "task_id", nullable = false)
    private String taskId;

    @Column(name = "from_state", length = 32)
    private String fromState;

    @Column(name = "to_state", nullable = false, length = 32)
    private String toState;

    @Column(name = "event", nullable = false, length = 32)
    private String event;

    /** 补充说明，例如审查意见 */
    @Column(name = "detail", columnDefinition = "text")
    private String detail;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
}
