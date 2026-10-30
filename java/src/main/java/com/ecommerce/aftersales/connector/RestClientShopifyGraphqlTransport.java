package com.ecommerce.aftersales.connector;

import com.ecommerce.config.ShopifyAfterSalesProperties;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.util.StreamUtils;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

/** Production HTTP implementation. Responses are returned without logging or exposing headers. */
public final class RestClientShopifyGraphqlTransport implements ShopifyGraphqlTransport {
    private final RestClient restClient;

    public RestClientShopifyGraphqlTransport(ShopifyAfterSalesProperties properties) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(properties.getConnectTimeoutMs()))
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(Duration.ofMillis(properties.getReadTimeoutMs()));
        this.restClient = RestClient.builder().requestFactory(requestFactory).build();
    }

    @Override
    public Response post(java.net.URI endpoint, String accessToken, String query, Map<String, Object> variables) {
        return restClient.post()
                .uri(endpoint)
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Shopify-Access-Token", accessToken)
                .body(Map.of("query", query, "variables", variables))
                .exchange((request, response) -> new Response(
                        response.getStatusCode().value(),
                        StreamUtils.copyToString(response.getBody(), StandardCharsets.UTF_8)));
    }
}
