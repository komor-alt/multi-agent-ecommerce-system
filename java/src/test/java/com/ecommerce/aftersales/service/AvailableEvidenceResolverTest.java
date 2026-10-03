package com.ecommerce.aftersales.service;

import com.ecommerce.aftersales.model.AfterSalesTypes.EvidenceType;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class AvailableEvidenceResolverTest {
    private final AvailableEvidenceResolver resolver = new AvailableEvidenceResolver();

    @Test
    void lostRouteOffersIndependentPolicyButRequiresShipmentForCarrierCase() {
        var graph = DecisionRouteResolver.LOST_IN_TRANSIT_EVIDENCE;
        assertThat(resolver.resolve(graph, Map.of())).containsExactly(EvidenceType.ORDER);
        assertThat(resolver.resolve(graph, Map.of("ORDER", true)))
                .containsExactly(EvidenceType.SHIPMENT, EvidenceType.POLICY);
        assertThat(resolver.resolve(graph, Map.of("ORDER", true, "SHIPMENT", true)))
                .containsExactly(EvidenceType.CARRIER_CASE, EvidenceType.POLICY);
        assertThat(resolver.resolve(graph, Map.of("ORDER", true, "POLICY", true)))
                .containsExactly(EvidenceType.SHIPMENT);
    }

    @Test
    void incompleteAndMalformedStateCannotClaimReady() {
        assertThat(resolver.resolve(List.of("ORDER", "UNKNOWN"), Map.of("ORDER", true))).isEmpty();
        assertThat(resolver.resolve(List.of("CARRIER_CASE"), Map.of())).isEmpty();
        assertThat(new EvidencePreconditionGate().validate(EvidenceType.READY_FOR_DECISION,
                List.of("UNKNOWN"), Map.of("UNKNOWN", true)).passed()).isFalse();
    }
}
