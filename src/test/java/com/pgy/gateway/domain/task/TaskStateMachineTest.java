package com.pgy.gateway.domain.task;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 状态机的测试。
 *
 * 【为什么这个测试最重要？】
 * 因为状态机是"防错"的最后一道闸门。它一旦有漏洞，
 * 脏状态就会写进数据库，之后各种诡异的线上问题都从这里来。
 * 所以这里要把"允许什么"和"不允许什么"都钉死。
 */
class TaskStateMachineTest {

    @Test
    @DisplayName("正常路径：创建 → 生成 → 审查 → 定稿 → 完成")
    void happyPath() {
        TaskState state = TaskState.CREATED;

        state = TaskStateMachine.next(state, TaskEvent.START);
        assertThat(state).isEqualTo(TaskState.GENERATING);

        state = TaskStateMachine.next(state, TaskEvent.GENERATED);
        assertThat(state).isEqualTo(TaskState.REVIEWING);

        state = TaskStateMachine.next(state, TaskEvent.REVIEW_PASS);
        assertThat(state).isEqualTo(TaskState.FINALIZING);

        state = TaskStateMachine.next(state, TaskEvent.FINALIZE);
        assertThat(state).isEqualTo(TaskState.COMPLETED);

        assertThat(state.isTerminal()).isTrue();
    }

    @Test
    @DisplayName("重写循环：审查不通过退回生成，审通过后再定稿")
    void retryThenPass() {
        TaskState state = TaskState.REVIEWING;

        state = TaskStateMachine.next(state, TaskEvent.REVIEW_REJECT);
        assertThat(state).isEqualTo(TaskState.RETRYING);

        state = TaskStateMachine.next(state, TaskEvent.RETRY);
        assertThat(state).isEqualTo(TaskState.GENERATING);

        state = TaskStateMachine.next(state, TaskEvent.GENERATED);
        assertThat(state).isEqualTo(TaskState.REVIEWING);

        state = TaskStateMachine.next(state, TaskEvent.REVIEW_PASS);
        assertThat(state).isEqualTo(TaskState.FINALIZING);

        state = TaskStateMachine.next(state, TaskEvent.FINALIZE);
        assertThat(state).isEqualTo(TaskState.COMPLETED);
    }

    @Test
    @DisplayName("终态不可逆：已完成／已失败／已取消的任务不会被任何事件唤醒")
    void terminalStatesAcceptNothing() {
        List<TaskState> terminals = List.of(
                TaskState.COMPLETED, TaskState.FAILED, TaskState.CANCELLED);

        for (TaskState terminal : terminals) {
            assertThat(TaskStateMachine.availableEvents(terminal))
                    .as("终态 %s 不应该存在任何出边", terminal)
                    .isEmpty();

            for (TaskEvent event : TaskEvent.values()) {
                assertThat(TaskStateMachine.canFire(terminal, event))
                        .as("终态 %s 不应响应事件 %s", terminal, event)
                        .isFalse();
            }
        }
    }

    @Test
    @DisplayName("不能跳过生成阶段，直接从未创建状态去定稿")
    void cannotSkipGeneration() {
        assertThatThrownBy(() -> TaskStateMachine.next(TaskState.CREATED, TaskEvent.FINALIZE))
                .isInstanceOf(IllegalTaskTransitionException.class)
                .hasMessageContaining("非法的状态迁移");
    }

    @Test
    @DisplayName("不能跳过审查阶段直接定稿")
    void cannotSkipReview() {
        assertThatThrownBy(() -> TaskStateMachine.next(TaskState.REVIEWING, TaskEvent.FINALIZE))
                .isInstanceOf(IllegalTaskTransitionException.class);
    }

    @Test
    @DisplayName("生成阶段不能直接收到审查结果")
    void cannotReceiveReviewResultWhileGenerating() {
        assertThatThrownBy(() -> TaskStateMachine.next(TaskState.GENERATING, TaskEvent.REVIEW_PASS))
                .isInstanceOf(IllegalTaskTransitionException.class);
    }

    @Test
    @DisplayName("异常信息里要带上当前允许的事件，方便排查")
    void exceptionMessageIsHelpful() {
        assertThatThrownBy(() -> TaskStateMachine.next(TaskState.CREATED, TaskEvent.FINALIZE))
                .isInstanceOf(IllegalTaskTransitionException.class)
                .hasMessageContaining("START")
                .hasMessageContaining("CANCEL");
    }
}
