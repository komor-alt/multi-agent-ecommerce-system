package com.ecommerce.aftersales.connector;

import java.net.URI;
import java.util.Map;

/** Replaceable HTTP boundary for Shopify GraphQL; test implementations never access the network. */
public interface ShopifyGraphqlTransport {
    Response post(URI endpoint, String accessToken, String query, Map<String, Object> variables);

    record Response(int statusCode, String body) {}
}
