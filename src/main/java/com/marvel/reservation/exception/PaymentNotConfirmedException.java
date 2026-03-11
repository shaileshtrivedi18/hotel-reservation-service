package com.marvel.reservation.exception;

/**
 * Thrown when the credit-card-payment-service returns a non-CONFIRMED status
 * or when the downstream call fails.
 */
public class PaymentNotConfirmedException extends RuntimeException {
    public PaymentNotConfirmedException(String message) {
        super(message);
    }
}
