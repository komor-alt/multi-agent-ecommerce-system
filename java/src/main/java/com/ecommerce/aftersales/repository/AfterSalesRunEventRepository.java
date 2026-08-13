package com.ecommerce.aftersales.repository;

import com.ecommerce.aftersales.entity.AfterSalesRunEventEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AfterSalesRunEventRepository extends JpaRepository<AfterSalesRunEventEntity, String> {
    List<AfterSalesRunEventEntity> findByRunIdAndSequenceGreaterThanOrderBySequenceAsc(String runId, int sequence);
    List<AfterSalesRunEventEntity> findByRunIdOrderBySequenceAsc(String runId);
    Optional<AfterSalesRunEventEntity> findTopByRunIdOrderBySequenceDesc(String runId);
    Optional<AfterSalesRunEventEntity> findByRunIdAndId(String runId, String id);
}
