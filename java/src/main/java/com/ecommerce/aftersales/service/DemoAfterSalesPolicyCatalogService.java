package com.ecommerce.aftersales.service;

import com.ecommerce.aftersales.model.AfterSalesTypes;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * 演示用售后政策目录：小、可信、确定、版本化的只读目录。
 *
 * 覆盖 DemoFulfillmentDataFactory 订单中出现的 SEA 国家（SG/MY/TH/ID/VN）与三个受理问题类型
 * （SHIPMENT_DELAY / LOST_IN_TRANSIT / DAMAGED_ITEM）；数据是演示政策，不代表任何真实法律。
 * 每个国家每种问题类型两条版本（v1 于 2025-06-01 生效，v2 于 2026-01-01 生效），
 * VN 保持既有当前版本 v3（2026-01-01 生效）；阈值/比例/动作类型按问题类型固定，
 * 补偿上限按订单币种取值。SHIPMENT_DELAY 的政策行与证据 ID 与改版前完全一致。
 *
 * 查找规则：
 * - 只接受可信 country、issueType 与 ticket occurredAt；
 * - 在 occurredAt 之前（含当天）已生效的政策中取生效时间最新的版本；
 * - 无匹配即失败关闭：抛出结构化 PolicyNotFoundException（POLICY_NOT_FOUND），绝不回退到通用政策；
 *   未受理的问题类型（如 REFUND）保持无政策覆盖，留给后续升级（人工升级/基线指标）。
 */
@Service
public class DemoAfterSalesPolicyCatalogService {

    /** 每国家每版本的生效时间：v1 与 v2，两个时间点对全部国家同构。 */
    private static final Instant V1_EFFECTIVE = Instant.parse("2025-06-01T00:00:00Z");
    private static final Instant V2_EFFECTIVE = Instant.parse("2026-01-01T00:00:00Z");

    /** SHIPMENT_DELAY：未更新 ≥ 7 天，10% 延迟补偿券。 */
    private static final int DELAY_MINIMUM_INACTIVE_DAYS = 7;
    private static final BigDecimal DELAY_COMPENSATION_RATE = new BigDecimal("0.10");
    private static final String DELAY_ACTION_TYPE = "DELAY_COMPENSATION_COUPON";
    private static final String DELAY_SECTION = "4.2";
    private static final String DELAY_SUMMARY =
            "Paid orders with shipment inactivity of at least 7 days may receive a delay compensation coupon.";

    /** LOST_IN_TRANSIT：未更新 ≥ 7 天且承运商确认丢失，全额退款（rate 1.00）。 */
    private static final int LOST_MINIMUM_INACTIVE_DAYS = 7;
    private static final BigDecimal LOST_COMPENSATION_RATE = new BigDecimal("1.00");
    private static final String LOST_ACTION_TYPE = "LOST_PARCEL_REFUND";
    private static final String LOST_SECTION = "5.2";
    private static final String LOST_SUMMARY =
            "Paid orders confirmed lost in transit by the carrier may be refunded in full.";

    /** DAMAGED_ITEM：交付签收 + 破损照片核验通过，30% 破损补偿券（无未更新天数阈值）。 */
    private static final int DAMAGE_MINIMUM_INACTIVE_DAYS = 0;
    private static final BigDecimal DAMAGE_COMPENSATION_RATE = new BigDecimal("0.30");
    private static final String DAMAGE_ACTION_TYPE = "DAMAGE_COMPENSATION_COUPON";
    private static final String DAMAGE_SECTION = "6.3";
    private static final String DAMAGE_SUMMARY =
            "Paid orders with a verified damage photo after delivery may receive a damage compensation coupon.";

    /** 不可变政策行；policyId/版本/国家/生效时间固定，目录发布后永不变化。 */
    private record PolicyRow(
            String policyId,
            String version,
            String country,
            String issueType,
            Instant effectiveFrom,
            int minimumInactiveDays,
            BigDecimal compensationRate,
            BigDecimal maximumCompensation,
            String actionType,
            String section,
            String summary) {

        /** 稳定证据 ID：policy ID + 版本 + 章节，跨 run 可复现。 */
        AfterSalesTypes.PolicyEvidence toEvidence() {
            return new AfterSalesTypes.PolicyEvidence(
                    "policy:" + policyId + ":" + version + "#section-" + section,
                    policyId, version, country, issueType, effectiveFrom,
                    minimumInactiveDays, compensationRate, maximumCompensation,
                    actionType, section, summary);
        }
    }

    private static final List<PolicyRow> CATALOG = List.of(
            row("SG_SHIPMENT_DELAY", "v1", "SG", "SHIPMENT_DELAY", V1_EFFECTIVE, "25.00"),
            row("SG_SHIPMENT_DELAY", "v2", "SG", "SHIPMENT_DELAY", V2_EFFECTIVE, "25.00"),
            row("MY_SHIPMENT_DELAY", "v1", "MY", "SHIPMENT_DELAY", V1_EFFECTIVE, "25.00"),
            row("MY_SHIPMENT_DELAY", "v2", "MY", "SHIPMENT_DELAY", V2_EFFECTIVE, "25.00"),
            row("TH_SHIPMENT_DELAY", "v1", "TH", "SHIPMENT_DELAY", V1_EFFECTIVE, "250.00"),
            row("TH_SHIPMENT_DELAY", "v2", "TH", "SHIPMENT_DELAY", V2_EFFECTIVE, "250.00"),
            row("ID_SHIPMENT_DELAY", "v1", "ID", "SHIPMENT_DELAY", V1_EFFECTIVE, "250000.00"),
            row("ID_SHIPMENT_DELAY", "v2", "ID", "SHIPMENT_DELAY", V2_EFFECTIVE, "250000.00"),
            row("VN_SHIPMENT_DELAY", "v2", "VN", "SHIPMENT_DELAY", V1_EFFECTIVE, "150000.00"),
            row("VN_SHIPMENT_DELAY", "v3", "VN", "SHIPMENT_DELAY", V2_EFFECTIVE, "150000.00"),

            row("SG_LOST_IN_TRANSIT", "v1", "SG", "LOST_IN_TRANSIT", V1_EFFECTIVE, "25.00"),
            row("SG_LOST_IN_TRANSIT", "v2", "SG", "LOST_IN_TRANSIT", V2_EFFECTIVE, "25.00"),
            row("MY_LOST_IN_TRANSIT", "v1", "MY", "LOST_IN_TRANSIT", V1_EFFECTIVE, "25.00"),
            row("MY_LOST_IN_TRANSIT", "v2", "MY", "LOST_IN_TRANSIT", V2_EFFECTIVE, "25.00"),
            row("TH_LOST_IN_TRANSIT", "v1", "TH", "LOST_IN_TRANSIT", V1_EFFECTIVE, "250.00"),
            row("TH_LOST_IN_TRANSIT", "v2", "TH", "LOST_IN_TRANSIT", V2_EFFECTIVE, "250.00"),
            row("ID_LOST_IN_TRANSIT", "v1", "ID", "LOST_IN_TRANSIT", V1_EFFECTIVE, "250000.00"),
            row("ID_LOST_IN_TRANSIT", "v2", "ID", "LOST_IN_TRANSIT", V2_EFFECTIVE, "250000.00"),
            row("VN_LOST_IN_TRANSIT", "v2", "VN", "LOST_IN_TRANSIT", V1_EFFECTIVE, "150000.00"),
            row("VN_LOST_IN_TRANSIT", "v3", "VN", "LOST_IN_TRANSIT", V2_EFFECTIVE, "150000.00"),

            row("SG_DAMAGED_ITEM", "v1", "SG", "DAMAGED_ITEM", V1_EFFECTIVE, "25.00"),
            row("SG_DAMAGED_ITEM", "v2", "SG", "DAMAGED_ITEM", V2_EFFECTIVE, "25.00"),
            row("MY_DAMAGED_ITEM", "v1", "MY", "DAMAGED_ITEM", V1_EFFECTIVE, "25.00"),
            row("MY_DAMAGED_ITEM", "v2", "MY", "DAMAGED_ITEM", V2_EFFECTIVE, "25.00"),
            row("TH_DAMAGED_ITEM", "v1", "TH", "DAMAGED_ITEM", V1_EFFECTIVE, "250.00"),
            row("TH_DAMAGED_ITEM", "v2", "TH", "DAMAGED_ITEM", V2_EFFECTIVE, "250.00"),
            row("ID_DAMAGED_ITEM", "v1", "ID", "DAMAGED_ITEM", V1_EFFECTIVE, "250000.00"),
            row("ID_DAMAGED_ITEM", "v2", "ID", "DAMAGED_ITEM", V2_EFFECTIVE, "250000.00"),
            row("VN_DAMAGED_ITEM", "v2", "VN", "DAMAGED_ITEM", V1_EFFECTIVE, "150000.00"),
            row("VN_DAMAGED_ITEM", "v3", "VN", "DAMAGED_ITEM", V2_EFFECTIVE, "150000.00")
    );

    private static PolicyRow row(String policyId, String version, String country,
                                 String issueType, Instant effectiveFrom, String maximumCompensation) {
        return new PolicyRow(
                policyId, version, country, issueType, effectiveFrom,
                minimumInactiveDays(issueType), compensationRate(issueType),
                new BigDecimal(maximumCompensation), actionType(issueType), section(issueType),
                summary(issueType));
    }

    private static int minimumInactiveDays(String issueType) {
        return switch (issueType) {
            case AfterSalesTypes.IntakeResult.LOST_IN_TRANSIT -> LOST_MINIMUM_INACTIVE_DAYS;
            case AfterSalesTypes.IntakeResult.DAMAGED_ITEM -> DAMAGE_MINIMUM_INACTIVE_DAYS;
            default -> DELAY_MINIMUM_INACTIVE_DAYS;
        };
    }

    private static BigDecimal compensationRate(String issueType) {
        return switch (issueType) {
            case AfterSalesTypes.IntakeResult.LOST_IN_TRANSIT -> LOST_COMPENSATION_RATE;
            case AfterSalesTypes.IntakeResult.DAMAGED_ITEM -> DAMAGE_COMPENSATION_RATE;
            default -> DELAY_COMPENSATION_RATE;
        };
    }

    private static String actionType(String issueType) {
        return switch (issueType) {
            case AfterSalesTypes.IntakeResult.LOST_IN_TRANSIT -> LOST_ACTION_TYPE;
            case AfterSalesTypes.IntakeResult.DAMAGED_ITEM -> DAMAGE_ACTION_TYPE;
            default -> DELAY_ACTION_TYPE;
        };
    }

    private static String section(String issueType) {
        return switch (issueType) {
            case AfterSalesTypes.IntakeResult.LOST_IN_TRANSIT -> LOST_SECTION;
            case AfterSalesTypes.IntakeResult.DAMAGED_ITEM -> DAMAGE_SECTION;
            default -> DELAY_SECTION;
        };
    }

    private static String summary(String issueType) {
        return switch (issueType) {
            case AfterSalesTypes.IntakeResult.LOST_IN_TRANSIT -> LOST_SUMMARY;
            case AfterSalesTypes.IntakeResult.DAMAGED_ITEM -> DAMAGE_SUMMARY;
            default -> DELAY_SUMMARY;
        };
    }

    /**
     * 按可信键查找：occurredAt 时刻已生效（effectiveFrom 不晚于 occurredAt）的政策中取最新版本；
     * 无匹配（国家/问题类型未覆盖，或发生时间早于最早版本）→ 结构化 POLICY_NOT_FOUND。
     */
    public AfterSalesTypes.PolicyEvidence lookup(String country, String issueType, Instant occurredAt) {
        if (occurredAt == null) {
            throw new PolicyNotFoundException(country, issueType, null);
        }
        Optional<PolicyRow> latest = CATALOG.stream()
                .filter(row -> row.country().equals(country))
                .filter(row -> row.issueType().equals(issueType))
                .filter(row -> !row.effectiveFrom().isAfter(occurredAt))
                .max(Comparator.comparing(PolicyRow::effectiveFrom));
        if (latest.isEmpty()) {
            throw new PolicyNotFoundException(country, issueType, occurredAt);
        }
        return latest.get().toEvidence();
    }
}
