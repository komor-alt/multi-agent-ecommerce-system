package com.ecommerce.data.repository;

import com.ecommerce.data.entity.RecUserEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface RecUserRepository extends JpaRepository<RecUserEntity, String> {

    Optional<RecUserEntity> findByUserId(String userId);
}
