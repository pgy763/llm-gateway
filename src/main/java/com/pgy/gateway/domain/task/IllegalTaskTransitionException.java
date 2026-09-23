package com.pgy.gateway.domain.task;

/**
 * 试图执行一次不合法的状态迁移时抛出。
 *
 * 例如：一个已经 COMPLETED 的任务，又收到了 RETRY 事件。
 * 这种情况说明调用方的逻辑有问题，必须立刻暴露出来，而不是默默改库。
 */
public class IllegalTaskTransitionException extends RuntimeException {

    private final TaskState from;

    private final TaskEvent event;

    public IllegalTaskTransitionException(TaskState from, TaskEvent event) {
        super(String.format(
                "非法的状态迁移：任务当前是 [%s]，无法响应事件 [%s]。当前允许的事件有：%s",
                TaskStateMachine.describe(from), event, TaskStateMachine.availableEvents(from)));
        this.from = from;
        this.event = event;
    }

    public TaskState getFrom() {
        return from;
    }

    public TaskEvent getEvent() {
        return event;
    }
}
