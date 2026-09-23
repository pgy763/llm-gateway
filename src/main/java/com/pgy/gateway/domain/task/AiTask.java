package com.pgy.gateway.domain.task;

import lombok.Getter;

/**
 * AI 任务 —— 领域对象（领域驱动设计里叫"聚合根"）。
 *
 * 一个任务从创建到完成，中间会经过生成、审查、可能重写、最后定稿。
 * 这个对象负责守护"状态必须一直合法"这条底线：
 * 外部想改状态，唯一的入口是 fire() 和下面这几个 mark 方法，
 * 任何人都不能直接给 state 赋值。
 *
 * 注意这个类里没有一行数据库相关的代码 —— 它只描述业务规则。
 * 怎么存、存到哪，是基础设施层的事。
 */
@Getter
public class AiTask {

    /** 对外展示的任务编号 */
    private final String taskId;

    /** 任务目标，例如"写一篇 800 字的科普文章" */
    private final String goal;

    /** 允许的最大重写次数 */
    private final int maxRetry;

    /** 当前状态 —— 只允许通过 fire 系列方法修改 */
    private TaskState state;

    /** 已经重写了几次 */
    private int retryCount;

    /** 最近一版初稿 */
    private String draftContent;

    /** 定稿内容 */
    private String finalContent;

    /** 最近一次失败原因 */
    private String lastError;

    /** 最近一次审查意见 */
    private String lastReviewComment;

    /** 新建任务 */
    public AiTask(String taskId, String goal, int maxRetry) {
        this(taskId, goal, TaskState.CREATED, 0, maxRetry, null, null, null, null);
    }

    /** 从数据库恢复任务（重启续跑时用这个构造函数） */
    public AiTask(String taskId, String goal, TaskState state, int retryCount, int maxRetry,
                  String draftContent, String finalContent, String lastError, String lastReviewComment) {
        this.taskId = taskId;
        this.goal = goal;
        this.state = state;
        this.retryCount = retryCount;
        this.maxRetry = maxRetry;
        this.draftContent = draftContent;
        this.finalContent = finalContent;
        this.lastError = lastError;
        this.lastReviewComment = lastReviewComment;
    }

    // ------------------------------------------------------------------
    // 状态迁移：所有状态变化的唯一入口
    // ------------------------------------------------------------------

    /**
     * 触发一次状态迁移。
     * 不合法的迁移会抛 IllegalTaskTransitionException，绝不放行。
     */
    public TaskState fire(TaskEvent event) {
        this.state = TaskStateMachine.next(this.state, event);
        if (event == TaskEvent.RETRY) {
            this.retryCount++;
        }
        return this.state;
    }

    /** 初稿生成完毕 */
    public void markGenerated(String draft) {
        this.draftContent = draft;
        fire(TaskEvent.GENERATED);
    }

    /** 审查通过 */
    public void markReviewPassed() {
        fire(TaskEvent.REVIEW_PASS);
    }

    /**
     * 审查未通过。
     *
     * 这里封装了一条业务规则：还有重写机会就退回重写，否则直接判失败。
     * 把这条规则放在领域对象里，而不是散落在 Service 的 if-else 中，
     * 正是"领域模型"相对于"贫血模型"的价值所在。
     */
    public void markReviewRejected(String comment) {
        this.lastReviewComment = comment;
        if (canRetry()) {
            fire(TaskEvent.REVIEW_REJECT);   // REVIEWING -> RETRYING
            fire(TaskEvent.RETRY);           // RETRYING  -> GENERATING
        } else {
            this.lastError = "重写 " + maxRetry + " 次后仍未通过审查，最后一次审查意见：" + comment;
            fire(TaskEvent.FAIL);            // REVIEWING -> FAILED
        }
    }

    /** 定稿完成 */
    public void markFinalized(String content) {
        this.finalContent = content;
        fire(TaskEvent.FINALIZE);
    }

    /** 发生错误 */
    public void markFailed(String error) {
        this.lastError = error;
        fire(TaskEvent.FAIL);
    }

    /** 用户取消 */
    public void cancel() {
        fire(TaskEvent.CANCEL);
    }

    /** 是否还有重写机会 */
    public boolean canRetry() {
        return retryCount < maxRetry;
    }

    /** 任务是否已经走完（成功、失败或取消） */
    public boolean isFinished() {
        return state.isTerminal();
    }
}
