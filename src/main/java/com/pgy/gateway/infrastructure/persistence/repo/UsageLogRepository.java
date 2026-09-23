package com.pgy.gateway.infrastructure.persistence.repo;

import com.pgy.gateway.infrastructure.persistence.entity.UsageLogEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface UsageLogRepository extends JpaRepository<UsageLogEntity, Long> {

    List<UsageLogEntity> findTop50ByOrderByCreatedAtDesc();

    @Query("""
            select coalesce(sum(u.totalTokens), 0)
            from UsageLogEntity u
            where u.apiKeyId = :apiKeyId and u.createdAt >= :since
            """)
    Long sumTokensByApiKeySince(@Param("apiKeyId") Long apiKeyId, @Param("since") Instant since);

    @Query("""
            select count(u)
            from UsageLogEntity u
            where u.createdAt >= :since
            """)
    long countSince(@Param("since") Instant since);

    @Query("""
            select count(u)
            from UsageLogEntity u
            where u.createdAt >= :since and u.success = false
            """)
    long countFailureSince(@Param("since") Instant since);

    @Query("""
            select count(u)
            from UsageLogEntity u
            where u.createdAt >= :since and u.fallbackUsed = true
            """)
    long countFallbackSince(@Param("since") Instant since);
}
