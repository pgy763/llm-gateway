package com.pgy.gateway.domain.task;

/**
 * 驱动任务状态变化的"事件"。
 *
 * 状态不会自己变化，必须由某个事件触发。
 * 把"事件"独立成枚举，好处是：任何一次状态变化都能说清楚"是因为发生了什么"。
 */
public enum TaskEvent {

    /** 开始处理任务 */
    START,

    /** 内容生成完成 */
    GENERATED,

    /** 审查通过 */
    REVIEW_PASS,

    /** 审查不通过，需要重写 */
    REVIEW_REJECT,

    /** 开始第 N 次重写 */
    RETRY,

    /** 定稿完成 */
    FINALIZE,

    /** 发生无法恢复的错误 */
    FAIL,

    /** 用户主动取消 */
    CANCEL
}
