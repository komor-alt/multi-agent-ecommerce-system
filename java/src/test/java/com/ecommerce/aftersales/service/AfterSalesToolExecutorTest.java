package com.ecommerce.aftersales.service;

import com.ecommerce.aftersales.connector.MockShopifyAfterSalesConnector;
import com.ecommerce.aftersales.entity.ActionProposalEntity;
import com.ecommerce.aftersales.entity.AfterSalesTicketEntity;
import com.ecommerce.aftersales.entity.TicketAttachmentEntity;
import com.ecommerce.aftersales.model.AfterSalesAgentState;
import com.ecommerce.aftersales.model.AfterSalesTypes;
import com.ecommerce.aftersales.repository.ActionProposalRepository;
import com.ecommerce.aftersales.repository.TicketAttachmentRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AfterSalesToolExecutorTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ActionProposalRepository proposalRepository = mock(ActionProposalRepository.class);
    private final TicketAttachmentRepository attachmentRepository = mock(TicketAttachmentRepository.class);
    private final AfterSalesToolExecutor executor = new AfterSalesToolExecutor(
            new MockShopifyAfterSalesConnector(),
            new DemoAfterSalesPolicyCatalogService(),
            new CompensationRuleService(),
            proposalRepository,
            attachmentRepository,
            objectMapper);

    /** 破损照片取证用的持久化图片附件（服务端元数据：核验结论 VERIFIED）。 */
    private TicketAttachmentEntity verifiedPhotoAttachment(String attachmentId, String ticketId, String metadataJson) {
        return TicketAttachmentEntity.builder()
                .id(attachmentId)
                .ticketId(ticketId)
                .messageId("message-1")
                .fileName("damage.jpg")
                .contentType("image/jpeg")
                .storageKey("objects/" + attachmentId)
                .metadataJson(metadataJson)
                .createdAt(Instant.parse("2026-08-10T02:00:00Z"))
                .build();
    }

    @Test
    void indonesiaOrderOid4001ReachesProposalCreationInsteadOfPolicyNotFound() {
        when(proposalRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        AfterSalesAgentState state = state("O-ID-4001", "2026-08-01T00:00:00Z");

        AfterSalesTypes.ToolResult orderResult = executor.execute(AfterSalesToolExecutor.GET_ORDER_DETAIL, state);
        AfterSalesTypes.OrderSnapshot order = (AfterSalesTypes.OrderSnapshot) orderResult.data();
        assertThat(order.country()).isEqualTo("ID");
        assertThat(order.trackingNumber()).isEqualTo("SF-ID-4001");
        // 种子回归：IDR 订单金额必须保持 IDR 合理量级，不得退回 241.0。
        assertThat(order.paidAmount()).isEqualByComparingTo("2410000.00");

        AfterSalesTypes.ToolResult shipmentResult = executor.execute(AfterSalesToolExecutor.GET_SHIPMENT_TRACE, state);
        AfterSalesTypes.ShipmentSnapshot shipment = (AfterSalesTypes.ShipmentSnapshot) shipmentResult.data();
        assertThat(shipment.inactiveDays()).isEqualTo(10);
        assertThat(shipment.timeline().get(shipment.timeline().size() - 1).location()).isEqualTo("Jakarta ID");

        AfterSalesTypes.ToolResult policyResult = executor.execute(AfterSalesToolExecutor.SEARCH_POLICY, state);
        AfterSalesTypes.PolicyEvidence policy = (AfterSalesTypes.PolicyEvidence) policyResult.data();
        assertThat(policy.policyId()).isEqualTo("ID_SHIPMENT_DELAY");
        assertThat(policy.evidenceId()).isEqualTo("policy:ID_SHIPMENT_DELAY:v2#section-4.2");
        // 工具/检索摘要不得写死越南。
        assertThat(policyResult.summary()).doesNotContain("Vietnam").contains("v2");

        AfterSalesTypes.ToolResult compensationResult = executor.execute(AfterSalesToolExecutor.CALCULATE_COMPENSATION, state);
        AfterSalesTypes.CompensationResult compensation = (AfterSalesTypes.CompensationResult) compensationResult.data();
        assertThat(compensation.eligible()).isTrue();
        // 确定性计算：2410000.00 IDR × 10% = 241000.00，低于 IDR 上限 250000。
        assertThat(compensation.amount()).isEqualByComparingTo("241000.00");
        assertThat(compensation.currency()).isEqualTo("IDR");

        executor.execute(AfterSalesToolExecutor.CREATE_ACTION_PROPOSAL, state);
        assertThat(state.getProposalId()).isNotNull();

        ArgumentCaptor<ActionProposalEntity> proposalCaptor = ArgumentCaptor.forClass(ActionProposalEntity.class);
        verify(proposalRepository).save(proposalCaptor.capture());
        ActionProposalEntity proposal = proposalCaptor.getValue();
        assertThat(proposal.getActionType()).isEqualTo("DELAY_COMPENSATION_COUPON");
        assertThat(proposal.getAmount()).isEqualByComparingTo("241000.00");
        assertThat(proposal.getCurrency()).isEqualTo("IDR");
        assertThat(proposal.getPolicyVersion()).isEqualTo("v2");
        assertThat(proposal.getStatus()).isEqualTo(AfterSalesTypes.ProposalStatus.PENDING);
        // decisionSummary 国家/政策中立，不写死越南。
        assertThat(proposal.getDecisionSummary()).doesNotContain("Vietnam").contains("policy threshold");
        // 证据 ID 链完整且含稳定政策证据 ID。
        assertThat(proposal.getEvidenceIdsJson())
                .contains("order:O-ID-4001:v1")
                .contains("policy:ID_SHIPMENT_DELAY:v2#section-4.2");
        assertThat(state.getEvidenceIds()).hasSize(4);
        assertThat(state.getEvidenceIds()).contains(
                "order:O-ID-4001:v1",
                "policy:ID_SHIPMENT_DELAY:v2#section-4.2",
                "calculation:" + state.getTicket().getId() + ":v1");
    }

    @Test
    void lostInTransitFlowCreatesRefundProposalWithCarrierCaseEvidence() {
        when(proposalRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        AfterSalesAgentState state = state("O-VN-5002", "2026-08-01T00:00:00Z", "LOST_IN_TRANSIT");

        executor.execute(AfterSalesToolExecutor.GET_ORDER_DETAIL, state);
        executor.execute(AfterSalesToolExecutor.GET_SHIPMENT_TRACE, state);
        AfterSalesTypes.ToolResult carrierResult = executor.execute(AfterSalesToolExecutor.GET_CARRIER_CASE, state);
        AfterSalesTypes.CarrierCaseSnapshot carrierCase = (AfterSalesTypes.CarrierCaseSnapshot) carrierResult.data();
        assertThat(carrierCase.caseId()).isEqualTo("CC-VN-5002");
        assertThat(carrierCase.outcome()).isEqualTo("LOST_CONFIRMED");
        assertThat(carrierCase.evidenceId()).isEqualTo("carrier-case:CC-VN-5002:v1");

        AfterSalesTypes.ToolResult policyResult = executor.execute(AfterSalesToolExecutor.SEARCH_POLICY, state);
        AfterSalesTypes.PolicyEvidence policy = (AfterSalesTypes.PolicyEvidence) policyResult.data();
        assertThat(policy.policyId()).isEqualTo("VN_LOST_IN_TRANSIT");
        assertThat(policy.evidenceId()).isEqualTo("policy:VN_LOST_IN_TRANSIT:v3#section-5.2");

        AfterSalesTypes.ToolResult compensationResult = executor.execute(AfterSalesToolExecutor.CALCULATE_COMPENSATION, state);
        AfterSalesTypes.CompensationResult compensation = (AfterSalesTypes.CompensationResult) compensationResult.data();
        assertThat(compensation.eligible()).isTrue();
        assertThat(compensation.actionType()).isEqualTo("LOST_PARCEL_REFUND");
        // 全额退款按上限截断：1899000.00 VND × 1.00 → 上限 150000.00。
        assertThat(compensation.amount()).isEqualByComparingTo("150000.00");
        assertThat(compensation.currency()).isEqualTo("VND");

        executor.execute(AfterSalesToolExecutor.CREATE_ACTION_PROPOSAL, state);
        ArgumentCaptor<ActionProposalEntity> proposalCaptor = ArgumentCaptor.forClass(ActionProposalEntity.class);
        verify(proposalRepository).save(proposalCaptor.capture());
        ActionProposalEntity proposal = proposalCaptor.getValue();
        assertThat(proposal.getActionType()).isEqualTo("LOST_PARCEL_REFUND");
        assertThat(proposal.getDecisionSummary()).contains("lost");
        // 证据链完整：订单 + 物流 + 承运商案件 + 政策 + 计算。
        assertThat(proposal.getEvidenceIdsJson())
                .contains("order:O-VN-5002:v1")
                .contains("carrier-case:CC-VN-5002:v1")
                .contains("policy:VN_LOST_IN_TRANSIT:v3#section-5.2");
        assertThat(state.getEvidenceIds()).hasSize(5);
    }

    @Test
    void damagedItemFlowCreatesDamageProposalWithDeliveryGraphEvidence() {
        when(proposalRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(attachmentRepository.findByTicketIdOrderByCreatedAtDesc("ticket-4001")).thenReturn(List.of(
                verifiedPhotoAttachment("att-dmg-01", "ticket-4001",
                        "{\"width\":1080,\"height\":1440,\"sizeBytes\":1240000,\"reviewStatus\":\"VERIFIED\","
                                + "\"reviewSummary\":\"Manual review confirmed visible damage matching the reported item.\"}")));
        AfterSalesAgentState state = state("O-SG-1001", "2026-08-01T00:00:00Z", "DAMAGED_ITEM");

        executor.execute(AfterSalesToolExecutor.GET_ORDER_DETAIL, state);
        AfterSalesTypes.ToolResult deliveryResult = executor.execute(AfterSalesToolExecutor.GET_DELIVERY_PROOF, state);
        AfterSalesTypes.DeliverySnapshot delivery = (AfterSalesTypes.DeliverySnapshot) deliveryResult.data();
        assertThat(delivery.status()).isEqualTo("DELIVERED");
        assertThat(delivery.evidenceId()).startsWith("delivery:O-SG-1001:");

        AfterSalesTypes.ToolResult photoResult = executor.execute(AfterSalesToolExecutor.GET_DAMAGE_PHOTO, state);
        AfterSalesTypes.DamagePhotoSnapshot photo = (AfterSalesTypes.DamagePhotoSnapshot) photoResult.data();
        assertThat(photo.status()).isEqualTo("VERIFIED");
        // 证据 ID 由服务端从持久化附件记录确定性派生（不再来自订单种子）。
        assertThat(photo.evidenceId()).isEqualTo("damage-photo:att-dmg-01:v1");
        assertThat(photo.photoId()).isEqualTo("att-dmg-01");
        assertThat(photo.contentType()).isEqualTo("image/jpeg");

        AfterSalesTypes.ToolResult productResult = executor.execute(AfterSalesToolExecutor.GET_PRODUCT, state);
        AfterSalesTypes.ProductSnapshot product = (AfterSalesTypes.ProductSnapshot) productResult.data();
        assertThat(product.productId()).isEqualTo("P001");
        assertThat(product.evidenceId()).isEqualTo("product:P001:v1");

        AfterSalesTypes.ToolResult policyResult = executor.execute(AfterSalesToolExecutor.SEARCH_POLICY, state);
        AfterSalesTypes.PolicyEvidence policy = (AfterSalesTypes.PolicyEvidence) policyResult.data();
        assertThat(policy.policyId()).isEqualTo("SG_DAMAGED_ITEM");
        assertThat(policy.evidenceId()).isEqualTo("policy:SG_DAMAGED_ITEM:v2#section-6.3");

        AfterSalesTypes.ToolResult compensationResult = executor.execute(AfterSalesToolExecutor.CALCULATE_COMPENSATION, state);
        AfterSalesTypes.CompensationResult compensation = (AfterSalesTypes.CompensationResult) compensationResult.data();
        assertThat(compensation.eligible()).isTrue();
        assertThat(compensation.actionType()).isEqualTo("DAMAGE_COMPENSATION_COUPON");
        // 588.00 SGD × 30% = 176.40 → 上限 25.00。
        assertThat(compensation.amount()).isEqualByComparingTo("25.00");
        assertThat(compensation.currency()).isEqualTo("SGD");

        executor.execute(AfterSalesToolExecutor.CREATE_ACTION_PROPOSAL, state);
        ArgumentCaptor<ActionProposalEntity> proposalCaptor = ArgumentCaptor.forClass(ActionProposalEntity.class);
        verify(proposalRepository).save(proposalCaptor.capture());
        ActionProposalEntity proposal = proposalCaptor.getValue();
        assertThat(proposal.getActionType()).isEqualTo("DAMAGE_COMPENSATION_COUPON");
        assertThat(proposal.getDecisionSummary()).contains("damage");
        // 证据链完整：订单 + 交付 + 照片 + 商品 + 政策 + 计算，无 SHIPMENT（绝不跨图取证）。
        assertThat(proposal.getEvidenceIdsJson())
                .contains("order:O-SG-1001:v1")
                .contains("damage-photo:att-dmg-01:v1")
                .contains("product:P001:v1")
                .contains("policy:SG_DAMAGED_ITEM:v2#section-6.3")
                .doesNotContain("shipment:");
        assertThat(state.getEvidenceIds()).hasSize(6);
    }

    @Test
    void damagePhotoSnapshotDerivesFromPersistedAttachmentMetadata() {
        // 服务端从持久化附件元数据派生快照：尺寸/大小/核验结论/校验和全部来自附件记录，订单种子不再参与。
        TicketAttachmentEntity attachment = verifiedPhotoAttachment("att-meta-01", "ticket-4001",
                "{\"width\":800,\"height\":600,\"sizeBytes\":204800,\"reviewStatus\":\"PENDING_REVIEW\"}");
        attachment.setChecksum("sha256:abc123");
        when(attachmentRepository.findByTicketIdOrderByCreatedAtDesc("ticket-4001")).thenReturn(List.of(attachment));
        AfterSalesAgentState state = state("O-SG-1001", "2026-08-01T00:00:00Z", "DAMAGED_ITEM");
        executor.execute(AfterSalesToolExecutor.GET_ORDER_DETAIL, state);
        executor.execute(AfterSalesToolExecutor.GET_DELIVERY_PROOF, state);

        AfterSalesTypes.ToolResult photoResult = executor.execute(AfterSalesToolExecutor.GET_DAMAGE_PHOTO, state);
        AfterSalesTypes.DamagePhotoSnapshot photo = (AfterSalesTypes.DamagePhotoSnapshot) photoResult.data();
        assertThat(photo.evidenceId()).isEqualTo("damage-photo:att-meta-01:v1");
        assertThat(photo.photoId()).isEqualTo("att-meta-01");
        assertThat(photo.width()).isEqualTo(800);
        assertThat(photo.height()).isEqualTo(600);
        assertThat(photo.sizeBytes()).isEqualTo(204800L);
        assertThat(photo.capturedAt()).isEqualTo(Instant.parse("2026-08-10T02:00:00Z"));
        assertThat(photo.status()).isEqualTo("PENDING_REVIEW");
        // 校验和由服务端从持久化附件记录原样带入证据快照（不可信客户输入）。
        assertThat(photo.checksum()).isEqualTo("sha256:abc123");
    }

    @Test
    void damagePhotoWithoutImageAttachmentFailsClosed() {
        // 工单没有持久化图片附件：取证失败关闭（Agent Loop 层面已先转为 REQUEST_MORE_INFO，这里是纵深防御）。
        when(attachmentRepository.findByTicketIdOrderByCreatedAtDesc("ticket-4001")).thenReturn(List.of());
        AfterSalesAgentState state = state("O-SG-1001", "2026-08-01T00:00:00Z", "DAMAGED_ITEM");
        executor.execute(AfterSalesToolExecutor.GET_ORDER_DETAIL, state);
        executor.execute(AfterSalesToolExecutor.GET_DELIVERY_PROOF, state);

        assertThatThrownBy(() -> executor.execute(AfterSalesToolExecutor.GET_DAMAGE_PHOTO, state))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("DAMAGE_PHOTO_ATTACHMENT_MISSING");
        assertThat(state.getDamagePhoto()).isNull();
    }

    @Test
    void nonImageAttachmentIsNotAcceptedAsDamagePhoto() {
        // 非图片附件（如 PDF）不算有效破损照片证据：只取图片附件，其余忽略。
        when(attachmentRepository.findByTicketIdOrderByCreatedAtDesc("ticket-4001")).thenReturn(List.of(
                TicketAttachmentEntity.builder()
                        .id("att-pdf-01")
                        .ticketId("ticket-4001")
                        .messageId("message-1")
                        .fileName("receipt.pdf")
                        .contentType("application/pdf")
                        .storageKey("objects/att-pdf-01")
                        .createdAt(Instant.now())
                        .build()));
        AfterSalesAgentState state = state("O-SG-1001", "2026-08-01T00:00:00Z", "DAMAGED_ITEM");
        executor.execute(AfterSalesToolExecutor.GET_ORDER_DETAIL, state);
        executor.execute(AfterSalesToolExecutor.GET_DELIVERY_PROOF, state);

        assertThatThrownBy(() -> executor.execute(AfterSalesToolExecutor.GET_DAMAGE_PHOTO, state))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("DAMAGE_PHOTO_ATTACHMENT_MISSING");
    }

    @Test
    void damagedItemWithRejectedPhotoIsBlockedFromProposal() {
        // 服务端核验结论 REJECTED（持久化附件元数据）：补偿失败关闭，绝不生成方案。
        when(attachmentRepository.findByTicketIdOrderByCreatedAtDesc("ticket-4001")).thenReturn(List.of(
                verifiedPhotoAttachment("att-rej-01", "ticket-4001",
                        "{\"reviewStatus\":\"REJECTED\"}")));
        AfterSalesAgentState state = state("O-MY-2001", "2026-08-01T00:00:00Z", "DAMAGED_ITEM");

        executor.execute(AfterSalesToolExecutor.GET_ORDER_DETAIL, state);
        executor.execute(AfterSalesToolExecutor.GET_DELIVERY_PROOF, state);
        executor.execute(AfterSalesToolExecutor.GET_DAMAGE_PHOTO, state);
        executor.execute(AfterSalesToolExecutor.GET_PRODUCT, state);
        executor.execute(AfterSalesToolExecutor.SEARCH_POLICY, state);

        AfterSalesTypes.ToolResult compensationResult = executor.execute(AfterSalesToolExecutor.CALCULATE_COMPENSATION, state);
        AfterSalesTypes.CompensationResult compensation = (AfterSalesTypes.CompensationResult) compensationResult.data();
        assertThat(compensation.eligible()).isFalse();
        assertThat(compensation.reason()).isEqualTo("DAMAGE_PHOTO_NOT_VERIFIED");

        assertThatThrownBy(() -> executor.execute(AfterSalesToolExecutor.CREATE_ACTION_PROPOSAL, state))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("COMPENSATION_NOT_ELIGIBLE");
    }

    @Test
    void lostInTransitWithOpenCarrierInvestigationIsBlocked() {
        // O-ID-4001 承运商调查未关闭（UNDER_INVESTIGATION）：计算失败关闭，绝不生成方案。
        AfterSalesAgentState state = state("O-ID-4001", "2026-08-01T00:00:00Z", "LOST_IN_TRANSIT");

        executor.execute(AfterSalesToolExecutor.GET_ORDER_DETAIL, state);
        executor.execute(AfterSalesToolExecutor.GET_SHIPMENT_TRACE, state);
        executor.execute(AfterSalesToolExecutor.GET_CARRIER_CASE, state);
        executor.execute(AfterSalesToolExecutor.SEARCH_POLICY, state);

        AfterSalesTypes.ToolResult compensationResult = executor.execute(AfterSalesToolExecutor.CALCULATE_COMPENSATION, state);
        AfterSalesTypes.CompensationResult compensation = (AfterSalesTypes.CompensationResult) compensationResult.data();
        assertThat(compensation.eligible()).isFalse();
        assertThat(compensation.reason()).isEqualTo("CARRIER_INVESTIGATION_OPEN");
    }

    @Test
    void newEvidenceToolArgumentsAreServerDerivedNeverModelSupplied() {
        AfterSalesAgentState state = state("O-SG-1001", "2026-08-01T00:00:00Z", "DAMAGED_ITEM");
        executor.execute(AfterSalesToolExecutor.GET_ORDER_DETAIL, state);
        executor.execute(AfterSalesToolExecutor.GET_DELIVERY_PROOF, state);

        // 参数全部来自服务端状态（订单快照/交付快照），客户消息或模型输出无法注入。
        assertThat(executor.trustedArguments(AfterSalesToolExecutor.GET_CARRIER_CASE, state))
                .containsEntry("orderId", "O-SG-1001")
                .containsEntry("trackingNumber", "SF-SG-1001");
        assertThat(executor.trustedArguments(AfterSalesToolExecutor.GET_DELIVERY_PROOF, state))
                .containsEntry("orderId", "O-SG-1001")
                .containsEntry("trackingNumber", "SF-SG-1001");
        Map<String, Object> photoArgs = executor.trustedArguments(AfterSalesToolExecutor.GET_DAMAGE_PHOTO, state);
        assertThat(photoArgs).containsEntry("orderId", "O-SG-1001");
        assertThat(photoArgs).containsEntry("deliveryId", state.getDelivery().deliveryId());
        assertThat(executor.trustedArguments(AfterSalesToolExecutor.GET_PRODUCT, state))
                .containsExactlyEntriesOf(Map.of("orderId", "O-SG-1001"));
    }

    @Test
    void damagePhotoWithoutDeliveryFailsClosed() {
        AfterSalesAgentState state = state("O-SG-1001", "2026-08-01T00:00:00Z", "DAMAGED_ITEM");
        executor.execute(AfterSalesToolExecutor.GET_ORDER_DETAIL, state);

        assertThatThrownBy(() -> executor.execute(AfterSalesToolExecutor.GET_DAMAGE_PHOTO, state))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("DELIVERY_REQUIRED");
    }

    @Test
    void unsupportedCountryFailsClosedOnPolicyLookupWithStructuredError() {
        AfterSalesAgentState state = state("O-ID-4001", "2026-08-01T00:00:00Z");
        state.setOrder(new AfterSalesTypes.OrderSnapshot(
                "O-KR-9001", "sea_kr_001", "shopify", "KR", "KRW", "KR",
                new BigDecimal("50000.00"), true, "cross_border_shipping", 5, "SF-KR-9001"));
        state.setShipment(new AfterSalesTypes.ShipmentSnapshot(
                "SF-KR-9001", "cross_border_shipping", Instant.now(), 10, 5, List.of()));

        assertThatThrownBy(() -> executor.execute(AfterSalesToolExecutor.SEARCH_POLICY, state))
                .isInstanceOfSatisfying(PolicyNotFoundException.class, error -> {
                    assertThat(error.getMessage()).isEqualTo("POLICY_NOT_FOUND");
                    assertThat(error.getCountry()).isEqualTo("KR");
                    assertThat(error.getIssueType()).isEqualTo("SHIPMENT_DELAY");
                });
        assertThat(state.getPolicy()).isNull();
    }

    private AfterSalesAgentState state(String orderId, String createdAt) {
        // 与既有测试相同的种子消息与 SHIPMENT_DELAY 问题类型。
        AfterSalesTicketEntity ticket = AfterSalesTicketEntity.builder()
                .id("ticket-4001")
                .ticketNo("AS-4001")
                .orderId(orderId)
                .issueType("SHIPMENT_DELAY")
                .customerMessage("my parcel from Indonesia has not moved in days")
                .status(AfterSalesTypes.TicketStatus.ANALYZING)
                .createdAt(Instant.parse(createdAt))
                .build();
        return new AfterSalesAgentState("run-4001", ticket);
    }

    private AfterSalesAgentState state(String orderId, String createdAt, String issueType) {
        AfterSalesTicketEntity ticket = AfterSalesTicketEntity.builder()
                .id("ticket-4001")
                .ticketNo("AS-4001")
                .orderId(orderId)
                .issueType(issueType)
                .customerMessage("my parcel has a problem")
                .status(AfterSalesTypes.TicketStatus.ANALYZING)
                .createdAt(Instant.parse(createdAt))
                .build();
        return new AfterSalesAgentState("run-4001", ticket);
    }
}
