package com.pgy.gateway.application;

import com.pgy.gateway.infrastructure.persistence.repo.UsageLogRepository;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 运行统计。
 *
 * 网关最重要的两个数字：
 *   成功率   —— 整体是否健康
 *   降级率   —— 有多少请求是靠备用模型救回来的
 *
 * 降级率尤其值得盯：它悄悄升高，通常意味着主力模型正在变差，
 * 但因为降级机制兜住了，业务侧毫无感知。如果不看这个指标，就发现不了。
 */
@Service
public class StatsService {

    /** 最近 24 小时 */
    private static final int WINDOW_HOURS = 24;

    private final UsageLogRepository usageLogRepository;

    public StatsService(UsageLogRepository usageLogRepository) {
        this.usageLogRepository = usageLogRepository;
    }

    public Map<String, Object> overview() {
        Instant since = Instant.now().minus(WINDOW_HOURS, ChronoUnit.HOURS);

        long total = usageLogRepository.countSince(since);
        long failures = usageLogRepository.countFailureSince(since);
        long fallbacks = usageLogRepository.countFallbackSince(since);

        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("windowHours", WINDOW_HOURS);
        stats.put("totalCalls", total);
        stats.put("successCalls", total - failures);
        stats.put("failureCalls", failures);
        stats.put("fallbackCalls", fallbacks);
        stats.put("successRate", ratio(total - failures, total));
        stats.put("failureRate", ratio(failures, total));
        stats.put("fallbackRate", ratio(fallbacks, total));
        return stats;
    }

    private double ratio(long part, long total) {
        if (total <= 0) {
            return 0.0;
        }
        return Math.round((double) part / total * 10000.0) / 10000.0;
    }
}
