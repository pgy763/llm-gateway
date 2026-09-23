package com.pgy.gateway.domain.task;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Set;

/**
 * 任务状态机 —— 整个项目的核心。
 *
 * 【它解决什么问题？】
 * 如果不用状态机，代码里会到处散落这种判断：
 *     if ("处理中".equals(status)) { status = "已完成"; }
 * 时间一长，你会得到一堆前后矛盾的组合：一个任务既"已完成"又"正在重写"，
 * 或者从"已取消"里又跳回"生成中"。这类 bug 极难排查。
 *
 * 【状态机的做法】
 * 把所有合法的"从哪来 → 到哪去"集中写在一张表里（就是下面的 TRANSITIONS）。
 * 任何一次状态变化都要先过这张表，表上没有的迁移直接拒绝并报错。
 * 于是"不可能的状态"从源头上就不可能出现。
 *
 * 【这张表的含义】
 *   CREATED    --START---------->  GENERATING   开始干活
 *   GENERATING --GENERATED------>  REVIEWING    初稿写完，送去审查
 *   REVIEWING  --REVIEW_PASS---->  FINALIZING   审查通过，去定稿
 *   REVIEWING  --REVIEW_REJECT-->  RETRYING     审查没过，准备重写
 *   RETRYING   --RETRY---------->  GENERATING   重写（回到生成，形成循环）
 *   FINALIZING --FINALIZE------->  COMPLETED    完成
 *   任意进行中状态 --FAIL------->  FAILED       出错
 *   任意进行中状态 --CANCEL----->  CANCELLED    用户取消
 */
public final class TaskStateMachine {

    private TaskStateMachine() {
        // 工具类，不允许实例化
    }

    /** 迁移表：当前状态 + 收到的事件 → 下一个状态 */
    private static final Map<TaskState, Map<TaskEvent, TaskState>> TRANSITIONS = buildTransitions();

    private static Map<TaskState, Map<TaskEvent, TaskState>> buildTransitions() {
        Map<TaskState, Map<TaskEvent, TaskState>> table = new EnumMap<>(TaskState.class);

        table.put(TaskState.CREATED, Map.of(
                TaskEvent.START, TaskState.GENERATING,
                TaskEvent.CANCEL, TaskState.CANCELLED
        ));

        table.put(TaskState.GENERATING, Map.of(
                TaskEvent.GENERATED, TaskState.REVIEWING,
                TaskEvent.FAIL, TaskState.FAILED,
                TaskEvent.CANCEL, TaskState.CANCELLED
        ));

        table.put(TaskState.REVIEWING, Map.of(
                TaskEvent.REVIEW_PASS, TaskState.FINALIZING,
                TaskEvent.REVIEW_REJECT, TaskState.RETRYING,
                TaskEvent.FAIL, TaskState.FAILED
        ));

        table.put(TaskState.RETRYING, Map.of(
                TaskEvent.RETRY, TaskState.GENERATING,
                TaskEvent.FAIL, TaskState.FAILED,
                TaskEvent.CANCEL, TaskState.CANCELLED
        ));

        table.put(TaskState.FINALIZING, Map.of(
                TaskEvent.FINALIZE, TaskState.COMPLETED,
                TaskEvent.FAIL, TaskState.FAILED
        ));

        // COMPLETED / FAILED / CANCELLED 是终态，故意不给它们任何出边。
        // 这意味着：任务一旦完成，就无法被任何事件"唤醒"回去，杜绝了脏状态。

        return Collections.unmodifiableMap(table);
    }

    /**
     * 在当前状态下，能否响应某个事件。
     * 用于"先问再动"的场景，例如接口层可以先检查合法性再决定是否提交。
     */
    public static boolean canFire(TaskState from, TaskEvent event) {
        return edgesOf(from).containsKey(event);
    }

    /**
     * 计算下一个状态。
     * 如果这次迁移不合法，直接抛出异常 —— 宁可当场报错，也不要让脏状态写进数据库。
     */
    public static TaskState next(TaskState from, TaskEvent event) {
        TaskState to = edgesOf(from).get(event);
        if (to == null) {
            throw new IllegalTaskTransitionException(from, event);
        }
        return to;
    }

    /** 当前状态下所有允许的事件，便于排查"为什么我这个操作没生效" */
    public static Set<TaskEvent> availableEvents(TaskState from) {
        return Collections.unmodifiableSet(edgesOf(from).keySet());
    }

    private static Map<TaskEvent, TaskState> edgesOf(TaskState from) {
        return TRANSITIONS.getOrDefault(from, Map.of());
    }

    /** 状态的中文说明，用于日志与接口返回 */
    public static String describe(TaskState state) {
        return switch (state) {
            case CREATED -> "已创建";
            case GENERATING -> "生成中";
            case REVIEWING -> "审查中";
            case RETRYING -> "待重写";
            case FINALIZING -> "定稿中";
            case COMPLETED -> "已完成";
            case FAILED -> "已失败";
            case CANCELLED -> "已取消";
        };
    }
}
