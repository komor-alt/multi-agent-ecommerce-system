package com.ecommerce.data.repository;

import com.ecommerce.data.entity.RecOrderEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface RecOrderRepository extends JpaRepository<RecOrderEntity, String> {

    List<RecOrderEntity> findByUserId(String userId);

    Optional<RecOrderEntity> findByOrderId(String orderId);

    List<RecOrderEntity> findTop5ByUserIdOrderByOrderIdDesc(String userId);

    long countByUserId(String userId);
}
