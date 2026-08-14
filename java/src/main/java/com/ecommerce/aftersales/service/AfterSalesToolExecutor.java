package com.ecommerce.aftersales.service;

import com.ecommerce.aftersales.connector.MockShopifyAfterSalesConnector;
import com.ecommerce.aftersales.entity.ActionProposalEntity;
import com.ecommerce.aftersales.entity.TicketAttachmentEntity;
import com.ecommerce.aftersales.model.AfterSalesAgentState;
import com.ecommerce.aftersales.model.AfterSalesTypes;
import com.ecommerce.aftersales.repository.ActionProposalRepository;
import com.ecommerce.aftersales.repository.TicketAttachmentRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class AfterSalesToolExecutor {
    public static final String GET_ORDER_DETAIL = "get_order_detail";
    public static final String GET_SHIPMENT_TRACE = "get_shipment_trace";
    public static final String GET_CARRIER_CASE = "get_carrier_case";
    public static final String GET_DELIVERY_PROOF = "get_delivery_proof";
    public static final String GET_DAMAGE_PHOTO = "get_damage_photo";
    public static final String GET_PRODUCT = "get_product";
    public static final String SEARCH_POLICY = "search_after_sales_policy";
    public static final String CALCULATE_COMPENSATION = "calculate_compensation";
    public static final String CREATE_ACTION_PROPOSAL = "create_action_proposal";

    private final MockShopifyAfterSalesConnector connector;
    private final DemoAfterSalesPolicyCatalogService policyCatalog;
    private final CompensationRuleService compensationRuleService;
    private final ActionProposalRepository proposalRepository;
    private final TicketAttachmentRepository attachmentRepository;
    private final ObjectMapper objectMapper;

    public AfterSalesToolExecutor(
            MockShopifyAfterSalesConnector connector,
            DemoAfterSalesPolicyCatalogService policyCatalog,
            CompensationRuleService compensationRuleService,
            ActionProposalRepository proposalRepository,
            TicketAttachmentRepository attachmentRepository,
            ObjectMapper objectMapper) {
        this.connector = connector;
        this.policyCatalog = policyCatalog;
        this.compensationRuleService = compensationRuleService;
        this.proposalRepository = proposalRepository;
        this.attachmentRepository = attachmentRepository;
        this.objectMapper = objectMapper;
    }

    public AfterSalesTypes.ToolResult execute(String action, AfterSalesAgentState state) {
        return switch (action) {
            case GET_ORDER_DETAIL -> getOrderDetail(state);
            case GET_SHIPMENT_TRACE -> getShipmentTrace(state);
            case GET_CARRIER_CASE -> getCarrierCase(state);
            case GET_DELIVERY_PROOF -> getDeliveryProof(state);
            case GET_DAMAGE_PHOTO -> getDamagePhoto(state);
            case GET_PRODUCT -> getProduct(state);
            case SEARCH_POLICY -> searchPolicy(state);
            case CALCULATE_COMPENSATION -> calculateCompensation(state);
            case CREATE_ACTION_PROPOSAL -> createActionProposal(state);
            default -> throw new IllegalArgumentException("TOOL_NOT_WHITELISTED");
        };
    }

    /**
     * 工具参数只来自服务端状态（订单快照/物流快照/交付快照），模型永远不能提供参数：
     * 任何客户消息或模型输出里的 orderId/trackingNumber/amount 都不会进入参数。
     */
    public Map<String, Object> trustedArguments(String action, AfterSalesAgentState state) {
        return switch (action) {
            case GET_ORDER_DETAIL -> Map.of(
                    "orderId", state.getTicket().getOrderId(),
                    "ticketId", state.getTicket().getId()
            );
            case GET_SHIPMENT_TRACE -> Map.of(
                    "orderId", state.getOrder().orderId(),
                    "trackingNumber", state.getOrder().trackingNumber()
            );
            case GET_CARRIER_CASE -> Map.of(
                    "orderId", state.getOrder().orderId(),
                    "trackingNumber", state.getOrder().trackingNumber()
            );
            case GET_DELIVERY_PROOF -> Map.of(
                    "orderId", state.getOrder().orderId(),
                    "trackingNumber", state.getOrder().trackingNumber()
            );
            case GET_DAMAGE_PHOTO -> Map.of(
                    "orderId", state.getOrder().orderId(),
                    "deliveryId", state.getDelivery().deliveryId()
            );
            case GET_PRODUCT -> Map.of(
                    "orderId", state.getOrder().orderId()
            );
            case SEARCH_POLICY -> Map.of(
                    "country", state.getOrder().country(),
                    "issueType", state.getTicket().getIssueType(),
                    "occurredAt", state.getTicket().getCreatedAt().toString()
            );
            case CALCULATE_COMPENSATION -> {
                // 按问题类型取对应证据图的参数（绝不跨图）：DAMAGED_ITEM 图没有 SHIPMENT 节点，
                // 不带 inactiveDays（Map.of 拒绝 null 值，必须按图构建）。
                Map<String, Object> args = new LinkedHashMap<>();
                args.put("orderAmount", state.getOrder().paidAmount());
                args.put("currency", state.getOrder().currency());
                args.put("policyVersion", state.getPolicy().version());
                String issueType = state.getTicket().getIssueType();
                if (AfterSalesTypes.IntakeResult.LOST_IN_TRANSIT.equals(issueType)) {
                    args.put("inactiveDays", state.getShipment().inactiveDays());
                    args.put("carrierOutcome", state.getCarrierCase().outcome());
                } else if (AfterSalesTypes.IntakeResult.DAMAGED_ITEM.equals(issueType)) {
                    args.put("deliveryStatus", state.getDelivery().status());
                    args.put("damagePhotoStatus", state.getDamagePhoto().status());
                } else {
                    args.put("inactiveDays", state.getShipment().inactiveDays());
                }
                yield args;
            }
            case CREATE_ACTION_PROPOSAL -> Map.of(
                    "ticketId", state.getTicket().getId(),
                    "actionType", state.getCompensation().actionType(),
                    "amount", state.getCompensation().amount(),
                    "currency", state.getCompensation().currency(),
                    "policyVersion", state.getPolicy().version()
            );
            default -> Map.of();
        };
    }

    private AfterSalesTypes.ToolResult getOrderDetail(AfterSalesAgentState state) {
        AfterSalesTypes.OrderSnapshot order = connector.getOrder(state.getTicket().getOrderId());
        state.setOrder(order);
        state.getTicket().setUserId(order.userId());
        String evidenceId = "order:" + order.orderId() + ":v1";
        state.getEvidenceIds().add(evidenceId);
        return new AfterSalesTypes.ToolResult(
                "Paid order verified for " + order.country() + " with trusted amount and currency.",
                order,
                List.of(evidenceId)
        );
    }

    private AfterSalesTypes.ToolResult getShipmentTrace(AfterSalesAgentState state) {
        require(state.getOrder(), "ORDER_REQUIRED");
        AfterSalesTypes.ShipmentSnapshot shipment = connector.getShipment(state.getOrder());
        state.setShipment(shipment);
        String evidenceId = "shipment:" + state.getOrder().orderId() + ":" + shipment.lastUpdatedAt().toString().substring(0, 10);
        state.getEvidenceIds().add(evidenceId);
        return new AfterSalesTypes.ToolResult(
                "Shipment has been inactive for " + shipment.inactiveDays() + " days at customs.",
                shipment,
                List.of(evidenceId)
        );
    }

    private AfterSalesTypes.ToolResult getCarrierCase(AfterSalesAgentState state) {
        require(state.getOrder(), "ORDER_REQUIRED");
        require(state.getShipment(), "SHIPMENT_REQUIRED");
        AfterSalesTypes.CarrierCaseSnapshot carrierCase = connector.getCarrierCase(state.getOrder());
        state.setCarrierCase(carrierCase);
        state.getEvidenceIds().add(carrierCase.evidenceId());
        return new AfterSalesTypes.ToolResult(
                "Carrier case " + carrierCase.caseId() + " is " + carrierCase.outcome() + ".",
                carrierCase,
                List.of(carrierCase.evidenceId())
        );
    }

    private AfterSalesTypes.ToolResult getDeliveryProof(AfterSalesAgentState state) {
        require(state.getOrder(), "ORDER_REQUIRED");
        AfterSalesTypes.DeliverySnapshot delivery = connector.getDelivery(state.getOrder());
        state.setDelivery(delivery);
        state.getEvidenceIds().add(delivery.evidenceId());
        return new AfterSalesTypes.ToolResult(
                "Delivery confirmed at " + delivery.deliveredLocation() + " on " + delivery.deliveredAt().toString().substring(0, 10) + ".",
                delivery,
                List.of(delivery.evidenceId())
        );
    }

    /**
     * 破损照片证据只能来自「属于该工单的已持久化图片附件元数据」（客户消息/模型不能提供照片内容）：
     * 取该工单最新的有效图片附件，evidenceId 与快照字段全部由服务端从附件记录确定性派生
     * （damage-photo:&lt;attachmentId&gt;:v1；核验结论只读持久化元数据中的服务端字段 reviewStatus，
     * 默认 PENDING_REVIEW）。工单没有任何有效图片附件 → 失败关闭（Agent Loop 在规划到
     * DAMAGE_PHOTO 时已先行转为 REQUEST_MORE_INFO 终态，本抛错只是纵深防御）。
     */
    private AfterSalesTypes.ToolResult getDamagePhoto(AfterSalesAgentState state) {
        require(state.getOrder(), "ORDER_REQUIRED");
        require(state.getDelivery(), "DELIVERY_REQUIRED");
        TicketAttachmentEntity attachment = attachmentRepository
                .findByTicketIdOrderByCreatedAtDesc(state.getTicket().getId()).stream()
                .filter(TicketAttachmentEntity::isImage)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("DAMAGE_PHOTO_ATTACHMENT_MISSING"));
        Map<String, Object> metadata = readMetadata(attachment.getMetadataJson());
        int width = intValue(metadata.get(TicketAttachmentEntity.METADATA_WIDTH));
        int height = intValue(metadata.get(TicketAttachmentEntity.METADATA_HEIGHT));
        long sizeBytes = longValue(metadata.get(TicketAttachmentEntity.METADATA_SIZE_BYTES));
        String status = stringValue(metadata.get(TicketAttachmentEntity.METADATA_REVIEW_STATUS), "PENDING_REVIEW");
        String reviewSummary = stringValue(metadata.get(TicketAttachmentEntity.METADATA_REVIEW_SUMMARY),
                "Customer-uploaded photo pending manual review.");
        String evidenceId = "damage-photo:" + attachment.getId() + ":v1";
        AfterSalesTypes.DamagePhotoSnapshot damagePhoto = new AfterSalesTypes.DamagePhotoSnapshot(
                evidenceId,
                attachment.getId(),
                attachment.getCreatedAt(),
                attachment.getContentType(),
                width,
                height,
                sizeBytes,
                status,
                reviewSummary,
                attachment.getChecksum());
        state.setDamagePhoto(damagePhoto);
        state.getEvidenceIds().add(evidenceId);
        return new AfterSalesTypes.ToolResult(
                "Damage photo review status: " + status + ".",
                damagePhoto,
                List.of(evidenceId)
        );
    }

    /** 附件元数据 JSON → Map；缺失/非法一律返回空 Map（快照字段走默认值，不因此失败）。 */
    private Map<String, Object> readMetadata(String metadataJson) {
        if (metadataJson == null || metadataJson.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(metadataJson, new TypeReference<>() {
            });
        } catch (Exception error) {
            return Map.of();
        }
    }

    private static int intValue(Object value) {
        return value instanceof Number number ? number.intValue() : 0;
    }

    private static long longValue(Object value) {
        return value instanceof Number number ? number.longValue() : 0L;
    }

    private static String stringValue(Object value, String fallback) {
        return value == null || String.valueOf(value).isBlank() ? fallback : String.valueOf(value);
    }

    private AfterSalesTypes.ToolResult getProduct(AfterSalesAgentState state) {
        require(state.getOrder(), "ORDER_REQUIRED");
        require(state.getDelivery(), "DELIVERY_REQUIRED");
        require(state.getDamagePhoto(), "DAMAGE_PHOTO_REQUIRED");
        AfterSalesTypes.ProductSnapshot product = connector.getProduct(state.getOrder());
        state.setProduct(product);
        state.getEvidenceIds().add(product.evidenceId());
        return new AfterSalesTypes.ToolResult(
                "Damaged item context: " + product.name() + " (" + product.productId() + ").",
                product,
                List.of(product.evidenceId())
        );
    }

    private AfterSalesTypes.ToolResult searchPolicy(AfterSalesAgentState state) {
        require(state.getOrder(), "ORDER_REQUIRED");
        // 可信键来自订单快照与工单：country + issueType + occurredAt；政策检索不需要物流/交付证据
        // （DAMAGED_ITEM 图没有 SHIPMENT 节点，绝不强制与当前图无关的证据；图顺序已由 Gate 保证）。
        // 无匹配政策时目录失败关闭（PolicyNotFoundException / POLICY_NOT_FOUND），不提供通用政策兜底。
        AfterSalesTypes.PolicyEvidence policy = policyCatalog.lookup(
                state.getOrder().country(),
                state.getTicket().getIssueType(),
                state.getTicket().getCreatedAt()
        );
        state.setPolicy(policy);
        state.getEvidenceIds().add(policy.evidenceId());
        return new AfterSalesTypes.ToolResult(
                policy.issueType() + " policy " + policy.version() + " section " + policy.section()
                        + " matched the ticket occurrence time.",
                policy,
                List.of(policy.evidenceId())
        );
    }

    /**
     * 补偿计算按问题类型分派（证据图决定所需证据，绝不跨图取证）：
     * - SHIPMENT_DELAY：ORDER+SHIPMENT+POLICY（既有规则）；
     * - LOST_IN_TRANSIT：ORDER+SHIPMENT+CARRIER_CASE+POLICY（承运商确认丢失才可补偿）；
     * - DAMAGED_ITEM：ORDER+DELIVERY+DAMAGE_PHOTO+PRODUCT+POLICY（交付签收 + 照片核验通过）。
     */
    private AfterSalesTypes.ToolResult calculateCompensation(AfterSalesAgentState state) {
        String issueType = state.getTicket().getIssueType();
        AfterSalesTypes.CompensationResult result = switch (issueType) {
            case AfterSalesTypes.IntakeResult.LOST_IN_TRANSIT -> {
                require(state.getOrder(), "ORDER_REQUIRED");
                require(state.getShipment(), "SHIPMENT_REQUIRED");
                require(state.getCarrierCase(), "CARRIER_CASE_REQUIRED");
                require(state.getPolicy(), "POLICY_REQUIRED");
                yield compensationRuleService.calculateLost(
                        state.getOrder(), state.getShipment(), state.getCarrierCase(), state.getPolicy());
            }
            case AfterSalesTypes.IntakeResult.DAMAGED_ITEM -> {
                require(state.getOrder(), "ORDER_REQUIRED");
                require(state.getDelivery(), "DELIVERY_REQUIRED");
                require(state.getDamagePhoto(), "DAMAGE_PHOTO_REQUIRED");
                require(state.getProduct(), "PRODUCT_REQUIRED");
                require(state.getPolicy(), "POLICY_REQUIRED");
                yield compensationRuleService.calculateDamage(
                        state.getOrder(), state.getDelivery(), state.getDamagePhoto(),
                        state.getProduct(), state.getPolicy());
            }
            default -> {
                require(state.getOrder(), "ORDER_REQUIRED");
                require(state.getShipment(), "SHIPMENT_REQUIRED");
                require(state.getPolicy(), "POLICY_REQUIRED");
                yield compensationRuleService.calculate(state.getOrder(), state.getShipment(), state.getPolicy());
            }
        };
        state.setCompensation(result);
        String evidenceId = "calculation:" + state.getTicket().getId() + ":v1";
        state.getEvidenceIds().add(evidenceId);
        return new AfterSalesTypes.ToolResult(
                result.eligible()
                        ? "Deterministic rules calculated an eligible " + result.amount() + " " + result.currency() + " compensation."
                        : "Deterministic rules blocked compensation: " + result.reason(),
                result,
                List.of(evidenceId)
        );
    }

    private AfterSalesTypes.ToolResult createActionProposal(AfterSalesAgentState state) {
        require(state.getCompensation(), "COMPENSATION_REQUIRED");
        if (!state.getCompensation().eligible()) {
            throw new IllegalStateException("COMPENSATION_NOT_ELIGIBLE");
        }
        ActionProposalEntity proposal = proposalRepository.save(ActionProposalEntity.builder()
                .id(UUID.randomUUID().toString())
                .ticketId(state.getTicket().getId())
                .actionType(state.getCompensation().actionType())
                .amount(state.getCompensation().amount())
                .currency(state.getCompensation().currency())
                .policyVersion(state.getPolicy().version())
                .proposalVersion("v1")
                .decisionSummary(decisionSummary(state))
                .evidenceIdsJson(writeJson(state.getEvidenceIds()))
                .status(AfterSalesTypes.ProposalStatus.PENDING)
                .build());
        state.setProposalId(proposal.getId());
        return new AfterSalesTypes.ToolResult(
                "Action proposal created and isolated from the side-effect executor.",
                proposal,
                List.copyOf(state.getEvidenceIds())
        );
    }

    /** 方案摘要按问题类型分派（仅安全的结构化文本，不含客户消息原文）。 */
    private String decisionSummary(AfterSalesAgentState state) {
        return switch (state.getTicket().getIssueType()) {
            case AfterSalesTypes.IntakeResult.LOST_IN_TRANSIT ->
                    "The carrier confirmed the parcel lost beyond the applicable policy threshold. "
                            + "A lost-parcel refund requires operator approval.";
            case AfterSalesTypes.IntakeResult.DAMAGED_ITEM ->
                    "The delivered item has verified damage under the applicable policy. "
                            + "A damage compensation proposal requires operator approval.";
            default ->
                    "The paid order is inactive beyond the applicable policy threshold. "
                            + "A delay coupon requires operator approval.";
        };
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception error) {
            throw new IllegalStateException("PROPOSAL_SERIALIZATION_FAILED", error);
        }
    }

    private static void require(Object value, String code) {
        if (value == null) {
            throw new IllegalStateException(code);
        }
    }
}
