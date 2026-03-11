package com.marvel.reservation.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * Represents an event consumed from Kafka topic: bank-transfer-payment-update
 *
 * transactionDescription format:
 *   "<E2E unique id (10 chars)> <reservationId>"
 *   Example: "1401541457 P4145478"
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class BankTransferPaymentEvent {

    private String paymentId;
    private String debtorAccountNumber;
    private BigDecimal amountReceived;
    private String transactionDescription;

    /**
     * Parses the reservationId from transactionDescription.
     *
     * @return reservationId or null if parsing fails
     */
    public String extractReservationId() {
        if (transactionDescription == null || transactionDescription.isBlank()) {
            return null;
        }

        String[] parts = transactionDescription.trim().split("\\s+");
        if (parts.length < 2) {
            return null;
        }

        return parts[1];
    }
}