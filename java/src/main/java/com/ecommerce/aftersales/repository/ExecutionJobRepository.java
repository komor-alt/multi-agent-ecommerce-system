package com.ecommerce.aftersales.repository;

import com.ecommerce.aftersales.entity.ExecutionJobEntity;
import com.ecommerce.aftersales.model.AfterSalesTypes;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.domain.Pageable;
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
    /**
     * 读取可投递任务候选。真正的所有权仍由 findByIdForUpdate 中的状态转换获得；
     * Pageable 只控制单轮扫描上限，避免一次把积压任务全部读入内存。
     */
    @Query("select job from ExecutionJobEntity job " +
            "where job.status = :status and (job.nextRetryAt is null or job.nextRetryAt <= :now) " +
            "order by job.nextRetryAt asc, job.createdAt asc")
    List<ExecutionJobEntity> findDispatchable(
            @Param("status") AfterSalesTypes.ExecutionStatus status,
            @Param("now") Instant now,
            Pageable pageable);

    /** 查找租约过期的 RUNNING 任务，供多实例故障恢复。 */
    @Query("select job from ExecutionJobEntity job " +
            "where job.status = :status and job.leaseUntil <= :now order by job.leaseUntil asc")
    List<ExecutionJobEntity> findExpiredLeases(
            @Param("status") AfterSalesTypes.ExecutionStatus status,
            @Param("now") Instant now,
            Pageable pageable);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select job from ExecutionJobEntity job where job.id = :id")
    Optional<ExecutionJobEntity> findByIdForUpdate(@Param("id") String id);
}
