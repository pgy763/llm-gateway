package com.pgy.gateway.api;

import com.pgy.gateway.api.dto.StateHistoryView;
import com.pgy.gateway.api.dto.SubmitTaskRequest;
import com.pgy.gateway.api.dto.TaskView;
import com.pgy.gateway.application.TaskService;
import com.pgy.gateway.common.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 多步任务接口。
 *
 * 和 /v1/chat 的区别：
 *   /v1/chat  一问一答，秒级返回，没有中间状态
 *   /v1/tasks 提交一个"活"，后台慢慢做，有明确的状态流转，随时可查进度
 *
 * 后者才是"状态机"发挥价值的地方 —— 因为一个任务可能失败、重试、
 * 中途被重启中断，这些都需要一套严谨的状态管理才不出乱子。
 */
@RestController
@RequestMapping("/v1/tasks")
public class TaskController {

    private final TaskService taskService;

    public TaskController(TaskService taskService) {
        this.taskService = taskService;
    }

    /**
     * 提交任务。立刻返回任务号，不等结果。
     *
     * 示例：
     *   curl -X POST http://localhost:8080/v1/tasks \
     *        -H "X-Api-Key: sk-demo-0001" \
     *        -H "Content-Type: application/json" \
     *        -d '{"goal":"写一篇 800 字的文章，介绍令牌桶限流算法"}'
     */
    @PostMapping
    public ApiResponse<TaskView> submit(
            @RequestHeader(value = ChatController.API_KEY_HEADER, required = false) String apiKey,
            @Valid @RequestBody SubmitTaskRequest request) {
        return ApiResponse.ok(TaskView.from(taskService.submit(apiKey, request.goal())));
    }

    /** 查询单个任务的最新状态 */
    @GetMapping("/{taskId}")
    public ApiResponse<TaskView> detail(@PathVariable String taskId) {
        return ApiResponse.ok(TaskView.from(taskService.getEntity(taskId)));
    }

    /** 最近提交的任务列表 */
    @GetMapping
    public ApiResponse<List<TaskView>> list() {
        return ApiResponse.ok(
                taskService.listRecent().stream().map(TaskView::from).toList());
    }

    /**
     * 查看任务的状态流转轨迹。
     * 排查"任务为什么卡住了"时，这个接口最有用。
     */
    @GetMapping("/{taskId}/history")
    public ApiResponse<List<StateHistoryView>> history(@PathVariable String taskId) {
        return ApiResponse.ok(
                taskService.history(taskId).stream().map(StateHistoryView::from).toList());
    }

    /** 取消一个还没结束的任务 */
    @PostMapping("/{taskId}/cancel")
    public ApiResponse<TaskView> cancel(@PathVariable String taskId) {
        taskService.cancel(taskId);
        return ApiResponse.ok(TaskView.from(taskService.getEntity(taskId)));
    }
}
