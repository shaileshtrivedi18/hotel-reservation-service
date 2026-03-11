package com.marvel.reservation.service;

import com.marvel.reservation.dto.BankTransferPaymentEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class BankTransferPaymentEventTest {

    // =========================================================================
    // VALID INPUTS
    // =========================================================================

    @ParameterizedTest
    @CsvSource({
        "1401541457 P4145478,       P4145478",
        "1401541457 RES00001,       RES00001",
        "ABCDE12345 XY123456,       XY123456",
        // Extra tokens after reservationId — second token is still the reservationId
        "1401541457 RES00001 extra, RES00001",
        "1401541457 RES00001 foo bar baz, RES00001"
    })
    @DisplayName("Valid transactionDescription → reservationId parsed from second token")
    void extractReservationId_validInput(String description, String expectedId) {
        BankTransferPaymentEvent event = new BankTransferPaymentEvent();
        event.setTransactionDescription(description.trim());
        assertThat(event.extractReservationId()).isEqualTo(expectedId.trim());
    }

    // =========================================================================
    // NULL / EMPTY
    // =========================================================================

    @ParameterizedTest
    @NullAndEmptySource
    @DisplayName("Null or empty transactionDescription → returns null")
    void extractReservationId_nullOrEmpty_returnsNull(String description) {
        BankTransferPaymentEvent event = new BankTransferPaymentEvent();
        event.setTransactionDescription(description);
        assertThat(event.extractReservationId()).isNull();
    }

    // =========================================================================
    // BLANK / WHITESPACE ONLY
    // =========================================================================

    @ParameterizedTest
    @ValueSource(strings = {"   ", "\t", "\n", "  \t  "})
    @DisplayName("Whitespace-only transactionDescription → returns null")
    void extractReservationId_whitespaceOnly_returnsNull(String description) {
        BankTransferPaymentEvent event = new BankTransferPaymentEvent();
        event.setTransactionDescription(description);
        assertThat(event.extractReservationId()).isNull();
    }

    // =========================================================================
    // SINGLE TOKEN (no space separator)
    // =========================================================================

    @ParameterizedTest
    @ValueSource(strings = {"onlyone", "RES00001", "1401541457"})
    @DisplayName("Single token (no space) → returns null — cannot extract reservationId")
    void extractReservationId_singleToken_returnsNull(String description) {
        BankTransferPaymentEvent event = new BankTransferPaymentEvent();
        event.setTransactionDescription(description);
        assertThat(event.extractReservationId()).isNull();
    }
}