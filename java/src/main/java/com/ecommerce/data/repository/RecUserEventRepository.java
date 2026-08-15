package com.ecommerce.data.repository;

import com.ecommerce.data.entity.RecUserEventEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface RecUserEventRepository extends JpaRepository<RecUserEventEntity, String> {

    List<RecUserEventEntity> findTop20ByUserIdOrderByCreatedAtDesc(String userId);

    long countByUserId(String userId);
}
