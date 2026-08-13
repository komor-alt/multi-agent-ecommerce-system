package com.ecommerce.aftersales.repository;

import com.ecommerce.aftersales.entity.ExecutionJobEntity;
import com.ecommerce.aftersales.model.AfterSalesTypes;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface ExecutionJobRepository extends JpaRepository<ExecutionJobEntity, String> {
    Optional<ExecutionJobEntity> findByProposalId(String proposalId);
    Optional<ExecutionJobEntity> findByIdempotencyKey(String idempotencyKey);

    /** 队列聚合用：一次批量查询多个工单的最新执行任务，避免逐工单 N+1。 */
    List<ExecutionJobEntity> findByTicketIdIn(Collection<String> ticketIds);
    List<ExecutionJobEntity> findTop10ByStatusAndNextRetryAtLessThanEqualOrderByNextRetryAtAsc(
            AfterSalesTypes.ExecutionStatus status,
            Instant now
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select job from ExecutionJobEntity job where job.id = :id")
    Optional<ExecutionJobEntity> findByIdForUpdate(@Param("id") String id);
}
