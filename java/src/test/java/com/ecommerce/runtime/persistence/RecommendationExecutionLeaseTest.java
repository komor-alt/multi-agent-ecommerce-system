package com.ecommerce.runtime.persistence;

import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@DataJpaTest
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import({RecommendationRuntimeStore.class, RecommendationRunEventService.class,
        RecommendationRuntimeIntegrityIntegrationTest.JacksonConfiguration.class})
class RecommendationExecutionLeaseTest extends RecommendationExecutionLeaseContract {
}
