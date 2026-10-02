package com.ecommerce.runtime.persistence;

import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@DataJpaTest(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class RecommendationOutboxDispatcherTest extends RecommendationOutboxContract {
}
