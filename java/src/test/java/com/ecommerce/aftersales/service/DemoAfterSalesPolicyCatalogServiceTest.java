package com.ecommerce.aftersales.service;

import com.ecommerce.aftersales.model.AfterSalesTypes;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DemoAfterSalesPolicyCatalogServiceTest {

    private final DemoAfterSalesPolicyCatalogService catalog = new DemoAfterSalesPolicyCatalogService();

    private static final Instant AFTER_V2 = Instant.parse("2026-08-14T00:00:00Z");
    private static final Instant BETWEEN_VERSIONS = Instant.parse("2025-10-01T00:00:00Z");
    private static final Instant BEFORE_V1 = Instant.parse("2025-01-01T00:00:00Z");

    @Test
    void resolvesIndonesiaTicketToLatestVersionWithStableEvidenceId() {
        AfterSalesTypes.PolicyEvidence policy =
                catalog.lookup("ID", "SHIPMENT_DELAY", AFTER_V2);

        assertThat(policy.policyId()).isEqualTo("ID_SHIPMENT_DELAY");
        assertThat(policy.version()).isEqualTo("v2");
        assertThat(policy.country()).isEqualTo("ID");
        assertThat(policy.issueType()).isEqualTo("SHIPMENT_DELAY");
        assertThat(policy.evidenceId()).isEqualTo("policy:ID_SHIPMENT_DELAY:v2#section-4.2");
        assertThat(policy.minimumInactiveDays()).isEqualTo(7);
        assertThat(policy.compensationRate()).isEqualByComparingTo("0.10");
        assertThat(policy.maximumCompensation()).isEqualByComparingTo("250000.00");
        assertThat(policy.actionType()).isEqualTo("DELAY_COMPENSATION_COUPON");
        assertThat(policy.section()).isEqualTo("4.2");
        assertThat(policy.summary()).isNotBlank();
    }

    @ParameterizedTest
    @CsvSource({
            "SG,SG_SHIPMENT_DELAY,v2,25.00",
            "MY,MY_SHIPMENT_DELAY,v2,25.00",
            "TH,TH_SHIPMENT_DELAY,v2,250.00",
            "ID,ID_SHIPMENT_DELAY,v2,250000.00",
            "VN,VN_SHIPMENT_DELAY,v3,150000.00"
    })
    void coversEveryDemoCountryWithCurrencyAppropriateCap(String country, String policyId, String version, String cap) {
        AfterSalesTypes.PolicyEvidence policy = catalog.lookup(country, "SHIPMENT_DELAY", AFTER_V2);

        assertThat(policy.policyId()).isEqualTo(policyId);
        assertThat(policy.country()).isEqualTo(country);
        assertThat(policy.version()).isEqualTo(version);
        assertThat(policy.evidenceId())
                .isEqualTo("policy:" + policyId + ":" + version + "#section-4.2");
        assertThat(policy.maximumCompensation()).isEqualByComparingTo(new BigDecimal(cap));
        // 演示政策，不代表真实法律；summary 不得写死国家。
        assertThat(policy.summary()).doesNotContain(country).doesNotContain("Vietnam");
    }

    @Test
    void selectsOlderVersionWhenOccurredBeforeNewerVersionEffectiveDate() {
        AfterSalesTypes.PolicyEvidence policy = catalog.lookup("SG", "SHIPMENT_DELAY", BETWEEN_VERSIONS);

        assertThat(policy.version()).isEqualTo("v1");
        assertThat(policy.evidenceId()).isEqualTo("policy:SG_SHIPMENT_DELAY:v1#section-4.2");
        assertThat(policy.effectiveFrom()).isBefore(BETWEEN_VERSIONS);
    }

    @Test
    void preservesEstablishedCurrentVietnamV3IdentityWithPriorHistoricalVersion() {
        // 兼容性：改版前目录对 VN 的既有当前版本是 v3（2026-01-01 生效），
        // 证据 ID policy:VN_SHIPMENT_DELAY:v3#section-4.2 必须保持不变。
        AfterSalesTypes.PolicyEvidence current = catalog.lookup("VN", "SHIPMENT_DELAY", AFTER_V2);

        assertThat(current.version()).isEqualTo("v3");
        assertThat(current.evidenceId()).isEqualTo("policy:VN_SHIPMENT_DELAY:v3#section-4.2");
        assertThat(current.effectiveFrom()).isEqualTo(Instant.parse("2026-01-01T00:00:00Z"));
        assertThat(current.maximumCompensation()).isEqualByComparingTo("150000.00");
        assertThat(current.compensationRate()).isEqualByComparingTo("0.10");

        // 生效日期选择仍可测：2026-01-01 之前命中历史版本 v2，证据 ID 随之携带 v2。
        AfterSalesTypes.PolicyEvidence historical = catalog.lookup("VN", "SHIPMENT_DELAY", BETWEEN_VERSIONS);
        assertThat(historical.version()).isEqualTo("v2");
        assertThat(historical.evidenceId()).isEqualTo("policy:VN_SHIPMENT_DELAY:v2#section-4.2");
        assertThat(historical.effectiveFrom()).isBefore(BETWEEN_VERSIONS);
    }

    @Test
    void failsClosedWhenOccurredBeforeEveryVersion() {
        assertThatThrownBy(() -> catalog.lookup("SG", "SHIPMENT_DELAY", BEFORE_V1))
                .isInstanceOf(PolicyNotFoundException.class)
                .hasMessage("POLICY_NOT_FOUND");
    }

    @Test
    void failsClosedForUnsupportedCountryWithStructuredError() {
        assertThatThrownBy(() -> catalog.lookup("KR", "SHIPMENT_DELAY", AFTER_V2))
                .isInstanceOfSatisfying(PolicyNotFoundException.class, error -> {
                    assertThat(error.getMessage()).isEqualTo("POLICY_NOT_FOUND");
                    assertThat(error.getCountry()).isEqualTo("KR");
                    assertThat(error.getIssueType()).isEqualTo("SHIPMENT_DELAY");
                    assertThat(error.getOccurredAt()).isEqualTo(AFTER_V2);
                });
    }

    @Test
    void failsClosedForUnsupportedIssueType() {
        assertThatThrownBy(() -> catalog.lookup("SG", "REFUND", AFTER_V2))
                .isInstanceOf(PolicyNotFoundException.class)
                .hasMessage("POLICY_NOT_FOUND");
    }

    @Test
    void failsClosedWhenOccurredAtIsMissing() {
        assertThatThrownBy(() -> catalog.lookup("SG", "SHIPMENT_DELAY", null))
                .isInstanceOf(PolicyNotFoundException.class)
                .hasMessage("POLICY_NOT_FOUND");
    }
}
