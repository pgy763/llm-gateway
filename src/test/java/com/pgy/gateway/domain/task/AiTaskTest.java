package com.pgy.gateway.domain.task;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 领域对象 AiTask 的测试。
 *
 * 这里验证的不是"状态机能怎么走"（那是 TaskStateMachineTest 的事），
 * 而是"业务规则有没有被正确执行"，例如：
 *   - 重试次数用尽之后，任务是否会老老实实变成失败，而不是无限重试
 *   - 完成之后还能不能被改回去
 */
class AiTaskTest {

    @Test
    @DisplayName("新任务的初始状态是 CREATED，重试次数为 0")
    void initialState() {
        AiTask task = new AiTask("t-1", "写一篇科普文章", 2);

        assertThat(task.getState()).isEqualTo(TaskState.CREATED);
        assertThat(task.getRetryCount()).isZero();
        assertThat(task.isFinished()).isFalse();
    }

    @Test
    @DisplayName("重写次数用尽后自动判定失败，不会无限重试下去")
    void failsAfterRetryExhausted() {
        AiTask task = new AiTask("t-2", "写一篇科普文章", 2);

        task.fire(TaskEvent.START);
        assertThat(task.getState()).isEqualTo(TaskState.GENERATING);

        task.markGenerated("第一版内容");
        assertThat(task.getState()).isEqualTo(TaskState.REVIEWING);

        task.markReviewRejected("内容过短");
        assertThat(task.getState()).isEqualTo(TaskState.GENERATING);
        assertThat(task.getRetryCount()).isEqualTo(1);

        task.markGenerated("第二版内容");
        task.markReviewRejected("内容仍然过短");
        assertThat(task.getState()).isEqualTo(TaskState.GENERATING);
        assertThat(task.getRetryCount()).isEqualTo(2);
        assertThat(task.canRetry()).isFalse();

        task.markGenerated("第三版内容");
        task.markReviewRejected("内容还是过短");
        assertThat(task.getState()).isEqualTo(TaskState.FAILED);
        assertThat(task.isFinished()).isTrue();
        assertThat(task.getLastError()).contains("重写 2 次");
    }

    @Test
    @DisplayName("审查通过后进入定稿，最终可以完成")
    void reviewPassLeadsToCompletion() {
        AiTask task = new AiTask("t-3", "写一篇科普文章", 2);

        task.fire(TaskEvent.START);
        task.markGenerated("这是一段足够长的内容");

        task.markReviewPassed();
        assertThat(task.getState()).isEqualTo(TaskState.FINALIZING);

        task.markFinalized("最终定稿的内容");
        assertThat(task.getState()).isEqualTo(TaskState.COMPLETED);
        assertThat(task.getFinalContent()).isEqualTo("最终定稿的内容");
    }

    @Test
    @DisplayName("已完成的任务再想重写，会被状态机拦住")
    void completedTaskRejectsFurtherEvents() {
        AiTask task = new AiTask("t-4", "写一篇科普文章", 2);
        task.fire(TaskEvent.START);
        task.markGenerated("内容");
        task.markReviewPassed();
        task.markFinalized("定稿");

        assertThatThrownBy(() -> task.fire(TaskEvent.RETRY))
                .isInstanceOf(IllegalTaskTransitionException.class);
    }

    @Test
    @DisplayName("从数据库恢复出来的任务，状态与重试次数都要保持原样")
    void restoreFromPersistence() {
        AiTask restored = new AiTask("t-5", "目标内容", TaskState.GENERATING, 1, 2,
                "草稿内容", null, null, "上次的审查意见");

        assertThat(restored.getState()).isEqualTo(TaskState.GENERATING);
        assertThat(restored.getRetryCount()).isEqualTo(1);
        assertThat(restored.getDraftContent()).isEqualTo("草稿内容");
        assertThat(restored.canRetry()).isTrue();
    }

    @Test
    @DisplayName("取消操作会终止进行中的任务")
    void cancelStopsInProgressTask() {
        AiTask task = new AiTask("t-6", "目标内容", 2);
        task.fire(TaskEvent.START);

        task.cancel();

        assertThat(task.getState()).isEqualTo(TaskState.CANCELLED);
        assertThat(task.isFinished()).isTrue();
    }
}
