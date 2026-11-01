package com.ecommerce.eval.live;

import com.ecommerce.data.CatalogSeedInitializer;
import com.ecommerce.data.RecommendationDataService;
import com.ecommerce.data.SemanticProductSearchService;
import com.ecommerce.data.entity.RecInventoryEntity;
import com.ecommerce.data.entity.RecOrderEntity;
import com.ecommerce.data.entity.RecProductEntity;
import com.ecommerce.data.entity.RecUserEntity;
import com.ecommerce.data.entity.RecUserEventEntity;
import com.ecommerce.data.repository.RecInventoryRepository;
import com.ecommerce.data.repository.RecOrderRepository;
import com.ecommerce.data.repository.RecProductRepository;
import com.ecommerce.data.repository.RecUserEventRepository;
import com.ecommerce.data.repository.RecUserRepository;
import com.ecommerce.eval.benchmark.AgentBenchmarkDataset;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.context.ConfigurableApplicationContext;

import java.time.Duration;
import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/** Explicit, idempotent fixtures imported only after isolation and embedding guards pass. */
final class LiveBenchmarkFixtureSeeder {
    static final Clock FIXTURE_CLOCK = Clock.fixed(Instant.parse("2026-10-30T00:00:00Z"),
            java.time.ZoneOffset.UTC);
    static final Instant FIXTURE_NOW = FIXTURE_CLOCK.instant();
    private static final String EVENT_METADATA = "{\"source\":\"live_benchmark_fixture\"}";

    private LiveBenchmarkFixtureSeeder() { }

    static void seed(ConfigurableApplicationContext app, List<AgentBenchmarkDataset.Case> cases) {
        if (cases == null || cases.isEmpty()) {
            throw new IllegalArgumentException("LIVE_BENCHMARK_FIXTURE: selected cases are required");
        }
        if (app.getEnvironment().getProperty("agent.demo.seed-enabled", Boolean.class, true)) {
            throw new IllegalStateException("LIVE_BENCHMARK_FIXTURE: automatic demo seed must remain disabled");
        }
        if (app.getEnvironment().getProperty("agent.embedding.live-enabled", Boolean.class, true)) {
            throw new IllegalStateException("LIVE_BENCHMARK_FIXTURE: live embedding must remain disabled");
        }
        if (app.getEnvironment().getProperty("agent.embedding.max-calls", Integer.class, -1) != 0) {
            throw new IllegalStateException("LIVE_BENCHMARK_FIXTURE: embedding budget must be zero");
        }
        RecProductRepository products = app.getBean(RecProductRepository.class);
        RecInventoryRepository inventory = app.getBean(RecInventoryRepository.class);
        RecOrderRepository orders = app.getBean(RecOrderRepository.class);
        RecUserRepository users = app.getBean(RecUserRepository.class);
        RecUserEventRepository events = app.getBean(RecUserEventRepository.class);
        new CatalogSeedInitializer(
                products, inventory, orders, users, events,
                app.getBean(RecommendationDataService.class),
                app.getBean(SemanticProductSearchService.class))
                .run(new DefaultApplicationArguments(new String[0]));

        // The catalog initializer also inserts unrelated demo personas and recent events.
        // The isolated benchmark schema should contain only the selected dataset's user context.
        events.deleteAll();
        orders.deleteAll();
        users.deleteAll();

        for (AgentBenchmarkDataset.Case benchmarkCase : cases) {
            RecProductEntity product = eligibleProducts(products, inventory, benchmarkCase).stream()
                    .min(Comparator.comparing(RecProductEntity::getProductId))
                    .orElseThrow(() -> new IllegalStateException(
                            "LIVE_BENCHMARK_FIXTURE: no existing sellable product for " + benchmarkCase.id()));
            seedUser(users, benchmarkCase);
            seedOrder(orders, benchmarkCase, product);
            seedEvents(events, benchmarkCase, product);
        }
        verify(app, cases);
    }

    static void verify(ConfigurableApplicationContext app, List<AgentBenchmarkDataset.Case> cases) {
        RecUserRepository users = app.getBean(RecUserRepository.class);
        RecProductRepository products = app.getBean(RecProductRepository.class);
        RecInventoryRepository inventory = app.getBean(RecInventoryRepository.class);
        RecOrderRepository orders = app.getBean(RecOrderRepository.class);
        RecUserEventRepository events = app.getBean(RecUserEventRepository.class);
        RecommendationDataService data = app.getBean(RecommendationDataService.class);

        for (AgentBenchmarkDataset.Case benchmarkCase : cases) {
            RecUserEntity user = users.findByUserId(benchmarkCase.userId())
                    .orElseThrow(() -> missing(benchmarkCase, "user"));
            requireMarket(user, benchmarkCase);

            List<RecProductEntity> eligible = eligibleProducts(products, inventory, benchmarkCase);
            if (eligible.isEmpty()) throw missing(benchmarkCase, "sellable market product/inventory");

            String orderId = orderId(benchmarkCase);
            RecOrderEntity order = orders.findByOrderId(orderId)
                    .orElseThrow(() -> missing(benchmarkCase, "order"));
            if (!benchmarkCase.userId().equals(order.getUserId())
                    || !benchmarkCase.country().equals(order.getCountry())
                    || !benchmarkCase.currency().equals(order.getCurrency())
                    || !benchmarkCase.platform().equals(order.getPlatform())
                    || !"paid".equals(order.getPaymentStatus())
                    || !"delivered".equals(order.getFulfillmentStatus())
                    || order.getCreatedAt() == null
                    || order.getCreatedAt().isBefore(FIXTURE_NOW.minus(Duration.ofDays(30)))
                    || order.getCreatedAt().isAfter(FIXTURE_NOW)) {
                throw missing(benchmarkCase, "consistent recent order");
            }
            String associatedProductId = order.getProductIds().split(",", 2)[0];
            RecProductEntity associated = products.findByProductId(associatedProductId)
                    .orElseThrow(() -> missing(benchmarkCase, "order product"));
            RecInventoryEntity stock = inventory.findByProductIdAndCountry(
                            associatedProductId, benchmarkCase.country())
                    .orElseThrow(() -> missing(benchmarkCase, "order product market inventory"));
            if (!marketEligible(associated, stock, benchmarkCase)) {
                throw missing(benchmarkCase, "order product with valid market inventory");
            }
            boolean orderVisible = data.orderContext(benchmarkCase.userId()).stream()
                    .anyMatch(row -> orderId.equals(row.get("order_id")));
            if (!orderVisible) throw missing(benchmarkCase, "recent order query visibility");

            List<RecUserEventEntity> userEvents = events.findTop20ByUserIdOrderByCreatedAtDesc(
                    benchmarkCase.userId());
            if (userEvents.size() < 2 || userEvents.stream().anyMatch(event -> event.getCreatedAt() == null
                    || event.getCreatedAt().isAfter(FIXTURE_NOW))) {
                throw missing(benchmarkCase, "deterministic behavior events");
            }
        }
    }

    private static List<RecProductEntity> eligibleProducts(RecProductRepository products,
                                                            RecInventoryRepository inventory,
                                                            AgentBenchmarkDataset.Case benchmarkCase) {
        return products.findByMarket(benchmarkCase.platform(), benchmarkCase.country(),
                        benchmarkCase.region(), benchmarkCase.currency()).stream()
                .filter(product -> inventory.findByProductIdAndCountry(
                                product.getProductId(), benchmarkCase.country())
                        .filter(row -> marketEligible(product, row, benchmarkCase)).isPresent())
                .sorted(Comparator.comparing(RecProductEntity::getProductId))
                .toList();
    }

    private static boolean marketEligible(RecProductEntity product, RecInventoryEntity row,
                                         AgentBenchmarkDataset.Case benchmarkCase) {
        String fulfillment = row.getFulfillmentStatus();
        return benchmarkCase.platform().equalsIgnoreCase(product.getPlatform())
                && benchmarkCase.currency().equalsIgnoreCase(product.getCurrency())
                && product.isCrossBorderEligible()
                && product.getStock() > 0
                && RecommendationDataService.split(product.getSupportedCountries()).stream()
                .anyMatch(country -> "SEA".equalsIgnoreCase(country)
                        || benchmarkCase.country().equalsIgnoreCase(country))
                && row.getStock() > 0
                && !row.isRestricted()
                && (fulfillment == null || !List.of("out_of_stock", "cancelled", "inactive", "restricted")
                .contains(fulfillment.toLowerCase()));
    }

    private static void seedUser(RecUserRepository users, AgentBenchmarkDataset.Case benchmarkCase) {
        var existing = users.findByUserId(benchmarkCase.userId());
        if (existing.isPresent()) {
            requireMarket(existing.get(), benchmarkCase);
            return;
        }
        users.save(RecUserEntity.builder()
                .userId(benchmarkCase.userId())
                .email(benchmarkCase.userId().toLowerCase() + "@benchmark.invalid")
                .name(benchmarkCase.userId())
                .region(benchmarkCase.region())
                .country(benchmarkCase.country())
                .currency(benchmarkCase.currency())
                .locale(benchmarkCase.locale())
                .platform(benchmarkCase.platform())
                .createdAt(FIXTURE_NOW.minus(Duration.ofDays(45)))
                .updatedAt(FIXTURE_NOW.minus(Duration.ofDays(45)))
                .build());
    }

    private static void seedOrder(RecOrderRepository orders, AgentBenchmarkDataset.Case benchmarkCase,
                                  RecProductEntity product) {
        String orderId = orderId(benchmarkCase);
        var existing = orders.findByOrderId(orderId);
        if (existing.isPresent()) {
            RecOrderEntity order = existing.get();
            if (!benchmarkCase.userId().equals(order.getUserId())
                    || !product.getProductId().equals(order.getProductIds())
                    || !benchmarkCase.country().equals(order.getCountry())
                    || !benchmarkCase.currency().equals(order.getCurrency())) {
                throw new IllegalStateException("LIVE_BENCHMARK_FIXTURE: conflicting deterministic order " + orderId);
            }
            return;
        }
        orders.save(RecOrderEntity.builder()
                .id("live-order-" + benchmarkCase.id())
                .orderId(orderId)
                .userId(benchmarkCase.userId())
                .platform(benchmarkCase.platform())
                .country(benchmarkCase.country())
                .currency(benchmarkCase.currency())
                .warehouseRegion(product.getWarehouseRegion())
                .productIds(product.getProductId())
                .orderValue(product.getPrice())
                .paymentStatus("paid")
                .fulfillmentStatus("delivered")
                .promisedDeliveryDays(product.getDeliveryDays())
                .riskLevel("low")
                .createdAt(FIXTURE_NOW.minus(Duration.ofDays(14)))
                .updatedAt(FIXTURE_NOW.minus(Duration.ofDays(14)))
                .build());
    }

    private static void seedEvents(RecUserEventRepository events, AgentBenchmarkDataset.Case benchmarkCase,
                                   RecProductEntity product) {
        seedEvent(events, benchmarkCase, product, "view", "view", FIXTURE_NOW.minus(Duration.ofDays(7)));
        seedEvent(events, benchmarkCase, product, "cart", "cart", FIXTURE_NOW.minus(Duration.ofDays(1)));
    }

    private static void seedEvent(RecUserEventRepository events, AgentBenchmarkDataset.Case benchmarkCase,
                                  RecProductEntity product, String suffix, String behavior, Instant createdAt) {
        String id = "live-event-" + benchmarkCase.id() + "-" + suffix;
        var existing = events.findById(id);
        if (existing.isPresent()) {
            RecUserEventEntity event = existing.get();
            if (!benchmarkCase.userId().equals(event.getUserId())
                    || !product.getProductId().equals(event.getProductId())
                    || !behavior.equals(event.getBehaviorType()) || !createdAt.equals(event.getCreatedAt())) {
                throw new IllegalStateException("LIVE_BENCHMARK_FIXTURE: conflicting deterministic event " + id);
            }
            return;
        }
        events.save(RecUserEventEntity.builder()
                .id(id)
                .userId(benchmarkCase.userId())
                .behaviorType(behavior)
                .productId(product.getProductId())
                .metadataJson(EVENT_METADATA)
                .createdAt(createdAt)
                .build());
    }

    private static void requireMarket(RecUserEntity user, AgentBenchmarkDataset.Case benchmarkCase) {
        if (!benchmarkCase.region().equals(user.getRegion())
                || !benchmarkCase.country().equals(user.getCountry())
                || !benchmarkCase.currency().equals(user.getCurrency())
                || !benchmarkCase.locale().equals(user.getLocale())
                || !benchmarkCase.platform().equals(user.getPlatform())) {
            throw new IllegalStateException("LIVE_BENCHMARK_FIXTURE: conflicting user market for "
                    + benchmarkCase.id());
        }
    }

    private static String orderId(AgentBenchmarkDataset.Case benchmarkCase) {
        return "LIVE-" + benchmarkCase.id();
    }

    private static IllegalStateException missing(AgentBenchmarkDataset.Case benchmarkCase, String item) {
        return new IllegalStateException("LIVE_BENCHMARK_FIXTURE: " + item + " missing for " + benchmarkCase.id());
    }
}
