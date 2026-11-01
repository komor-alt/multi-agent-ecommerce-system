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

import java.time.Instant;
import java.util.List;

/** Explicit deterministic benchmark fixture import after all live embedding guards are verified. */
final class LiveBenchmarkFixtureSeeder {
    private LiveBenchmarkFixtureSeeder() { }

    static void seed(ConfigurableApplicationContext app) {
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
        seedExactSmokeFixtures(products, inventory, orders, users, events);
    }

    private static void seedExactSmokeFixtures(RecProductRepository products,
                                               RecInventoryRepository inventory,
                                               RecOrderRepository orders,
                                               RecUserRepository users,
                                               RecUserEventRepository events) {
        for (AgentBenchmarkDataset.Case benchmarkCase : AgentBenchmarkDataset.selectSmoke(
                AgentBenchmarkDataset.loadAll().cases())) {
            RecUserEntity user = users.findByUserId(benchmarkCase.userId()).orElseGet(() ->
                    RecUserEntity.builder()
                            .userId(benchmarkCase.userId())
                            .email(benchmarkCase.userId() + "@benchmark.invalid")
                            .name(benchmarkCase.userId())
                            .build());
            user.setRegion(benchmarkCase.region());
            user.setCountry(benchmarkCase.country());
            user.setCurrency(benchmarkCase.currency());
            user.setLocale(benchmarkCase.locale());
            user.setPlatform(benchmarkCase.platform());
            users.save(user);

            List<RecProductEntity> eligible = products.findByMarket(
                    benchmarkCase.platform(), benchmarkCase.country(), benchmarkCase.region(),
                    benchmarkCase.currency()).stream()
                    .filter(product -> inventory.findByProductIdAndCountry(
                                    product.getProductId(), benchmarkCase.country())
                            .filter(LiveBenchmarkFixtureSeeder::available).isPresent())
                    .toList();
            if (eligible.isEmpty()) {
                throw new IllegalStateException("LIVE_BENCHMARK_FIXTURE: no sellable product for "
                        + benchmarkCase.id());
            }
            RecProductEntity product = eligible.get(0);
            String orderId = "LIVE-" + benchmarkCase.id();
            if (orders.findByOrderId(orderId).isEmpty()) {
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
                        .build());
            }
            String eventId = "live-event-" + benchmarkCase.id();
            if (!events.existsById(eventId)) {
                events.save(RecUserEventEntity.builder()
                        .id(eventId)
                        .userId(benchmarkCase.userId())
                        .behaviorType("view")
                        .productId(product.getProductId())
                        .metadataJson("{\"source\":\"live_benchmark_fixture\"}")
                        .createdAt(Instant.parse("2026-10-30T00:00:00Z"))
                        .build());
            }
        }
    }

    private static boolean available(RecInventoryEntity row) {
        if (row.getStock() <= 0 || row.isRestricted()) return false;
        String status = row.getFulfillmentStatus();
        return status == null || (!"out_of_stock".equalsIgnoreCase(status)
                && !"cancelled".equalsIgnoreCase(status)
                && !"inactive".equalsIgnoreCase(status));
    }
}
