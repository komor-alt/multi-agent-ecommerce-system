package com.ecommerce.runtime.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface RecommendationArtifactRepository extends JpaRepository<RecommendationArtifactEntity, String> {
    List<RecommendationArtifactEntity> findByRunIdOrderByCreatedAtAsc(String runId);
}
