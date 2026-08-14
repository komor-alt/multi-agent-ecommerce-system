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
        return new AfterSalesTypes.IntakeResult(
                "SHIPMENT_DELAY", intents, "LOW", Map.of(), List.of(),
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
}
