package com.ecommerce.aftersales.repository;

import com.ecommerce.aftersales.entity.TicketMessageEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TicketMessageRepository extends JpaRepository<TicketMessageEntity, String> {
    List<TicketMessageEntity> findByTicketIdOrderByCreatedAtAsc(String ticketId);
}
