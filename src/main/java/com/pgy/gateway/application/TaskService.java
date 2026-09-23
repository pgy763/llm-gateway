package com.pgy.gateway.application;

import com.pgy.gateway.common.BizException;
import com.pgy.gateway.config.GatewayProperties;
import com.pgy.gateway.domain.chat.ChatMessage;
import com.pgy.gateway.domain.chat.ChatRequest;
import com.pgy.gateway.domain.chat.ChatResponse;
import com.pgy.gateway.domain.provider.ChatProvider;
import com.pgy.gateway.domain.provider.ProviderException;
import com.pgy.gateway.domain.provider.ProviderRegistry;
import com.pgy.gateway.domain.route.ProviderHealthTracker;
import com.pgy.gateway.domain.route.RoutePlanner;
import com.pgy.gateway.domain.task.AiTask;
import com.pgy.gateway.domain.task.TaskEvent;
import com.pgy.gateway.domain.task.TaskState;
import com.pgy.gateway.domain.task.TaskStateMachine;
import com.pgy.gateway.infrastructure.persistence.entity.AiTaskEntity;
import com.pgy.gateway.infrastructure.persistence.entity.ApiKeyEntity;
import com.pgy.gateway.infrastructure.persistence.entity.TaskStateLogEntity;
import com.pgy.gateway.infrastructure.persistence.repo.AiTaskRepository;
import com.pgy.gateway.infrastructure.persistence.repo.TaskStateLogRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;

/**
 * 任务编排服务 —— 状态机的"驾驶员"。
 *
 * 状态机（TaskStateMachine）定义了"允许怎么走"，这个类负责"实际去走"：
 * 每一步该调模型就调模型，该审查就审查，然后把新状态写回数据库。
 *
 * 整个流程是：
 *   ① 提交任务 → 状态 CREATED，立刻返回任务号
 *   ② 后台线程开始推进 → GENERATING（调模型写初稿）
 *   ③ 初稿出来 → REVIEWING（按规则审查）
 *   ④ 审查不通过 → 还有次数就 RETRYING → 回到 ②；没次数了就 FAILED
 *   ⑤ 审查通过 → FINALIZING → COMPLETED
 *
 * 每一步的状态都会立刻落库，所以就算服务器在第 ③ 步之后重启，
 * 重启后也能从数据库读回"它停在 REVIEWING"，接着往下跑。
 */
@Slf4j
@Service
public class TaskService {

    /** 审查时要求的最小字数 */
    private static final int MIN_CONTENT_LENGTH = 60;

    /** 补偿扫描时，只处理"超过这个秒数没更新"的任务，避免打断正在执行的 */
    private static final long STUCK_THRESHOLD_SECONDS = 30;

    private final AiTaskRepository taskRepository;

    private final TaskStateLogRepository stateLogRepository;

    private final ApiKeyService apiKeyService;

    private final ProviderRegistry providerRegistry;

    private final RoutePlanner routePlanner;

    private final ProviderHealthTracker healthTracker;

    private final GatewayProperties properties;

    private final TaskExecutor gatewayTaskExecutor;

    public TaskService(AiTaskRepository taskRepository,
                       TaskStateLogRepository stateLogRepository,
                       ApiKeyService apiKeyService,
                       ProviderRegistry providerRegistry,
                       RoutePlanner routePlanner,
                       ProviderHealthTracker healthTracker,
                       GatewayProperties properties,
                       TaskExecutor gatewayTaskExecutor) {
        this.taskRepository = taskRepository;
        this.stateLogRepository = stateLogRepository;
        this.apiKeyService = apiKeyService;
        this.providerRegistry = providerRegistry;
        this.routePlanner = routePlanner;
        this.healthTracker = healthTracker;
        this.properties = properties;
        this.gatewayTaskExecutor = gatewayTaskExecutor;
    }

    // ==================================================================
    // 对外接口
    // ==================================================================

    /** 提交任务：创建记录后立刻返回，实际处理交给后台线程 */
    public AiTaskEntity submit(String apiKey, String goal) {
        ApiKeyEntity keyEntity = apiKeyService.authenticate(apiKey);

        String taskId = "task-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        AiTask task = new AiTask(taskId, goal, properties.getTask().getMaxRetry());

        AiTaskEntity entity = AiTaskEntity.fromDomain(task, keyEntity.getId());
        taskRepository.save(entity);
        writeStateLog(taskId, null, TaskState.CREATED, null, "任务已创建：" + goal);

        log.info("任务 {} 已创建，开始后台处理", taskId);
        gatewayTaskExecutor.execute(() -> advance(taskId));

        return entity;
    }

    public AiTaskEntity getEntity(String taskId) {
        return loadEntity(taskId);
    }

    public AiTask get(String taskId) {
        return loadEntity(taskId).toDomain();
    }

    public List<AiTaskEntity> listRecent() {
        return taskRepository.findTop50ByOrderByCreatedAtDesc();
    }

    /** 任务的完整状态变迁历史 */
    public List<TaskStateLogEntity> history(String taskId) {
        return stateLogRepository.findByTaskIdOrderByIdAsc(taskId);
    }

    /** 取消任务（只能取消还没走完的） */
    public AiTask cancel(String taskId) {
        AiTaskEntity entity = loadEntity(taskId);
        AiTask task = entity.toDomain();

        if (task.isFinished()) {
            throw BizException.notFound("任务已结束，无法取消。当前状态：" + TaskStateMachine.describe(task.getState()));
        }

        TaskState before = task.getState();
        task.cancel();
        entity.applyDomain(task);
        taskRepository.save(entity);
        writeStateLog(taskId, before, task.getState(), TaskEvent.CANCEL, "用户取消");

        return task;
    }

    // ==================================================================
    // 状态机推进：核心逻辑
    // ==================================================================

    /**
     * 把任务从当前状态往前推进一步（必要时会连续推进多步）。
     * 这个方法可能被三种情况触发：
     *   1. 刚提交任务时
     *   2. 上一步执行完后自己递归调用
     *   3. 补偿扫描发现任务卡住了
     */
    public void advance(String taskId) {
        AiTaskEntity entity;
        try {
            entity = loadEntity(taskId);
        } catch (BizException e) {
            log.warn("推进任务失败，任务不存在：{}", taskId);
            return;
        }

        AiTask task = entity.toDomain();
        if (task.isFinished()) {
            log.debug("任务 {} 已是终态 {}，无需推进", taskId, task.getState());
            return;
        }

        try {
            switch (task.getState()) {
                case CREATED -> startTask(task, entity);
                case GENERATING -> generate(task, entity);
                case REVIEWING -> review(task, entity);
                case RETRYING -> retry(task, entity);
                case FINALIZING -> finalizeTask(task, entity);
                default -> log.warn("任务 {} 处于不可推进的状态：{}", taskId, task.getState());
            }
        } catch (Exception e) {
            log.error("推进任务 {} 时发生异常", taskId, e);
            failSafely(taskId, e.getMessage());
        }
    }

    /** CREATED → GENERATING */
    private void startTask(AiTask task, AiTaskEntity entity) {
        fireAndPersist(task, entity, TaskEvent.START, "开始处理");
        generate(task, entity);
    }

    /** GENERATING：调用模型生成初稿 */
    private void generate(AiTask task, AiTaskEntity entity) {
        String draft = generateText(task.getGoal());

        task.markGenerated(draft);
        persist(task, entity, TaskEvent.GENERATED, "初稿生成完成，共 " + draft.length() + " 字");

        if (properties.getTask().isReviewEnabled()) {
            review(task, entity);
        } else {
            fireAndPersist(task, entity, TaskEvent.REVIEW_PASS, "已关闭审查，直接通过");
            finalizeTask(task, entity);
        }
    }

    /** REVIEWING：审查初稿 */
    private void review(AiTask task, AiTaskEntity entity) {
        ReviewResult result = reviewContent(task.getDraftContent());

        if (result.passed()) {
            fireAndPersist(task, entity, TaskEvent.REVIEW_PASS, "审查通过：" + result.comment());
            finalizeTask(task, entity);
            return;
        }

        // 不通过。markReviewRejected 内部会判断还有没有重写机会：
        //   有 → 退回 RETRYING 再自动进入 GENERATING
        //   没有 → 直接判 FAILED
        task.markReviewRejected(result.comment());
        persist(task, entity, null, "审查未通过：" + result.comment());

        if (task.getState() == TaskState.GENERATING) {
            log.info("任务 {} 第 {} 次重写", task.getTaskId(), task.getRetryCount());
            generate(task, entity);
        } else {
            log.warn("任务 {} 重写次数用尽，判定失败", task.getTaskId());
        }
    }

    /** RETRYING → GENERATING */
    private void retry(AiTask task, AiTaskEntity entity) {
        fireAndPersist(task, entity, TaskEvent.RETRY,
                "开始第 " + (task.getRetryCount() + 1) + " 次重写");
        generate(task, entity);
    }

    /** FINALIZING → COMPLETED */
    private void finalizeTask(AiTask task, AiTaskEntity entity) {
        task.markFinalized(task.getDraftContent());
        persist(task, entity, TaskEvent.FINALIZE, "定稿完成");
        log.info("任务 {} 已完成", task.getTaskId());
    }

    // ==================================================================
    // 内容生成与审查
    // ==================================================================

    /**
     * 调用模型生成文本。
     * 这里同样走完整的降级链：主模型失败就换备用，再失败就用 mock 兜底。
     */
    private String generateText(String goal) {
        ChatRequest request = new ChatRequest();
        request.setMessages(List.of(
                ChatMessage.system("你是一位专业的内容撰写助手。请围绕用户给出的目标直接输出成文内容，"
                        + "也不要加任何引导语。"),
                ChatMessage.user(goal)
        ));
        request.setStream(Boolean.FALSE);

        List<ChatProvider> chain = routePlanner.plan(null);
        List<String> errors = new ArrayList<>();

        for (ChatProvider provider : chain) {
            String model = providerRegistry.pickModel(provider.name(), null);
            try {
                ChatResponse response = provider.chat(request, model);
                healthTracker.recordSuccess(provider.name());
                return response.getContent();
            } catch (ProviderException e) {
                healthTracker.recordFailure(provider.name());
                errors.add(e.getMessage());
                log.warn("任务生成阶段，提供方 [{}] 失败：{}", provider.name(), e.getMessage());
                if (!e.isRetryable()) {
                    break;
                }
            }
        }

        throw new BizException(503, "内容生成失败：" + String.join(" ｜ ", errors));
    }

    /**
     * 审查生成的内容。
     *
     * 这里用规则做审查（够用且零成本）。真实项目里通常是"规则 + 另一个模型打分"的组合，
     * 也就是常说的 Critic 模式。扩展点已经留好了：只要 reviewContent 返回不同的结论，
     * 整条状态机链路就会自动走"打回重写"的分支。
     */
    private ReviewResult reviewContent(String content) {
        if (content == null || content.isBlank()) {
            return ReviewResult.reject("内容为空");
        }
        if (content.length() < MIN_CONTENT_LENGTH) {
            return ReviewResult.reject(
                    "内容过短，" + content.length() + " 字，未达到 " + MIN_CONTENT_LENGTH + " 字的下限");
        }
        if (content.contains("【模拟回答")) {
            // 用的是内置的模拟提供方，内容本身就是假的，深度审查没有意义
            return ReviewResult.pass("当前使用模拟提供方，跳过深度审查");
        }
        if (content.contains("我不知道") || content.contains("无法回答")) {
            return ReviewResult.reject("模型表示无法回答该目标");
        }
        return ReviewResult.pass("长度 " + content.length() + " 字，检查通过");
    }

    /** 审查结论 */
    private record ReviewResult(boolean passed, String comment) {
        static ReviewResult pass(String comment) {
            return new ReviewResult(true, comment);
        }

        static ReviewResult reject(String comment) {
            return new ReviewResult(false, comment);
        }
    }

    // ==================================================================
    // 断点续跑：补偿扫描
    // ==================================================================

    /**
     * 定时扫描"卡住"的任务，把它们重新推进。
     *
     * 【它解决什么问题？】
     * 假设任务刚进入 GENERATING，服务器就被重启了。此时数据库里记着 GENERATING，
     * 但没有任何线程在跑它 —— 这个任务就永远卡住了。
     *
     * 这个定时任务每隔几秒扫一次，把超过 30 秒没动静的任务重新捡起来执行。
     * 这就是"断点续跑"：状态存在数据库里，谁来跑都行，重启不丢。
     */
    @Scheduled(fixedDelayString = "${gateway.task.poll-interval-ms:2000}")
    public void compensateStuckTasks() {
        List<AiTaskEntity> inProgress = taskRepository.findTop50ByStateInOrderByCreatedAtAsc(
                EnumSet.of(TaskState.CREATED, TaskState.GENERATING,
                        TaskState.REVIEWING, TaskState.RETRYING, TaskState.FINALIZING));

        if (inProgress.isEmpty()) {
            return;
        }

        Instant threshold = Instant.now().minusSeconds(STUCK_THRESHOLD_SECONDS);
        for (AiTaskEntity entity : inProgress) {
            if (entity.getUpdatedAt() != null && entity.getUpdatedAt().isBefore(threshold)) {
                log.info("补偿扫描：重新推进停滞的任务 {}（状态 {}）",
                        entity.getTaskId(), entity.getState());
                gatewayTaskExecutor.execute(() -> advance(entity.getTaskId()));
            }
        }
    }

    // ==================================================================
    // 内部工具
    // ==================================================================

    private AiTaskEntity loadEntity(String taskId) {
        return taskRepository.findByTaskId(taskId)
                .orElseThrow(() -> BizException.notFound("任务不存在：" + taskId));
    }

    /** 触发状态迁移并落库、写流转日志 */
    private void fireAndPersist(AiTask task, AiTaskEntity entity,
                                TaskEvent event, String detail) {
        TaskState before = task.getState();
        task.fire(event);
        persistInternal(task, entity, before, event, detail);
    }

    /** 状态未变化时的落库（例如记录了初稿内容） */
    private void persist(AiTask task, AiTaskEntity entity, TaskEvent event, String detail) {
        TaskState before = entity.getState();
        persistInternal(task, entity, before, event, detail);
    }

    private void persistInternal(AiTask task, AiTaskEntity entity, TaskState before,
                                 TaskEvent event, String detail) {
        entity.applyDomain(task);
        taskRepository.save(entity);

        if (before != task.getState()) {
            writeStateLog(task.getTaskId(), before, task.getState(), event, detail);
        }
    }

    private void writeStateLog(String taskId, TaskState from, TaskState to,
                               TaskEvent event, String detail) {
        try {
            stateLogRepository.save(TaskStateLogEntity.builder()
                    .taskId(taskId)
                    .fromState(from == null ? null : from.name())
                    .toState(to.name())
                    .event(event == null ? "STATE_REFRESH" : event.name())
                    .detail(detail)
                    .createdAt(Instant.now())
                    .build());
        } catch (Exception e) {
            log.error("写入状态流转日志失败，taskId={}", taskId, e);
        }
    }

    /** 兜底：把任务标记为失败，但不要再抛出异常（避免异常处理里又抛异常） */
    private void failSafely(String taskId, String message) {
        try {
            AiTaskEntity entity = loadEntity(taskId);
            AiTask task = entity.toDomain();
            if (task.isFinished()) {
                return;
            }
            TaskState before = task.getState();
            task.markFailed(message == null ? "未知错误" : message);
            entity.applyDomain(task);
            taskRepository.save(entity);
            writeStateLog(taskId, before, task.getState(), TaskEvent.FAIL, message);
        } catch (Exception e) {
            log.error("标记任务 {} 失败时又出错了", taskId, e);
        }
    }
}
