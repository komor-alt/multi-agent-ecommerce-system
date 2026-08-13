package com.ecommerce.aftersales.repository;

import com.ecommerce.aftersales.entity.ActionProposalEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface ActionProposalRepository extends JpaRepository<ActionProposalEntity, String> {
    Optional<ActionProposalEntity> findTopByTicketIdOrderByCreatedAtDesc(String ticketId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select proposal from ActionProposalEntity proposal where proposal.id = :id")
    Optional<ActionProposalEntity> findByIdForUpdate(@Param("id") String id);
}
