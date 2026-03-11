package com.marvel.reservation.client;

import com.marvel.reservation.config.CreditCardServiceProperties;
import com.marvel.reservation.exception.PaymentNotConfirmedException;
import com.marvel.reservation.generated.client.DefaultApi;
import com.marvel.reservation.generated.invoker.ApiClient;
import com.marvel.reservation.generated.model.PaymentStatusRetrievalRequest;
import com.marvel.reservation.generated.model.PaymentStatusResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Thin wrapper around the auto-generated DefaultApi client.
 *
 * All HTTP plumbing and DTOs are generated from:
 *   src/main/resources/openapi/Assignment02_creditcardpayment_api.yaml
 *
 * This class only contains business logic: checking CONFIRMED status.
 */
@Slf4j
@Component
public class CreditCardPaymentClient {

    private static final String CONFIRMED = "CONFIRMED";

    private final DefaultApi generatedApi;

    public CreditCardPaymentClient(CreditCardServiceProperties properties,
                                   RestClient creditCardRestClient) {
        ApiClient apiClient = new ApiClient(creditCardRestClient);
        apiClient.setBasePath(properties.baseUrl());
        this.generatedApi = new DefaultApi(apiClient);
    }

    /**
     * Verifies the credit card payment is CONFIRMED via the external service.
     *
     * @param paymentReference the payment reference to verify
     * @throws PaymentNotConfirmedException if status is not CONFIRMED or call fails
     */
    public void verifyPaymentConfirmed(String paymentReference) {
        log.info("Verifying credit card payment for reference: {}", paymentReference);

        try {
            PaymentStatusRetrievalRequest request = new PaymentStatusRetrievalRequest()
                    .paymentReference(paymentReference);

            PaymentStatusResponse response = generatedApi.paymentStatusPost(request);

            if (response == null || response.getStatus() == null) {
                throw new PaymentNotConfirmedException(
                        "Empty response from credit-card-payment-service for reference: "
                        + paymentReference);
            }

            log.info("Credit card payment status for reference {}: {}",
                    paymentReference, response.getStatus());

            if (!CONFIRMED.equalsIgnoreCase(response.getStatus())) {
                throw new PaymentNotConfirmedException(
                        "Credit card payment is not confirmed. Status: " + response.getStatus());
            }

        } catch (PaymentNotConfirmedException e) {
            throw e;
        } catch (RestClientException e) {
            log.error("Error calling credit-card-payment-service for reference {}: {}",
                    paymentReference, e.getMessage());
            throw new PaymentNotConfirmedException(
                    "Unable to reach credit-card-payment-service: " + e.getMessage());
        }
    }
}
