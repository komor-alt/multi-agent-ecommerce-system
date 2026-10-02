package com.ecommerce.runtime.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Optional;

public interface RecommendationRunEventRepository extends JpaRepository<RecommendationRunEventEntity, String> {
    List<RecommendationRunEventEntity> findByRunIdAndSequenceGreaterThanOrderBySequenceAsc(
            String runId, int sequence);
    List<RecommendationRunEventEntity> findByRunIdAndSequenceGreaterThanOrderBySequenceAsc(
            String runId, int sequence, Pageable pageable);
    List<RecommendationRunEventEntity> findByRunIdOrderBySequenceAsc(String runId);
    Optional<RecommendationRunEventEntity> findByRunIdAndId(String runId, String id);
    Optional<RecommendationRunEventEntity> findTopByRunIdOrderBySequenceDesc(String runId);
}
