package com.ecommerce.aftersales.connector;

import com.ecommerce.config.ShopifyAfterSalesProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpTimeoutException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ShopifyGraphqlAfterSalesConnectorTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void queriesGrantedScopesAndMapsRealOrderAndFulfillmentFields() {
        FakeTransport transport = new FakeTransport()
                .respond(200, "{\"data\":{\"currentAppInstallation\":{\"accessScopes\":[{\"handle\":\"read_orders\"},{\"handle\":\"write_orders\"}]}}}")
                .respond(200, paidOrderByIdResponse())
                .respond(200, "{\"data\":{\"currentAppInstallation\":{\"accessScopes\":[{\"handle\":\"read_orders\"},{\"handle\":\"write_orders\"}]}}}")
                .respond(200, paidOrderByIdResponse());
        ShopifyGraphqlAfterSalesConnector connector = connector(transport, true);

        var order = connector.getOrder("gid://shopify/Order/12345");
        var shipment = connector.getShipment(order);

        assertThat(order.orderId()).isEqualTo("gid://shopify/Order/12345");
        assertThat(order.userId()).isEqualTo("gid://shopify/Customer/77");
        assertThat(order.country()).isEqualTo("SG");
        assertThat(order.currency()).isEqualTo("SGD");
        assertThat(order.paidAmount()).isEqualByComparingTo(new BigDecimal("120.50"));
        assertThat(order.paid()).isTrue();
        assertThat(order.fulfillmentStatus()).isEqualTo("fulfilled");
        assertThat(order.trackingNumber()).isEqualTo("TRACK-1");
        assertThat(shipment.status()).isEqualTo("delivered");
        assertThat(shipment.timeline()).hasSize(1);
        assertThat(shipment.timeline().get(0).location()).isEqualTo("Singapore, SG");
        assertThat(transport.requests).hasSize(4); // scope + order, then scope + fresh order read for sync
        assertThat(transport.requests.get(1).endpoint().toString())
                .isEqualTo("https://demo-store.myshopify.com/admin/api/2026-10/graphql.json");
        assertThat(transport.requests.get(1).variables()).containsEntry("id", "gid://shopify/Order/12345");
        assertThat(transport.requests).allSatisfy(request -> assertThat(request.accessToken()).isEqualTo("test-token-not-real"));
    }

    @Test
    void safelyResolvesNamesUsingGraphqlVariablesAndRejectsSearchInjection() {
        FakeTransport transport = new FakeTransport()
                .respond(200, "{\"data\":{\"currentAppInstallation\":{\"accessScopes\":[{\"handle\":\"read_orders\"}]}}}")
                .respond(200, paidOrderResponse("EN1001"));
        var connector = connector(transport, false);

        var order = connector.getOrder("name:EN1001");

        assertThat(order.orderId()).isEqualTo("name:EN1001");
        assertThat(transport.requests.get(1).variables()).containsEntry("search", "name:EN1001");
        assertThat(transport.requests.get(1).query()).contains("orders(first: 2, query: $search)");

        int requestsBeforeInvalidInput = transport.requests.size();
        assertThatThrownBy(() -> connector.getOrder("#1001) { refunds { id } }"))
                .isInstanceOf(ConnectorException.class).hasMessage("SHOPIFY_ORDER_ID_INVALID");
        assertThat(transport.requests).hasSize(requestsBeforeInvalidInput);
    }

    @Test
    void readOrdersIsRequiredAndReadAllOrdersIsReportedWithoutBeingAssumed() {
        FakeTransport missingRead = new FakeTransport().respond(200,
                "{\"data\":{\"currentAppInstallation\":{\"accessScopes\":[{\"handle\":\"read_all_orders\"}]}}}");
        assertThatThrownBy(() -> connector(missingRead, false).getOrder("#1001"))
                .isInstanceOf(ConnectorException.class).hasMessage("SHOPIFY_READ_ORDERS_SCOPE_MISSING");
        assertThat(missingRead.requests).hasSize(1);

        FakeTransport readAndHistory = new FakeTransport().respond(200,
                "{\"data\":{\"currentAppInstallation\":{\"accessScopes\":[{\"handle\":\"read_orders\"},{\"handle\":\"read_all_orders\"}]}}}");
        var status = connector(readAndHistory, false).verifyAccessScopes();
        assertThat(status.readOrders()).isTrue();
        assertThat(status.readAllOrders()).isTrue();
    }

    @Test
    void writeOrdersGrantAlsoProvidesEffectiveOrderReadCapability() {
        String writeOnlyScopes = "{\"data\":{\"currentAppInstallation\":{\"accessScopes\":[{\"handle\":\"write_orders\"}]}}}";
        FakeTransport transport = new FakeTransport()
                .respond(200, writeOnlyScopes)
                .respond(200, writeOnlyScopes)
                .respond(200, paidOrderResponse("#1001"));
        var connector = connector(transport, false);

        var status = connector.verifyAccessScopes();
        var order = connector.getOrder("#1001");

        assertThat(status.grantedReadOrders()).isFalse();
        assertThat(status.effectiveCanReadOrders()).isTrue();
        assertThat(status.readOrders()).isTrue();
        assertThat(status.writeOrders()).isTrue();
        assertThat(order.country()).isEqualTo("SG");
    }

    @Test
    void classifiesHttpGraphqlAndTimeoutFailuresWithoutSurfacingSensitivePayloads() {
        assertFailure(401, "", ConnectorException.Category.TERMINAL, "SHOPIFY_HTTP_AUTHORIZATION_FAILED");
        assertFailure(403, "", ConnectorException.Category.TERMINAL, "SHOPIFY_HTTP_AUTHORIZATION_FAILED");
        assertFailure(429, "private throttle payload", ConnectorException.Category.RETRYABLE, "SHOPIFY_HTTP_429");
        assertFailure(503, "private upstream payload", ConnectorException.Category.RETRYABLE, "SHOPIFY_HTTP_503");
        assertFailure(400, "private validation payload", ConnectorException.Category.TERMINAL, "SHOPIFY_HTTP_400");

        FakeTransport throttled = new FakeTransport().respond(200,
                "{\"errors\":[{\"message\":\"secret detail\",\"extensions\":{\"code\":\"THROTTLED\"}}]}");
        assertConnectorFailure(connector(throttled, false), ConnectorException.Category.RETRYABLE,
                "SHOPIFY_GRAPHQL_RETRYABLE_ERROR");
        assertThatThrownBy(() -> connector(throttled, false).verifyAccessScopes())
                .hasMessageNotContaining("secret detail");

        FakeTransport denied = new FakeTransport().respond(200,
                "{\"errors\":[{\"message\":\"private access detail\",\"extensions\":{\"code\":\"ACCESS_DENIED\"}}]}");
        assertConnectorFailure(connector(denied, false), ConnectorException.Category.TERMINAL,
                "SHOPIFY_GRAPHQL_ACCESS_DENIED");
        FakeTransport userErrors = new FakeTransport().respond(200,
                "{\"data\":{\"action\":{\"userErrors\":[{\"message\":\"sensitive validation text\"}]}}}");
        assertConnectorFailure(connector(userErrors, false), ConnectorException.Category.TERMINAL,
                "SHOPIFY_USER_ERROR");

        FakeTransport timeout = new FakeTransport().throwNext(new IllegalStateException(
                "test-token-not-real", new HttpTimeoutException("private timeout details")));
        var timeoutConnector = connector(timeout, false);
        assertThatThrownBy(timeoutConnector::verifyAccessScopes)
                .isInstanceOf(ConnectorException.class)
                .hasMessage("SHOPIFY_TIMEOUT")
                .hasMessageNotContaining("test-token-not-real")
                .satisfies(error -> assertThat(((ConnectorException) error).category())
                        .isEqualTo(ConnectorException.Category.RETRYABLE));
    }

    @Test
    void writeAndReconciliationFailClosedEvenWhenWriteFlagAndScopeArePresent() {
        FakeTransport transport = new FakeTransport().respond(200,
                "{\"data\":{\"currentAppInstallation\":{\"accessScopes\":[{\"handle\":\"read_orders\"},{\"handle\":\"write_orders\"}]}}}");
        var properties = configuredProperties(true);
        properties.setAllowedWriteActions(List.of("DELAY_COMPENSATION_COUPON"));
        var connector = new ShopifyGraphqlAfterSalesConnector(properties, transport, MAPPER);
        var order = new com.ecommerce.aftersales.model.AfterSalesTypes.OrderSnapshot(
                "gid://shopify/Order/1", "gid://shopify/Customer/2", "shopify", "SG", "SGD", "warehouse",
                BigDecimal.TEN, true, "fulfilled", 3, "T-1", "NONE");
        var command = new AfterSalesConnector.ExecutionCommand("idem-key", order.orderId(),
                "DELAY_COMPENSATION_COUPON", BigDecimal.ONE, "SGD");

        assertThatThrownBy(() -> connector.execute(order, command))
                .isInstanceOf(ConnectorException.class)
                .hasMessage("SHOPIFY_WRITE_MUTATION_UNSUPPORTED_FAIL_CLOSED");
        assertThatThrownBy(() -> connector.findExecution(command))
                .isInstanceOf(ConnectorException.class)
                .hasMessage("SHOPIFY_EXECUTION_RECONCILIATION_UNSUPPORTED_FAIL_CLOSED");
        assertThat(transport.requests).hasSize(1);
        assertThat(transport.requests.get(0).query()).contains("currentAppInstallation");
        assertThat(transport.requests).noneSatisfy(request -> assertThat(request.query()).contains("mutation"));
    }

    @Test
    void missingCredentialsFailClosedBeforeTransport() {
        var properties = new ShopifyAfterSalesProperties();
        properties.setConnector("shopify");
        FakeTransport transport = new FakeTransport();
        var connector = new ShopifyGraphqlAfterSalesConnector(properties, transport, MAPPER);

        assertThatThrownBy(() -> connector.getOrder("#1001"))
                .isInstanceOf(ConnectorException.class).hasMessage("SHOPIFY_CONFIGURATION_MISSING");
        assertThat(transport.requests).isEmpty();
    }

    private static void assertFailure(
            int status, String body, ConnectorException.Category category, String code) {
        FakeTransport transport = new FakeTransport().respond(status, body);
        assertConnectorFailure(connector(transport, false), category, code);
    }

    private static void assertConnectorFailure(
            ShopifyGraphqlAfterSalesConnector connector, ConnectorException.Category category, String code) {
        assertThatThrownBy(connector::verifyAccessScopes)
                .isInstanceOf(ConnectorException.class)
                .hasMessage(code)
                .extracting(error -> ((ConnectorException) error).category())
                .isEqualTo(category);
    }

    private static ShopifyGraphqlAfterSalesConnector connector(FakeTransport transport, boolean writeEnabled) {
        return new ShopifyGraphqlAfterSalesConnector(configuredProperties(writeEnabled), transport, MAPPER);
    }

    private static ShopifyAfterSalesProperties configuredProperties(boolean writeEnabled) {
        ShopifyAfterSalesProperties properties = new ShopifyAfterSalesProperties();
        properties.setConnector("shopify");
        properties.setShopDomain("demo-store.myshopify.com");
        properties.setAccessToken("test-token-not-real");
        properties.setApiVersion("2026-10");
        properties.setWriteEnabled(writeEnabled);
        properties.validate();
        return properties;
    }

    private static String paidOrderResponse(String name) {
        return "{\"data\":{\"orders\":{\"nodes\":[%s]}}}".formatted(orderNode(name));
    }

    private static String paidOrderByIdResponse() {
        return "{\"data\":{\"order\":%s}}".formatted(orderNode("#1001"));
    }

    private static String orderNode(String name) {
        return """
                {"id":"gid://shopify/Order/12345","name":"%s",
                  "createdAt":"2026-10-01T00:00:00Z","updatedAt":"2026-10-02T00:00:00Z",
                  "displayFinancialStatus":"PAID","displayFulfillmentStatus":"FULFILLED",
                  "totalReceivedSet":{"shopMoney":{"amount":"120.50","currencyCode":"SGD"}},
                  "shippingAddress":{"countryCodeV2":"SG"},"customer":{"id":"gid://shopify/Customer/77"},
                  "fulfillments":[{"id":"gid://shopify/Fulfillment/9","status":"SUCCESS","createdAt":"2026-10-01T01:00:00Z",
                    "updatedAt":"2026-10-02T01:00:00Z","deliveredAt":"2026-10-02T00:00:00Z",
                    "estimatedDeliveryAt":"2026-10-05T00:00:00Z","location":{"name":"SG Warehouse"},
                    "trackingInfo":[{"company":"Carrier","number":"TRACK-1"}],
                    "events":{"nodes":[{"status":"DELIVERED","happenedAt":"2026-10-02T00:00:00Z",
                      "city":"Singapore","country":"SG","message":"Delivered","estimatedDeliveryAt":"2026-10-05T00:00:00Z"}]}}]}
                """.formatted(name);
    }

    private record Request(URI endpoint, String accessToken, String query, Map<String, Object> variables) {}

    private static final class FakeTransport implements ShopifyGraphqlTransport {
        final Queue<Response> responses = new ArrayDeque<>();
        final Queue<RuntimeException> exceptions = new ArrayDeque<>();
        final List<Request> requests = new ArrayList<>();

        FakeTransport respond(int status, String body) {
            responses.add(new Response(status, body));
            return this;
        }

        FakeTransport throwNext(RuntimeException error) {
            exceptions.add(error);
            return this;
        }

        @Override
        public Response post(URI endpoint, String accessToken, String query, Map<String, Object> variables) {
            requests.add(new Request(endpoint, accessToken, query, variables));
            if (!exceptions.isEmpty()) throw exceptions.remove();
            if (responses.isEmpty()) throw new AssertionError("Unexpected GraphQL request");
            return responses.remove();
        }
    }
}
