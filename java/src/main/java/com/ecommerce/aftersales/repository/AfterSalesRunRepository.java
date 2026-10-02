package com.ecommerce.aftersales.repository;

import com.ecommerce.aftersales.entity.AfterSalesRunEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;
import jakarta.persistence.LockModeType;

import java.time.Instant;
import java.util.Optional;

public interface AfterSalesRunRepository extends JpaRepository<AfterSalesRunEntity, String> {

    /**
     * 事件序号分配锁：锁定父 run 后再读取最大 sequence，使多个应用实例对同一 run
     * 的 append 串行化。事件表上的 (run_id, sequence) 唯一约束是最后一道防线。
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select run from AfterSalesRunEntity run where run.id = :runId")
    Optional<AfterSalesRunEntity> findByIdForUpdate(@Param("runId") String runId);

    /**
     * 原子认领：仅当 run 仍为 READY 时更新为 RUNNING 并写入 startedAt。
     * 单条条件 UPDATE 由数据库行锁保证互斥，并发/重复调用最多一个调用方返回 1，
     * 其余返回 0（已启动或已终态），从而实现「最多启动一次」。
     * 自带事务并在返回前提交，调用方在提交后才把 run 交给 executor，避免
     * 异步线程在事务提交前读取不到/读不到新状态的竞态。
     */
    @Transactional
    @Modifying(clearAutomatically = true)
    @Query("update AfterSalesRunEntity r set r.status = 'RUNNING', r.startedAt = :startedAt " +
            "where r.id = :runId and r.status = 'READY'")
    int claimReady(@Param("runId") String runId, @Param("startedAt") Instant startedAt);
}
