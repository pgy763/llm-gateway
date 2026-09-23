package com.pgy.gateway.api;

import com.pgy.gateway.api.dto.ApiKeyView;
import com.pgy.gateway.api.dto.UsageLogView;
import com.pgy.gateway.application.ApiKeyService;
import com.pgy.gateway.application.StatsService;
import com.pgy.gateway.common.ApiResponse;
import com.pgy.gateway.infrastructure.persistence.repo.UsageLogRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 管理接口：给运维和业务方看的。
 *
 * 功能是"管密钥、看用量、查日志"。
 * 生产环境这些接口必须加管理员鉴权，演示项目为了便于体验就先留空。
 */
@RestController
@RequestMapping("/admin")
public class AdminController {

    private final ApiKeyService apiKeyService;

    private final StatsService statsService;

    private final UsageLogRepository usageLogRepository;

    public AdminController(ApiKeyService apiKeyService,
                           StatsService statsService,
                           UsageLogRepository usageLogRepository) {
        this.apiKeyService = apiKeyService;
        this.statsService = statsService;
        this.usageLogRepository = usageLogRepository;
    }

    // ---------------- 密钥管理 ----------------

    /** 所有虚拟密钥及其用量 */
    @GetMapping("/keys")
    public ApiResponse<List<ApiKeyView>> listKeys() {
        return ApiResponse.ok(apiKeyService.listAll().stream().map(ApiKeyView::from).toList());
    }

    /**
     * 新建虚拟密钥。
     * 示例：curl -X POST "http://localhost:8080/admin/keys?name=运营系统&quota=500000"
     */
    @PostMapping("/keys")
    public ApiResponse<ApiKeyView> createKey(@RequestParam String name,
                                             @RequestParam(defaultValue = "1000000") long quota) {
        return ApiResponse.ok(ApiKeyView.from(apiKeyService.create(name, quota)));
    }

    /** 启用 / 禁用某个密钥 */
    @PostMapping("/keys/{id}/enabled")
    public ApiResponse<ApiKeyView> setEnabled(@PathVariable Long id,
                                              @RequestParam boolean enabled) {
        return ApiResponse.ok(ApiKeyView.from(apiKeyService.setEnabled(id, enabled)));
    }

    /** 把已用量清零（相当于"这个月重新开始算"） */
    @PostMapping("/keys/{id}/reset")
    public ApiResponse<ApiKeyView> resetUsage(@PathVariable Long id) {
        return ApiResponse.ok(ApiKeyView.from(apiKeyService.resetUsage(id)));
    }

    // ---------------- 观测 ----------------

    /** 最近 24 小时的整体运行指标 */
    @GetMapping("/stats")
    public ApiResponse<Map<String, Object>> stats() {
        return ApiResponse.ok(statsService.overview());
    }

    /** 最近 50 条调用日志 */
    @GetMapping("/logs")
    public ApiResponse<List<UsageLogView>> logs() {
        return ApiResponse.ok(
                usageLogRepository.findTop50ByOrderByCreatedAtDesc()
                        .stream().map(UsageLogView::from).toList());
    }
}
