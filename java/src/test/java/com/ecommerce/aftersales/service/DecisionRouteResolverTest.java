package com.ecommerce.aftersales.service;

import com.ecommerce.aftersales.model.AfterSalesTypes;
import com.ecommerce.aftersales.model.DecisionRoute;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DecisionRouteResolverTest {

    private final DecisionRouteResolver resolver = new DecisionRouteResolver();

    private AfterSalesTypes.IntakeResult intake(List<String> intents, List<String> evidenceSuggestion) {
        return intake(intents, evidenceSuggestion, "SHIPMENT_DELAY");
    }

    private AfterSalesTypes.IntakeResult intake(List<String> intents, List<String> evidenceSuggestion,
                                                String issueType) {
        return new AfterSalesTypes.IntakeResult(
                issueType, intents, "LOW", Map.of(), List.of(),
                evidenceSuggestion, "LLM", null, 0L);
    }

    @Test
    void trackingOnlyIntentMapsToAnswerOnlyWithOrderAndShipment() {
        DecisionRouteResolver.RouteDecision decision =
                resolver.resolve(intake(List.of("TRACK_SHIPMENT"), List.of("ORDER", "SHIPMENT", "POLICY")));

        assertThat(decision.route()).isEqualTo(DecisionRoute.ANSWER_ONLY);
        assertThat(decision.requiredEvidence()).containsExactly("ORDER", "SHIPMENT");
    }

    @Test
    void refundIntentMapsToCompensationEvaluationWithFullEvidence() {
        DecisionRouteResolver.RouteDecision decision = resolver.resolve(
                intake(List.of("TRACK_SHIPMENT", "REQUEST_REFUND"), List.of("ORDER", "SHIPMENT")));

        assertThat(decision.route()).isEqualTo(DecisionRoute.COMPENSATION_EVALUATION);
        assertThat(decision.requiredEvidence()).containsExactly("ORDER", "SHIPMENT", "POLICY");
    }

    @Test
    void modelEvidenceSuggestionCannotRaiseAnswerOnlyRequirements() {
        // 模型建议试图抬高到全量（含 POLICY）：ANSWER_ONLY 路线要求保持 ORDER+SHIPMENT 不变。
        DecisionRouteResolver.RouteDecision decision = resolver.resolve(
                intake(List.of("TRACK_SHIPMENT"), List.of("ORDER", "SHIPMENT", "POLICY")));

        assertThat(decision.route()).isEqualTo(DecisionRoute.ANSWER_ONLY);
        assertThat(decision.requiredEvidence()).containsExactly("ORDER", "SHIPMENT");
    }

    @Test
    void modelEvidenceSuggestionCannotLowerCompensationRequirements() {
        // 模型建议只给 SHIPMENT：COMPENSATION_EVALUATION 路线要求保持全量三份不变。
        DecisionRouteResolver.RouteDecision decision = resolver.resolve(
                intake(List.of("TRACK_SHIPMENT", "REQUEST_REFUND"), List.of("SHIPMENT")));

        assertThat(decision.route()).isEqualTo(DecisionRoute.COMPENSATION_EVALUATION);
        assertThat(decision.requiredEvidence()).containsExactly("ORDER", "SHIPMENT", "POLICY");
    }

    @Test
    void unknownEvidenceSuggestionIsIgnored() {
        // 未知证据名（INVOICE）与空建议都不影响路线证据重建。
        DecisionRouteResolver.RouteDecision withUnknown = resolver.resolve(
                intake(List.of("TRACK_SHIPMENT"), List.of("ORDER", "INVOICE")));
        assertThat(withUnknown.route()).isEqualTo(DecisionRoute.ANSWER_ONLY);
        assertThat(withUnknown.requiredEvidence()).containsExactly("ORDER", "SHIPMENT");

        DecisionRouteResolver.RouteDecision empty = resolver.resolve(
                intake(List.of("TRACK_SHIPMENT"), List.of()));
        assertThat(empty.requiredEvidence()).containsExactly("ORDER", "SHIPMENT");
    }

    @Test
    void unknownIntentStillDefaultsToAnswerOnlyAndMvpNeverEmitsEscalationRoutes() {
        // 白名单外意图（如模型试图 EXECUTE_REFUND）默认 ANSWER_ONLY；
        // MVP 解析器绝不产生 REQUEST_MORE_INFO / HUMAN_ESCALATION。
        DecisionRouteResolver.RouteDecision decision = resolver.resolve(
                intake(List.of("EXECUTE_REFUND"), List.of("ORDER", "SHIPMENT", "POLICY")));

        assertThat(decision.route()).isEqualTo(DecisionRoute.ANSWER_ONLY);
        assertThat(decision.requiredEvidence()).containsExactly("ORDER", "SHIPMENT");
    }

    @Test
    void lostInTransitRefundMapsToCompensationWithCarrierCaseGraph() {
        DecisionRouteResolver.RouteDecision decision = resolver.resolve(
                intake(List.of("TRACK_SHIPMENT", "REQUEST_REFUND"), List.of("ORDER", "SHIPMENT", "POLICY"),
                        "LOST_IN_TRANSIT"));

        assertThat(decision.route()).isEqualTo(DecisionRoute.COMPENSATION_EVALUATION);
        assertThat(decision.requiredEvidence()).containsExactly(
                "ORDER", "SHIPMENT", "CARRIER_CASE", "POLICY");
    }

    @Test
    void damagedItemRefundMapsToCompensationWithDeliveryGraph() {
        DecisionRouteResolver.RouteDecision decision = resolver.resolve(
                intake(List.of("TRACK_SHIPMENT", "REQUEST_REFUND"), List.of("ORDER", "SHIPMENT", "POLICY"),
                        "DAMAGED_ITEM"));

        assertThat(decision.route()).isEqualTo(DecisionRoute.COMPENSATION_EVALUATION);
        assertThat(decision.requiredEvidence()).containsExactly(
                "ORDER", "DELIVERY", "DAMAGE_PHOTO", "PRODUCT", "POLICY");
    }

    @Test
    void damagedItemTrackingOnlyMapsToAnswerOnlyWithOrderAndDelivery() {
        // DAMAGED_ITEM 证据图没有 SHIPMENT 节点：纯查询路线只取证 ORDER+DELIVERY（交付证明答复）。
        DecisionRouteResolver.RouteDecision decision = resolver.resolve(
                intake(List.of("TRACK_SHIPMENT"), List.of("ORDER", "SHIPMENT"), "DAMAGED_ITEM"));

        assertThat(decision.route()).isEqualTo(DecisionRoute.ANSWER_ONLY);
        assertThat(decision.requiredEvidence()).containsExactly("ORDER", "DELIVERY");
    }

    @Test
    void lostInTransitTrackingOnlyMapsToAnswerOnlyWithOrderAndShipment() {
        DecisionRouteResolver.RouteDecision decision = resolver.resolve(
                intake(List.of("TRACK_SHIPMENT"), List.of("ORDER", "SHIPMENT"), "LOST_IN_TRANSIT"));

        assertThat(decision.route()).isEqualTo(DecisionRoute.ANSWER_ONLY);
        assertThat(decision.requiredEvidence()).containsExactly("ORDER", "SHIPMENT");
    }

    @Test
    void threeIssueTypeGraphsAreMutuallyDistinct() {
        // 三条补偿评估证据图必须互不相同，绝不存在一个全局固定的 ORDER/SHIPMENT/POLICY 顺序。
        List<String> delay = resolver.resolve(
                intake(List.of("REQUEST_REFUND"), List.of(), "SHIPMENT_DELAY")).requiredEvidence();
        List<String> lost = resolver.resolve(
                intake(List.of("REQUEST_REFUND"), List.of(), "LOST_IN_TRANSIT")).requiredEvidence();
        List<String> damaged = resolver.resolve(
                intake(List.of("REQUEST_REFUND"), List.of(), "DAMAGED_ITEM")).requiredEvidence();

        assertThat(delay).containsExactly("ORDER", "SHIPMENT", "POLICY");
        assertThat(lost).containsExactly("ORDER", "SHIPMENT", "CARRIER_CASE", "POLICY");
        assertThat(damaged).containsExactly("ORDER", "DELIVERY", "DAMAGE_PHOTO", "PRODUCT", "POLICY");
        assertThat(damaged).doesNotContain("SHIPMENT");
    }

    @Test
    void modelEvidenceSuggestionCannotAlterIssueTypeGraphs() {
        // 模型建议不能降低（漏 CARRIER_CASE）也不能抬高（补 INVOICE）LOST_IN_TRANSIT 路线要求。
        DecisionRouteResolver.RouteDecision lowered = resolver.resolve(
                intake(List.of("REQUEST_REFUND"), List.of("ORDER", "SHIPMENT", "POLICY"), "LOST_IN_TRANSIT"));
        assertThat(lowered.requiredEvidence()).containsExactly("ORDER", "SHIPMENT", "CARRIER_CASE", "POLICY");

        DecisionRouteResolver.RouteDecision raised = resolver.resolve(
                intake(List.of("REQUEST_REFUND"), List.of("ORDER", "SHIPMENT", "CARRIER_CASE", "POLICY", "INVOICE"),
                        "DAMAGED_ITEM"));
        assertThat(raised.requiredEvidence()).containsExactly("ORDER", "DELIVERY", "DAMAGE_PHOTO", "PRODUCT", "POLICY");
    }

    @Test
    void unknownIssueTypeStillDefaultsToShipmentDelayGraph() {
        // 未受理的问题类型（如 REFUND）走 SHIPMENT_DELAY 图默认分支，不给模型指定证据的机会。
        DecisionRouteResolver.RouteDecision decision = resolver.resolve(
                intake(List.of("REQUEST_REFUND"), List.of("ORDER", "SHIPMENT", "POLICY"), "REFUND"));

        assertThat(decision.route()).isEqualTo(DecisionRoute.COMPENSATION_EVALUATION);
        assertThat(decision.requiredEvidence()).containsExactly("ORDER", "SHIPMENT", "POLICY");
    }
}
