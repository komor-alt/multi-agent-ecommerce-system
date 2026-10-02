package com.ecommerce.runtime.persistence;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.List;
import java.time.Instant;

public interface RecommendationRunRepository extends JpaRepository<RecommendationRunEntity, String> {
    long countByStatus(String status);

    @Query("select run.status from RecommendationRunEntity run where run.id = :runId")
    Optional<String> findStatusValueById(@Param("runId") String runId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select run from RecommendationRunEntity run where run.id = :runId")
    Optional<RecommendationRunEntity> findByIdForUpdate(@Param("runId") String runId);

    @Query("select run.id from RecommendationRunEntity run where run.status = 'RUNNING' "
            + "and run.recoverable = true and (run.executionToken is null "
            + "or run.executionLeaseUntil is null or run.executionLeaseUntil <= :now) "
            + "order by run.createdAt asc, run.id asc")
    List<String> findClaimableIds(@Param("now") Instant now, Pageable pageable);
}
