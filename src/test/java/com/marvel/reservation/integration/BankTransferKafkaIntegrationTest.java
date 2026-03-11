package com.marvel.reservation.integration;

import com.marvel.reservation.dto.BankTransferPaymentEvent;
import com.marvel.reservation.entity.Reservation;
import com.marvel.reservation.enums.PaymentMode;
import com.marvel.reservation.enums.ReservationStatus;
import com.marvel.reservation.enums.RoomSegment;
import com.marvel.reservation.repository.ReservationRepository;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;

import com.fasterxml.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Full pipeline test:
 * Embedded Kafka → BankTransferPaymentConsumer → ReservationService → DB.
 */
@SpringBootTest(properties = {
        // Point Spring Kafka at the embedded broker instead of any real cluster
        "spring.kafka.bootstrap-servers=${spring.embedded.kafka.brokers}"
})
@EmbeddedKafka(
        partitions = 1,
        topics = { "bank-transfer-payment-update" }
)
@ActiveProfiles("test")
class BankTransferKafkaIntegrationTest {

    private static final String TOPIC = "bank-transfer-payment-update";

    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ReservationRepository reservationRepository;

    @Test
    @DisplayName("Embedded Kafka event → reservation status updated to CONFIRMED")
    void bankTransferEventThroughKafka_updatesReservationToConfirmed() throws Exception {
        // Arrange: real PENDING_PAYMENT bank-transfer reservation in DB
        Reservation pending = Reservation.builder()
                .customerName("Natasha Romanoff")
                .roomNumber("404")
                .startDate(LocalDate.now().plusDays(7))
                .endDate(LocalDate.now().plusDays(12))
                .roomSegment(RoomSegment.SMALL)
                .paymentMode(PaymentMode.BANK_TRANSFER)
                .status(ReservationStatus.PENDING_PAYMENT)
                .build();
        Reservation saved = reservationRepository.save(pending);

        // Build Kafka event whose transactionDescription encodes the reservationId
        BankTransferPaymentEvent event = new BankTransferPaymentEvent(
        "PAY-KAFKA-001",
        "NL91ABNA0417164300",
        new BigDecimal("500.00"),
        "1401541457 " + saved.getId()
);

        String payload = objectMapper.writeValueAsString(event);

        kafkaTemplate.send(TOPIC, event.getPaymentId(), payload).get();

        // Assert: within a small timeout, DB record is updated to CONFIRMED
        assertEventually(
                () -> reservationRepository.findById(saved.getId())
                        .map(Reservation::getStatus)
                        .orElse(null),
                status -> status == ReservationStatus.CONFIRMED,
                Duration.ofSeconds(5)
        );
    }

    /**
     * Simple polling helper to avoid extra dependencies (like Awaitility).
     */
    private <T> void assertEventually(
            SupplierWithException<T> valueSupplier,
            java.util.function.Predicate<T> condition,
            Duration timeout
    ) throws Exception {
        long deadline = System.currentTimeMillis() + timeout.toMillis();
        T lastValue = null;

        do {
            lastValue = valueSupplier.get();
            if (condition.test(lastValue)) {
                return;
            }
            Thread.sleep(100L);
        } while (System.currentTimeMillis() < deadline);

        assertThat(condition.test(lastValue))
                .as("Condition not met within %d ms, last value was: %s", timeout.toMillis(), lastValue)
                .isTrue();
    }

    @FunctionalInterface
    private interface SupplierWithException<T> {
        T get() throws Exception;
    }
}