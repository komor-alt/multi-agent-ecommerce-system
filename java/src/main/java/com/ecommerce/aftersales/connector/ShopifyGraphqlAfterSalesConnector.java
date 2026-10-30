package com.ecommerce.aftersales.connector;

import com.ecommerce.aftersales.model.AfterSalesTypes;
import com.ecommerce.config.ShopifyAfterSalesProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpTimeoutException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Read-only Shopify Admin GraphQL adapter. External writes remain deliberately unsupported. */
public final class ShopifyGraphqlAfterSalesConnector implements AfterSalesConnector {
    private static final Pattern ORDER_GID = Pattern.compile("^gid://shopify/Order/(\\d+)$");
    private static final Pattern ORDER_NAME = Pattern.compile("^[A-Za-z0-9#._-]{1,64}$");
    private static final Pattern EXPLICIT_NAME = Pattern.compile("(?i)^name:(.+)$");
    private static final String ORDER_FIELDS = """
            id name createdAt updatedAt displayFinancialStatus displayFulfillmentStatus
            totalReceivedSet { shopMoney { amount currencyCode } }
            shippingAddress { countryCodeV2 }
            customer { id }
            fulfillments(first: 20) {
              id status createdAt updatedAt deliveredAt estimatedDeliveryAt
              location { name }
              trackingInfo(first: 10) { company number url }
              events(first: 50) { nodes { status happenedAt city country message estimatedDeliveryAt } }
            }
            """;
    private static final String ACCESS_SCOPES_QUERY = """
            query CurrentAppAccessScopes {
              currentAppInstallation { accessScopes { handle } }
            }
            """;

    private final ShopifyAfterSalesProperties properties;
    private final ShopifyGraphqlTransport transport;
    private final ObjectMapper objectMapper;

    public ShopifyGraphqlAfterSalesConnector(
            ShopifyAfterSalesProperties properties,
            ShopifyGraphqlTransport transport,
            ObjectMapper objectMapper) {
        this.properties = properties;
        this.transport = transport;
        this.objectMapper = objectMapper;
    }

    /** Current installation grants, not the scopes requested in app configuration. */
    public AccessScopeStatus verifyAccessScopes() {
        JsonNode data = executeGraphql(ACCESS_SCOPES_QUERY, Map.of());
        JsonNode installation = data.path("currentAppInstallation");
        JsonNode scopesNode = installation.path("accessScopes");
        if (!scopesNode.isArray()) {
            throw terminal("SHOPIFY_ACCESS_SCOPES_UNAVAILABLE");
        }
        Set<String> scopes = new HashSet<>();
        scopesNode.forEach(scope -> {
            String handle = text(scope, "handle");
            if (handle != null) scopes.add(handle);
        });
        boolean grantedReadOrders = scopes.contains("read_orders");
        boolean writeOrders = scopes.contains("write_orders");
        return new AccessScopeStatus(grantedReadOrders, grantedReadOrders || writeOrders,
                scopes.contains("read_all_orders"), writeOrders, Set.copyOf(scopes));
    }

    @Override
    public AfterSalesTypes.OrderSnapshot getOrder(String orderId) {
        OrderIdentifier identifier = parseOrderIdentifier(orderId);
        requireReadScope(verifyAccessScopes());
        OrderData order = loadOrder(identifier);
        JsonNode root = order.root();
        JsonNode money = root.path("totalReceivedSet").path("shopMoney");
        String country = text(root.path("shippingAddress"), "countryCodeV2");
        String currency = text(money, "currencyCode");
        String amountValue = text(money, "amount");
        if (country == null || currency == null || amountValue == null) {
            throw terminal("SHOPIFY_ORDER_REQUIRED_FIELDS_MISSING");
        }
        BigDecimal paidAmount;
        try {
            paidAmount = new BigDecimal(amountValue);
        } catch (NumberFormatException error) {
            throw terminal("SHOPIFY_ORDER_AMOUNT_INVALID");
        }

        String financialStatus = text(root, "displayFinancialStatus");
        String fulfillmentStatus = text(root, "displayFulfillmentStatus");
        if (financialStatus == null || fulfillmentStatus == null) {
            throw terminal("SHOPIFY_ORDER_STATUS_MISSING");
        }
        String refundStatus = switch (financialStatus) {
            case "REFUNDED" -> "REFUNDED";
            case "PARTIALLY_REFUNDED" -> "PARTIALLY_REFUNDED";
            default -> "NONE";
        };
        String userId = text(root.path("customer"), "id");
        String trackingNumber = order.fulfillments().stream()
                .flatMap(fulfillment -> array(fulfillment.path("trackingInfo")).stream())
                .map(info -> text(info, "number"))
                .filter(value -> value != null && !value.isBlank())
                .findFirst().orElse("");
        String location = order.fulfillments().stream()
                .map(fulfillment -> text(fulfillment.path("location"), "name"))
                .filter(value -> value != null && !value.isBlank())
                .findFirst().orElse(null);

        return new AfterSalesTypes.OrderSnapshot(orderId, userId, "shopify", country, currency, location,
                paidAmount, paidAmount.signum() > 0 && !"REFUNDED".equals(financialStatus),
                fulfillmentStatus.toLowerCase(Locale.ROOT), promisedDays(order), trackingNumber, refundStatus);
    }

    @Override
    public AfterSalesTypes.ShipmentSnapshot getShipment(AfterSalesTypes.OrderSnapshot order) {
        if (order == null || order.orderId() == null || order.orderId().isBlank()) {
            throw terminal("SHOPIFY_ORDER_ID_REQUIRED");
        }
        OrderIdentifier identifier = parseOrderIdentifier(order.orderId());
        requireReadScope(verifyAccessScopes());
        OrderData current = loadOrder(identifier);
        List<EventData> events = current.fulfillments().stream()
                .flatMap(fulfillment -> array(fulfillment.path("events").path("nodes")).stream())
                .map(ShopifyGraphqlAfterSalesConnector::eventData)
                .sorted(Comparator.comparing(EventData::occurredAt))
                .toList();
        Instant orderUpdated = parseInstant(text(current.root(), "updatedAt"), "SHOPIFY_ORDER_TIMESTAMP_INVALID");
        Instant lastUpdated = events.isEmpty()
                ? latestFulfillmentUpdate(current).orElse(orderUpdated)
                : events.get(events.size() - 1).occurredAt();
        String status = !events.isEmpty()
                ? events.get(events.size() - 1).status()
                : current.fulfillments().stream().map(node -> text(node, "status"))
                        .filter(value -> value != null && !value.isBlank()).findFirst()
                        .orElse(text(current.root(), "displayFulfillmentStatus"));
        String trackingNumber = current.fulfillments().stream()
                .flatMap(fulfillment -> array(fulfillment.path("trackingInfo")).stream())
                .map(info -> text(info, "number"))
                .filter(value -> value != null && !value.isBlank()).findFirst()
                .orElse(order.trackingNumber());
        Instant estimate = latestEstimate(current, events).orElse(null);
        Instant now = Instant.now();
        int inactiveDays = (int) Math.max(0, ChronoUnit.DAYS.between(lastUpdated, now));
        int delayDays = estimate == null || estimate.isAfter(now)
                ? 0 : (int) Math.max(0, ChronoUnit.DAYS.between(estimate, now));
        List<AfterSalesTypes.ShipmentCheckpoint> timeline = events.stream()
                .map(event -> new AfterSalesTypes.ShipmentCheckpoint(event.occurredAt(),
                        event.status().toLowerCase(Locale.ROOT), event.location(), event.message()))
                .toList();
        return new AfterSalesTypes.ShipmentSnapshot(trackingNumber, status == null ? "unknown" : status.toLowerCase(Locale.ROOT),
                lastUpdated, inactiveDays, delayDays, timeline);
    }

    @Override
    public AfterSalesTypes.CarrierCaseSnapshot getCarrierCase(AfterSalesTypes.OrderSnapshot order) {
        throw terminal("SHOPIFY_CARRIER_CASE_EVIDENCE_UNSUPPORTED");
    }

    @Override
    public AfterSalesTypes.DeliverySnapshot getDelivery(AfterSalesTypes.OrderSnapshot order) {
        throw terminal("SHOPIFY_DELIVERY_PROOF_EVIDENCE_UNSUPPORTED");
    }

    @Override
    public AfterSalesTypes.ProductSnapshot getProduct(AfterSalesTypes.OrderSnapshot order) {
        throw terminal("SHOPIFY_PRODUCT_EVIDENCE_UNSUPPORTED");
    }

    @Override
    public AfterSalesTypes.ExecutionResult execute(
            AfterSalesTypes.OrderSnapshot order, ExecutionCommand command) {
        requireConfigured();
        if (!properties.isWriteEnabled()) throw terminal("SHOPIFY_WRITE_DISABLED");
        if (!properties.getAllowedWriteActions().contains(command.actionType())) {
            throw terminal("SHOPIFY_WRITE_ACTION_NOT_ALLOWED");
        }
        if (!verifyAccessScopes().writeOrders()) throw terminal("SHOPIFY_WRITE_SCOPE_MISSING");
        // No Shopify mutation and no result-reconciliation contract has been implemented here.
        throw terminal("SHOPIFY_WRITE_MUTATION_UNSUPPORTED_FAIL_CLOSED");
    }

    @Override
    public Optional<AfterSalesTypes.ExecutionResult> findExecution(ExecutionCommand command) {
        requireConfigured();
        throw terminal("SHOPIFY_EXECUTION_RECONCILIATION_UNSUPPORTED_FAIL_CLOSED");
    }

    private OrderData loadOrder(OrderIdentifier identifier) {
        JsonNode data;
        if (identifier.gid() != null) {
            String query = "query AfterSalesOrderById($id: ID!) { order(id: $id) { " + ORDER_FIELDS + " } }";
            data = executeGraphql(query, Map.of("id", identifier.gid()));
            JsonNode order = data.path("order");
            if (order.isMissingNode() || order.isNull()) throw terminal("SHOPIFY_ORDER_NOT_FOUND_OR_SCOPE_LIMITED");
            return new OrderData(order, array(order.path("fulfillments")));
        }

        String query = "query AfterSalesOrderByName($search: String!) { orders(first: 2, query: $search) { nodes { "
                + ORDER_FIELDS + " } } }";
        data = executeGraphql(query, Map.of("search", "name:" + identifier.name()));
        List<JsonNode> matches = array(data.path("orders").path("nodes")).stream()
                .filter(node -> identifier.name().equals(text(node, "name")))
                .toList();
        if (matches.isEmpty()) throw terminal("SHOPIFY_ORDER_NOT_FOUND_OR_SCOPE_LIMITED");
        if (matches.size() != 1) throw terminal("SHOPIFY_ORDER_NAME_AMBIGUOUS");
        JsonNode order = matches.get(0);
        return new OrderData(order, array(order.path("fulfillments")));
    }

    private JsonNode executeGraphql(String query, Map<String, Object> variables) {
        requireConfigured();
        ShopifyGraphqlTransport.Response response;
        try {
            response = transport.post(endpoint(), properties.getAccessToken(), query, variables);
        } catch (ConnectorException error) {
            throw error;
        } catch (RuntimeException error) {
            throw retryable(isTimeout(error) ? "SHOPIFY_TIMEOUT" : "SHOPIFY_TRANSPORT_FAILURE");
        }
        if (response == null) throw retryable("SHOPIFY_TRANSPORT_EMPTY_RESPONSE");
        int status = response.statusCode();
        if (status == 401 || status == 403) throw terminal("SHOPIFY_HTTP_AUTHORIZATION_FAILED");
        if (status == 408 || status == 429 || status >= 500) throw retryable("SHOPIFY_HTTP_" + status);
        if (status < 200 || status >= 300) throw terminal("SHOPIFY_HTTP_" + status);

        JsonNode root;
        try {
            root = objectMapper.readTree(response.body());
        } catch (Exception error) {
            throw terminal("SHOPIFY_RESPONSE_INVALID");
        }
        if (root == null || !root.isObject()) throw terminal("SHOPIFY_RESPONSE_INVALID");
        JsonNode errors = root.path("errors");
        if (errors.isArray() && !errors.isEmpty()) {
            ConnectorException firstError = null;
            for (JsonNode error : errors) {
                ConnectorException classified = classifyGraphqlError(error);
                if (classified.category() == ConnectorException.Category.RETRYABLE) throw classified;
                if (firstError == null) firstError = classified;
            }
            throw firstError == null ? terminal("SHOPIFY_GRAPHQL_ERROR") : firstError;
        }
        JsonNode data = root.path("data");
        if (data.isMissingNode() || data.isNull()) throw terminal("SHOPIFY_RESPONSE_DATA_MISSING");
        if (containsUserErrors(data)) throw terminal("SHOPIFY_USER_ERROR");
        return data;
    }

    private ConnectorException classifyGraphqlError(JsonNode error) {
        String code = text(error.path("extensions"), "code");
        if ("THROTTLED".equals(code) || "INTERNAL_SERVER_ERROR".equals(code)
                || "SERVICE_UNAVAILABLE".equals(code)) {
            return retryable("SHOPIFY_GRAPHQL_RETRYABLE_ERROR");
        }
        if ("ACCESS_DENIED".equals(code) || "SHOP_INACTIVE".equals(code)) {
            return terminal("SHOPIFY_GRAPHQL_ACCESS_DENIED");
        }
        return terminal("SHOPIFY_GRAPHQL_ERROR");
    }

    private static boolean containsUserErrors(JsonNode node) {
        if (node.isObject()) {
            var fields = node.fields();
            while (fields.hasNext()) {
                var field = fields.next();
                if ("userErrors".equals(field.getKey()) && field.getValue().isArray()
                        && !field.getValue().isEmpty()) return true;
                if (containsUserErrors(field.getValue())) return true;
            }
        } else if (node.isArray()) {
            for (JsonNode child : node) if (containsUserErrors(child)) return true;
        }
        return false;
    }

    private static OrderIdentifier parseOrderIdentifier(String suppliedId) {
        if (suppliedId == null || suppliedId.isBlank()) throw terminal("SHOPIFY_ORDER_ID_INVALID");
        String value = suppliedId.trim();
        Matcher gid = ORDER_GID.matcher(value);
        if (gid.matches()) return new OrderIdentifier("gid://shopify/Order/" + gid.group(1), null);
        if (value.startsWith("gid://shopify/Order/")) throw terminal("SHOPIFY_ORDER_ID_INVALID");
        Matcher explicitName = EXPLICIT_NAME.matcher(value);
        String name = explicitName.matches() ? explicitName.group(1) : value;
        if (!ORDER_NAME.matcher(name).matches()) throw terminal("SHOPIFY_ORDER_ID_INVALID");
        return new OrderIdentifier(null, name);
    }

    private void requireReadScope(AccessScopeStatus scopes) {
        if (!scopes.effectiveCanReadOrders()) throw terminal("SHOPIFY_READ_ORDERS_SCOPE_MISSING");
    }

    private void requireConfigured() {
        if (!properties.isConfigured()) throw terminal("SHOPIFY_CONFIGURATION_MISSING");
    }

    private URI endpoint() {
        return URI.create("https://" + properties.getShopDomain().toLowerCase(Locale.ROOT)
                + "/admin/api/" + properties.getApiVersion() + "/graphql.json");
    }

    private static int promisedDays(OrderData order) {
        Instant createdAt = parseInstant(text(order.root(), "createdAt"), "SHOPIFY_ORDER_TIMESTAMP_INVALID");
        return latestEstimate(order, List.of()).map(estimate ->
                (int) Math.max(0, ChronoUnit.DAYS.between(createdAt, estimate))).orElse(0);
    }

    private static Optional<Instant> latestFulfillmentUpdate(OrderData order) {
        return order.fulfillments().stream()
                .map(node -> firstInstant(text(node, "updatedAt"), text(node, "createdAt")))
                .flatMap(Optional::stream).max(Comparator.naturalOrder());
    }

    private static Optional<Instant> latestEstimate(OrderData order, List<EventData> events) {
        Optional<Instant> eventEstimate = events.stream().map(EventData::estimatedDeliveryAt)
                .flatMap(Optional::stream).max(Comparator.naturalOrder());
        if (eventEstimate.isPresent()) return eventEstimate;
        return order.fulfillments().stream()
                .map(node -> firstInstant(text(node, "estimatedDeliveryAt"), null))
                .flatMap(Optional::stream).max(Comparator.naturalOrder());
    }

    private static EventData eventData(JsonNode event) {
        String status = text(event, "status");
        if (status == null) throw terminal("SHOPIFY_FULFILLMENT_EVENT_INVALID");
        String location = text(event, "city");
        String country = text(event, "country");
        if (location != null && country != null) location = location + ", " + country;
        if (location == null) location = country;
        String message = text(event, "message");
        return new EventData(status,
                parseInstant(text(event, "happenedAt"), "SHOPIFY_FULFILLMENT_EVENT_INVALID"),
                location, message == null ? "" : message,
                firstInstant(text(event, "estimatedDeliveryAt"), null));
    }

    private static List<JsonNode> array(JsonNode node) {
        List<JsonNode> items = new ArrayList<>();
        if (node != null && node.isArray()) node.forEach(items::add);
        return items;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value == null || value.isNull() || !value.isValueNode() ? null : value.asText();
    }

    private static Optional<Instant> firstInstant(String first, String second) {
        String value = first == null ? second : first;
        return value == null ? Optional.empty() : Optional.of(parseInstant(value, "SHOPIFY_TIMESTAMP_INVALID"));
    }

    private static Instant parseInstant(String value, String errorCode) {
        if (value == null || value.isBlank()) throw terminal(errorCode);
        try {
            return Instant.parse(value);
        } catch (RuntimeException ignored) {
            try {
                return OffsetDateTime.parse(value).toInstant();
            } catch (RuntimeException invalid) {
                throw terminal(errorCode);
            }
        }
    }

    private static boolean isTimeout(Throwable error) {
        for (Throwable current = error; current != null; current = current.getCause()) {
            if (current instanceof HttpTimeoutException || current instanceof java.net.SocketTimeoutException
                    || current instanceof TimeoutException) return true;
        }
        return false;
    }

    private static ConnectorException retryable(String code) {
        return new ConnectorException(ConnectorException.Category.RETRYABLE, code);
    }

    private static ConnectorException terminal(String code) {
        return new ConnectorException(ConnectorException.Category.TERMINAL, code);
    }

    public record AccessScopeStatus(boolean grantedReadOrders, boolean effectiveCanReadOrders,
                                    boolean readAllOrders, boolean writeOrders, Set<String> grantedScopes) {
        /** Effective read permission includes Shopify's write_orders scope. */
        public boolean readOrders() { return effectiveCanReadOrders; }
    }
    private record OrderIdentifier(String gid, String name) {}
    private record OrderData(JsonNode root, List<JsonNode> fulfillments) {}
    private record EventData(String status, Instant occurredAt, String location, String message,
                             Optional<Instant> estimatedDeliveryAt) {}
}
