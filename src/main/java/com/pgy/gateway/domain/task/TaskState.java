package com.pgy.gateway.domain.task;

/**
 * AI 任务的状态集合。
 *
 * 这是一个"封闭集合"：任务在任何一时刻，必定处于且仅处于其中一个状态。
 * 用枚举（而不是随便定义字符串常量）的好处是，编译器能帮你挡住拼写错误，
 * 而且你能一眼看全所有可能的状态。
 */
public enum TaskState {

    /** 已创建，排队等待处理 */
    CREATED,

    /** 正在调用模型生成内容 */
    GENERATING,

    /** 内容已生成，正在审查是否合格 */
    REVIEWING,

    /** 审查未通过，准备重新生成 */
    RETRYING,

    /** 审查通过，正在收尾定稿 */
    FINALIZING,

    /** 已完成（终态，不会再变化） */
    COMPLETED,

    /** 已失败（终态） */
    FAILED,

    /** 已取消（终态） */
    CANCELLED;

    /**
     * 是否为终态。
     * 终态意味着任务已经走完了，不会再发生任何状态迁移。
     * 后台的补偿扫描任务靠这个方法判断"哪些任务还需要继续推进"。
     */
    public boolean isTerminal() {
        return this == COMPLETED || this == FAILED || this == CANCELLED;
    }

    /** 任务是否还在进行中（没走完，但也没失败） */
    public boolean isInProgress() {
        return !isTerminal();
    }
}
