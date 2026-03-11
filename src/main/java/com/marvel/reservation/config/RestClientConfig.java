package com.marvel.reservation.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;

/**
 * Configures the RestClient used by the generated
 * credit-card-payment-service API client.
 *
 */
@Configuration
public class RestClientConfig {

    private final CreditCardServiceProperties properties;

    public RestClientConfig(CreditCardServiceProperties properties) {
        this.properties = properties;
    }

    @Bean
    public RestClient creditCardRestClient() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) Duration.ofSeconds(properties.timeoutSeconds()).toMillis());
        factory.setReadTimeout((int) Duration.ofSeconds(properties.timeoutSeconds()).toMillis());

        return RestClient.builder()
                .baseUrl(properties.baseUrl())
                .requestFactory(factory)
                .build();
    }
}
