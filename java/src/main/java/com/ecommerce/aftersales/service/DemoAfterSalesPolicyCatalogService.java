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
 * 只覆盖 DemoFulfillmentDataFactory 订单中出现的 SEA 国家（SG/MY/TH/ID/VN）与 SHIPMENT_DELAY
 * 问题类型；数据是演示政策，不代表任何真实法律。SG/MY/TH/ID 每个国家两条版本（v1 于 2025-06-01
 * 生效，v2 于 2026-01-01 生效）；VN 保持既有当前版本 v3（2026-01-01 生效，证据 ID 不变），
 * 历史版本 v2 于 2025-06-01 生效。阈值/比例/动作类型同构，补偿上限按订单币种取值。
 *
 * 查找规则：
 * - 只接受可信 country、issueType 与 ticket occurredAt；
 * - 在 occurredAt 之前（含当天）已生效的政策中取生效时间最新的版本；
 * - 无匹配即失败关闭：抛出结构化 PolicyNotFoundException（POLICY_NOT_FOUND），绝不回退到通用政策。
 */
@Service
public class DemoAfterSalesPolicyCatalogService {

    /** 每国家每版本的生效时间：v1 与 v2，两个时间点对全部国家同构。 */
    private static final Instant V1_EFFECTIVE = Instant.parse("2025-06-01T00:00:00Z");
    private static final Instant V2_EFFECTIVE = Instant.parse("2026-01-01T00:00:00Z");

    private static final int MINIMUM_INACTIVE_DAYS = 7;
    private static final BigDecimal COMPENSATION_RATE = new BigDecimal("0.10");
    private static final String ACTION_TYPE = "DELAY_COMPENSATION_COUPON";
    private static final String SECTION = "4.2";
    private static final String SUMMARY =
            "Paid orders with shipment inactivity of at least 7 days may receive a delay compensation coupon.";

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
            row("SG_SHIPMENT_DELAY", "v1", "SG", V1_EFFECTIVE, "25.00"),
            row("SG_SHIPMENT_DELAY", "v2", "SG", V2_EFFECTIVE, "25.00"),
            row("MY_SHIPMENT_DELAY", "v1", "MY", V1_EFFECTIVE, "25.00"),
            row("MY_SHIPMENT_DELAY", "v2", "MY", V2_EFFECTIVE, "25.00"),
            row("TH_SHIPMENT_DELAY", "v1", "TH", V1_EFFECTIVE, "250.00"),
            row("TH_SHIPMENT_DELAY", "v2", "TH", V2_EFFECTIVE, "250.00"),
            row("ID_SHIPMENT_DELAY", "v1", "ID", V1_EFFECTIVE, "250000.00"),
            row("ID_SHIPMENT_DELAY", "v2", "ID", V2_EFFECTIVE, "250000.00"),
            row("VN_SHIPMENT_DELAY", "v2", "VN", V1_EFFECTIVE, "150000.00"),
            row("VN_SHIPMENT_DELAY", "v3", "VN", V2_EFFECTIVE, "150000.00")
    );

    private static PolicyRow row(String policyId, String version, String country,
                                 Instant effectiveFrom, String maximumCompensation) {
        return new PolicyRow(
                policyId, version, country, AfterSalesTypes.IntakeResult.SHIPMENT_DELAY,
                effectiveFrom, MINIMUM_INACTIVE_DAYS, COMPENSATION_RATE,
                new BigDecimal(maximumCompensation), ACTION_TYPE, SECTION, SUMMARY);
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
