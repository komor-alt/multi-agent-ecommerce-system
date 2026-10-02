package com.ecommerce.runtime.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface RecommendationTaskRepository extends JpaRepository<RecommendationTaskEntity, String> {
    long countByStatus(String status);

    List<RecommendationTaskEntity> findByRunIdOrderByCreatedAtAsc(String runId);
}
