package com.ecommerce.aftersales.repository;

import com.ecommerce.aftersales.entity.ApprovalRecordEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ApprovalRecordRepository extends JpaRepository<ApprovalRecordEntity, String> {
    List<ApprovalRecordEntity> findByProposalIdOrderByCreatedAtAsc(String proposalId);
}
