package com.ecommerce.aftersales.repository;

import com.ecommerce.aftersales.entity.ExecutionJobEntity;
import com.ecommerce.aftersales.model.AfterSalesTypes;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface ExecutionJobRepository extends JpaRepository<ExecutionJobEntity, String> {
    Optional<ExecutionJobEntity> findByProposalId(String proposalId);
    Optional<ExecutionJobEntity> findByIdempotencyKey(String idempotencyKey);
    List<ExecutionJobEntity> findTop10ByStatusAndNextRetryAtLessThanEqualOrderByNextRetryAtAsc(
            AfterSalesTypes.ExecutionStatus status,
            Instant now
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select job from ExecutionJobEntity job where job.id = :id")
    Optional<ExecutionJobEntity> findByIdForUpdate(@Param("id") String id);
}
