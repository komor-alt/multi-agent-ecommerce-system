package com.ecommerce.data.repository;

import com.ecommerce.data.entity.RecInventoryEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface RecInventoryRepository extends JpaRepository<RecInventoryEntity, String> {

    Optional<RecInventoryEntity> findByProductIdAndCountry(String productId, String country);

    List<RecInventoryEntity> findByProductIdInAndCountry(List<String> productIds, String country);

    List<RecInventoryEntity> findByCountry(String country);

    long count();
}
