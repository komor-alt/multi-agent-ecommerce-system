package com.ecommerce.config;

import org.springframework.boot.web.client.RestClientCustomizer;
import org.springframework.boot.web.reactive.function.client.WebClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import reactor.netty.http.client.HttpClient;
import io.netty.channel.ChannelOption;

/** Socket deadlines bound real network work, including timed-out model calls. */
@Configuration(proxyBeanMethods = false)
public class OutboundHttpConfiguration {
    @Bean
    RestClientCustomizer boundedRestClient(RuntimeLimitsProperties limits) {
        java.net.http.HttpClient client = java.net.http.HttpClient.newBuilder()
                .connectTimeout(limits.getConnectTimeout()).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(client);
        factory.setReadTimeout(limits.getReadTimeout());
        return builder -> builder.requestFactory(factory);
    }

    @Bean
    WebClientCustomizer boundedWebClient(RuntimeLimitsProperties limits) {
        HttpClient client = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS,
                        Math.toIntExact(limits.getConnectTimeout().toMillis()))
                .responseTimeout(limits.getReadTimeout());
        return builder -> builder.clientConnector(new ReactorClientHttpConnector(client));
    }
}
