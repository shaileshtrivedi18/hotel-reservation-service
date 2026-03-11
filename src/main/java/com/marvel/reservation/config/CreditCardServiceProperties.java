package com.marvel.reservation.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Typed configuration for credit-card-payment-service.
 * Bound from application.yml under prefix: credit-card-payment-service
 *
 * Using @ConfigurationProperties is the recommended approach over @Value:
 * - Type-safe
 * - IDE autocomplete in application.yml
 * - Easy to test (just instantiate the record)
 */
@ConfigurationProperties(prefix = "credit-card-payment-service")
public record CreditCardServiceProperties(
        String baseUrl,
        int timeoutSeconds
) {}
