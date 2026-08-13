package com.ecommerce.aftersales.repository;

import com.ecommerce.aftersales.entity.AfterSalesTicketEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AfterSalesTicketRepository extends JpaRepository<AfterSalesTicketEntity, String> {
    List<AfterSalesTicketEntity> findTop20ByOrderByCreatedAtDesc();
}
