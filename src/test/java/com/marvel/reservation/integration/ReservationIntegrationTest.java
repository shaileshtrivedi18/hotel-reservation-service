package com.marvel.reservation.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.marvel.reservation.client.CreditCardPaymentClient;
import com.marvel.reservation.dto.BankTransferPaymentEvent;
import com.marvel.reservation.dto.ReservationRequest;
import com.marvel.reservation.entity.Reservation;
import com.marvel.reservation.enums.PaymentMode;
import com.marvel.reservation.enums.ReservationStatus;
import com.marvel.reservation.enums.RoomSegment;
import com.marvel.reservation.exception.PaymentNotConfirmedException;
import com.marvel.reservation.repository.ReservationRepository;
import com.marvel.reservation.scheduler.ReservationAutoCancelScheduler;
import com.marvel.reservation.service.ReservationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Integration tests using the full Spring Boot application context.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional  // each test rolls back — DB is clean for every test
class ReservationIntegrationTest {

    // ── Injected beans — all at outer class level, accessible by all nested classes ──

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ReservationRepository reservationRepository;

    @Autowired
    private ReservationService reservationService;

    @Autowired
    private ReservationAutoCancelScheduler scheduler;  // ← fixed: declared here, not in nested class

    // Only the external HTTP dependency is mocked
    @MockBean
    private CreditCardPaymentClient creditCardPaymentClient;

    private ReservationRequest validRequest;

    @BeforeEach
    void setUp() {
        validRequest = new ReservationRequest();
        validRequest.setCustomerName("Steve Rogers");
        validRequest.setRoomNumber("303");
        validRequest.setStartDate(LocalDate.now().plusDays(5));
        validRequest.setEndDate(LocalDate.now().plusDays(10));
        validRequest.setRoomSegment(RoomSegment.LARGE);
    }

    // =========================================================================
    // FULL PIPELINE: HTTP → Controller → Service → DB
    // =========================================================================

    @Nested
    @DisplayName("Full pipeline: HTTP request → DB persistence")
    class FullPipeline {

        @Test
        @DisplayName("CASH: POST /confirm → 201, persisted to DB as CONFIRMED")
        void cash_endToEnd_persistedAsConfirmed() throws Exception {
            validRequest.setPaymentMode(PaymentMode.CASH);

            MvcResult result = mockMvc.perform(post("/api/v1/reservations/confirm")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(validRequest)))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.status").value("CONFIRMED"))
                    .andExpect(jsonPath("$.reservationId").isNotEmpty())
                    .andReturn();

            String reservationId = extractReservationId(result);
            Reservation saved = reservationRepository.findById(reservationId).orElseThrow();

            assertThat(saved.getStatus()).isEqualTo(ReservationStatus.CONFIRMED);
            assertThat(saved.getPaymentMode()).isEqualTo(PaymentMode.CASH);
            assertThat(saved.getCustomerName()).isEqualTo("Steve Rogers");
            assertThat(saved.getRoomNumber()).isEqualTo("303");
        }

        @Test
        @DisplayName("BANK_TRANSFER: POST /confirm → 201, persisted to DB as PENDING_PAYMENT")
        void bankTransfer_endToEnd_persistedAsPending() throws Exception {
            validRequest.setPaymentMode(PaymentMode.BANK_TRANSFER);

            MvcResult result = mockMvc.perform(post("/api/v1/reservations/confirm")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(validRequest)))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.status").value("PENDING_PAYMENT"))
                    .andReturn();

            String reservationId = extractReservationId(result);
            Reservation saved = reservationRepository.findById(reservationId).orElseThrow();

            assertThat(saved.getStatus()).isEqualTo(ReservationStatus.PENDING_PAYMENT);
            assertThat(saved.getPaymentMode()).isEqualTo(PaymentMode.BANK_TRANSFER);
        }

        @Test
        @DisplayName("CREDIT_CARD confirmed: POST /confirm → 201, persisted as CONFIRMED")
        void creditCard_confirmed_endToEnd_persistedAsConfirmed() throws Exception {
            validRequest.setPaymentMode(PaymentMode.CREDIT_CARD);
            validRequest.setPaymentReference("CC-INTEGRATION-REF");
            doNothing().when(creditCardPaymentClient).verifyPaymentConfirmed("CC-INTEGRATION-REF");

            MvcResult result = mockMvc.perform(post("/api/v1/reservations/confirm")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(validRequest)))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.status").value("CONFIRMED"))
                    .andReturn();

            String reservationId = extractReservationId(result);
            Reservation saved = reservationRepository.findById(reservationId).orElseThrow();

            assertThat(saved.getStatus()).isEqualTo(ReservationStatus.CONFIRMED);
            assertThat(saved.getPaymentReference()).isEqualTo("CC-INTEGRATION-REF");
        }

        @Test
        @DisplayName("CREDIT_CARD rejected: POST /confirm → 402, nothing persisted to DB")
        void creditCard_rejected_endToEnd_nothingPersisted() throws Exception {
            validRequest.setPaymentMode(PaymentMode.CREDIT_CARD);
            validRequest.setPaymentReference("CC-REJECTED-REF");
            doThrow(new PaymentNotConfirmedException("Payment REJECTED"))
                    .when(creditCardPaymentClient).verifyPaymentConfirmed("CC-REJECTED-REF");

            mockMvc.perform(post("/api/v1/reservations/confirm")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(validRequest)))
                    .andExpect(status().isPaymentRequired());

            // @Transactional rollback means nothing should be in DB
            assertThat(reservationRepository.findAll()).isEmpty();
        }

        @Test
        @DisplayName("Room already booked for overlapping dates → 400, nothing persisted to DB")
        void roomAlreadyBooked_returns400_nothingPersisted() throws Exception {
            // Arrange: existing CONFIRMED reservation for same room and overlapping dates
            reservationRepository.save(Reservation.builder()
                    .customerName("Existing Guest")
                    .roomNumber("303")
                    .startDate(LocalDate.now().plusDays(4))   // overlaps: starts before new booking ends
                    .endDate(LocalDate.now().plusDays(8))     // overlaps: ends after new booking starts
                    .roomSegment(RoomSegment.LARGE)
                    .paymentMode(PaymentMode.CASH)
                    .status(ReservationStatus.CONFIRMED)
                    .build());

            // New request: same room, overlapping dates
            validRequest.setPaymentMode(PaymentMode.CASH);

            mockMvc.perform(post("/api/v1/reservations/confirm")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(validRequest)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.title").value("Invalid Reservation"))
                    .andExpect(jsonPath("$.detail").value("Room 303 is already booked for the requested dates."))
                    .andExpect(jsonPath("$.type").value("urn:marvel:error:invalid-reservation"));

            // Only the pre-existing reservation should be in DB — not a new one
            assertThat(reservationRepository.findAll()).hasSize(1);
        }

        @Test
        @DisplayName("Different room for same dates → allowed, no conflict")
        void differentRoom_sameDates_allowed() throws Exception {
            // Existing confirmed booking for room 303
            reservationRepository.save(Reservation.builder()
                    .customerName("Other Guest")
                    .roomNumber("303")
                    .startDate(LocalDate.now().plusDays(5))
                    .endDate(LocalDate.now().plusDays(10))
                    .roomSegment(RoomSegment.LARGE)
                    .paymentMode(PaymentMode.CASH)
                    .status(ReservationStatus.CONFIRMED)
                    .build());

            // New booking for a different room on the same dates — must be allowed
            validRequest.setRoomNumber("404");
            validRequest.setPaymentMode(PaymentMode.CASH);

            mockMvc.perform(post("/api/v1/reservations/confirm")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(validRequest)))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.status").value("CONFIRMED"));
        }
    }

    // =========================================================================
    // KAFKA → DB: processBankTransferPayment integration
    // =========================================================================

    @Nested
    @DisplayName("Kafka payment event → DB update")
    class KafkaEventIntegration {

        @Test
        @DisplayName("Valid bank-transfer event → status updated to CONFIRMED in DB")
        void bankTransferEvent_updatesDbToConfirmed() {
            Reservation pending = reservationRepository.save(Reservation.builder()
                    .customerName("Natasha Romanoff")
                    .roomNumber("404")
                    .startDate(LocalDate.now().plusDays(7))
                    .endDate(LocalDate.now().plusDays(12))
                    .roomSegment(RoomSegment.SMALL)
                    .paymentMode(PaymentMode.BANK_TRANSFER)
                    .status(ReservationStatus.PENDING_PAYMENT)
                    .build());

            BankTransferPaymentEvent event = new BankTransferPaymentEvent(
                    "PAY-IT-001", "NL91ABNA0417164300",
                    new BigDecimal("500.00"), "1401541457 " + pending.getId());

            reservationService.processBankTransferPayment(event);

            Reservation updated = reservationRepository.findById(pending.getId()).orElseThrow();
            assertThat(updated.getStatus()).isEqualTo(ReservationStatus.CONFIRMED);
        }

        @Test
        @DisplayName("Duplicate Kafka event (already CONFIRMED) → DB stays CONFIRMED, no error")
        void duplicateKafkaEvent_alreadyConfirmed_staysConfirmed() {
            Reservation confirmed = reservationRepository.save(Reservation.builder()
                    .customerName("Bruce Banner")
                    .roomNumber("505")
                    .startDate(LocalDate.now().plusDays(7))
                    .endDate(LocalDate.now().plusDays(12))
                    .roomSegment(RoomSegment.MEDIUM)
                    .paymentMode(PaymentMode.BANK_TRANSFER)
                    .status(ReservationStatus.CONFIRMED)
                    .build());

            BankTransferPaymentEvent event = new BankTransferPaymentEvent(
                    "PAY-IT-DUP", "NL91ABNA0417164300",
                    new BigDecimal("500.00"), "1401541457 " + confirmed.getId());

            reservationService.processBankTransferPayment(event);

            Reservation unchanged = reservationRepository.findById(confirmed.getId()).orElseThrow();
            assertThat(unchanged.getStatus()).isEqualTo(ReservationStatus.CONFIRMED);
        }

        @Test
        @DisplayName("Kafka event with unknown reservationId → DB unchanged, no error")
        void unknownReservationId_dbUnchanged() {
            // Should silently skip — no exception, no DB changes
            BankTransferPaymentEvent event = new BankTransferPaymentEvent(
                    "PAY-IT-UNK", "NL91ABNA0417164300",
                    new BigDecimal("100.00"), "1401541457 NONEXISTENT-ID");

            reservationService.processBankTransferPayment(event);

            assertThat(reservationRepository.findAll()).isEmpty();
        }
    }

    // =========================================================================
    // AUTO-CANCEL SCHEDULER → DB
    // =========================================================================

    @Nested
    @DisplayName("Auto-cancel scheduler → DB update")
    class AutoCancelIntegration {

        @Test
        @DisplayName("Overdue PENDING_PAYMENT bank transfer → cancelled in DB by service")
        void overdueReservation_cancelledByService() {
            Reservation overdue = reservationRepository.save(Reservation.builder()
                    .customerName("Thor Odinson")
                    .roomNumber("606")
                    .startDate(LocalDate.now().plusDays(1))  // within 2-day deadline
                    .endDate(LocalDate.now().plusDays(5))
                    .roomSegment(RoomSegment.EXTRA_LARGE)
                    .paymentMode(PaymentMode.BANK_TRANSFER)
                    .status(ReservationStatus.PENDING_PAYMENT)
                    .build());

            int cancelled = reservationService.cancelOverdueBankTransferReservations();

            assertThat(cancelled).isGreaterThanOrEqualTo(1);
            Reservation updated = reservationRepository.findById(overdue.getId()).orElseThrow();
            assertThat(updated.getStatus()).isEqualTo(ReservationStatus.CANCELLED);
        }

        @Test
        @DisplayName("Scheduler trigger → same result as calling service directly")
        void schedulerTrigger_cancelledInDb() {
            // Verifies the full chain: scheduler → service → repository → DB
            Reservation overdue = reservationRepository.save(Reservation.builder()
                    .customerName("Thor Odinson")
                    .roomNumber("607")
                    .startDate(LocalDate.now().plusDays(1))
                    .endDate(LocalDate.now().plusDays(5))
                    .roomSegment(RoomSegment.EXTRA_LARGE)
                    .paymentMode(PaymentMode.BANK_TRANSFER)
                    .status(ReservationStatus.PENDING_PAYMENT)
                    .build());

            // Simulate the cron trigger firing
            scheduler.autoCancelPendingBankTransferReservations();

            Reservation updated = reservationRepository.findById(overdue.getId()).orElseThrow();
            assertThat(updated.getStatus()).isEqualTo(ReservationStatus.CANCELLED);
        }

        @Test
        @DisplayName("Future reservation (outside 2-day window) → NOT cancelled")
        void futureReservation_notCancelled() {
            Reservation future = reservationRepository.save(Reservation.builder()
                    .customerName("Wanda Maximoff")
                    .roomNumber("707")
                    .startDate(LocalDate.now().plusDays(10))  // safely outside deadline
                    .endDate(LocalDate.now().plusDays(15))
                    .roomSegment(RoomSegment.SMALL)
                    .paymentMode(PaymentMode.BANK_TRANSFER)
                    .status(ReservationStatus.PENDING_PAYMENT)
                    .build());

            reservationService.cancelOverdueBankTransferReservations();

            Reservation unchanged = reservationRepository.findById(future.getId()).orElseThrow();
            assertThat(unchanged.getStatus()).isEqualTo(ReservationStatus.PENDING_PAYMENT);
        }

        @Test
        @DisplayName("CASH reservations never touched by auto-cancel")
        void cashReservation_neverCancelledByScheduler() {
            Reservation cash = reservationRepository.save(Reservation.builder()
                    .customerName("Clint Barton")
                    .roomNumber("808")
                    .startDate(LocalDate.now().plusDays(1))
                    .endDate(LocalDate.now().plusDays(3))
                    .roomSegment(RoomSegment.SMALL)
                    .paymentMode(PaymentMode.CASH)
                    .status(ReservationStatus.CONFIRMED)
                    .build());

            reservationService.cancelOverdueBankTransferReservations();

            Reservation unchanged = reservationRepository.findById(cash.getId()).orElseThrow();
            assertThat(unchanged.getStatus()).isEqualTo(ReservationStatus.CONFIRMED);
        }
    }

    // =========================================================================
    // ERROR RESPONSES END-TO-END
    // =========================================================================

    @Nested
    @DisplayName("Error responses end-to-end (real exception handler)")
    class ErrorResponseIntegration {

        @Test
        @DisplayName("Bean validation → ProblemDetail with no input data -> fieldErrors")
        void beanValidation_returnsProblemDetail() throws Exception {
            mockMvc.perform(post("/api/v1/reservations/confirm")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.title").value("Validation Error"))
                    .andExpect(jsonPath("$.type").value("urn:marvel:error:validation"))
                    .andExpect(jsonPath("$.fieldErrors").isMap())
                    .andExpect(jsonPath("$.timestamp").exists());
        }

        @Test
        @DisplayName("Exceeds 30 days → ProblemDetail 'Invalid Reservation'")
        void exceeds30Days_returnsProblemDetail() throws Exception {
            validRequest.setPaymentMode(PaymentMode.CASH);
            validRequest.setStartDate(LocalDate.now().plusDays(1));
            validRequest.setEndDate(LocalDate.now().plusDays(32));

            mockMvc.perform(post("/api/v1/reservations/confirm")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(validRequest)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.title").value("Invalid Reservation"))
                    .andExpect(jsonPath("$.type").value("urn:marvel:error:invalid-reservation"))
                    .andExpect(jsonPath("$.timestamp").exists());
        }

        @Test
        @DisplayName("Credit card rejected → ProblemDetail 'Payment Not Confirmed'")
        void creditCardRejected_returnsProblemDetail() throws Exception {
            validRequest.setPaymentMode(PaymentMode.CREDIT_CARD);
            validRequest.setPaymentReference("CC-INT-REJECTED");
            doThrow(new PaymentNotConfirmedException("Status: REJECTED"))
                    .when(creditCardPaymentClient).verifyPaymentConfirmed(anyString());

            mockMvc.perform(post("/api/v1/reservations/confirm")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(validRequest)))
                    .andExpect(status().isPaymentRequired())
                    .andExpect(jsonPath("$.title").value("Payment Not Confirmed"))
                    .andExpect(jsonPath("$.type").value("urn:marvel:error:payment-not-confirmed"));
        }
    }

    // =========================================================================
    // REPOSITORY QUERIES DIRECTLY
    // =========================================================================

    @Nested
    @DisplayName("Repository queries")
    class RepositoryQueries {

        @Test
        @DisplayName("findByIdAndStatusAndPaymentMode — returns correct reservation")
        void findByIdAndStatusAndPaymentMode_returnsCorrectResult() {
            Reservation r = reservationRepository.save(Reservation.builder()
                    .customerName("Sam Wilson")
                    .roomNumber("909")
                    .startDate(LocalDate.now().plusDays(5))
                    .endDate(LocalDate.now().plusDays(10))
                    .roomSegment(RoomSegment.MEDIUM)
                    .paymentMode(PaymentMode.BANK_TRANSFER)
                    .status(ReservationStatus.PENDING_PAYMENT)
                    .build());

            var found = reservationRepository.findByIdAndStatusAndPaymentMode(
                    r.getId(), ReservationStatus.PENDING_PAYMENT, PaymentMode.BANK_TRANSFER);

            assertThat(found).isPresent();
            assertThat(found.get().getId()).isEqualTo(r.getId());
        }

        @Test
        @DisplayName("findByIdAndStatusAndPaymentMode — wrong status → empty")
        void findByIdAndStatusAndPaymentMode_wrongStatus_returnsEmpty() {
            Reservation r = reservationRepository.save(Reservation.builder()
                    .customerName("Sam Wilson")
                    .roomNumber("910")
                    .startDate(LocalDate.now().plusDays(5))
                    .endDate(LocalDate.now().plusDays(10))
                    .roomSegment(RoomSegment.MEDIUM)
                    .paymentMode(PaymentMode.BANK_TRANSFER)
                    .status(ReservationStatus.CONFIRMED)
                    .build());

            var found = reservationRepository.findByIdAndStatusAndPaymentMode(
                    r.getId(), ReservationStatus.PENDING_PAYMENT, PaymentMode.BANK_TRANSFER);

            assertThat(found).isEmpty();
        }

        @Test
        @DisplayName("existsByRoomNumber — returns true when overlapping CONFIRMED booking exists")
        void existsByRoomNumber_confirmedOverlap_returnsTrue() {
            reservationRepository.save(Reservation.builder()
                    .customerName("Existing Guest")
                    .roomNumber("111")
                    .startDate(LocalDate.now().plusDays(5))
                    .endDate(LocalDate.now().plusDays(10))
                    .roomSegment(RoomSegment.SMALL)
                    .paymentMode(PaymentMode.CASH)
                    .status(ReservationStatus.CONFIRMED)
                    .build());

            boolean exists = reservationRepository
                    .existsByRoomNumberAndStatusInAndStartDateLessThanEqualAndEndDateGreaterThanEqual(
                            "111",
                            List.of(ReservationStatus.CONFIRMED, ReservationStatus.PENDING_PAYMENT),
                            LocalDate.now().plusDays(10),   // requested endDate
                            LocalDate.now().plusDays(5));   // requested startDate

            assertThat(exists).isTrue();
        }

        @Test
        @DisplayName("existsByRoomNumber — CANCELLED booking does not block, returns false")
        void existsByRoomNumber_cancelledBooking_returnsFalse() {
            reservationRepository.save(Reservation.builder()
                    .customerName("Cancelled Guest")
                    .roomNumber("222")
                    .startDate(LocalDate.now().plusDays(5))
                    .endDate(LocalDate.now().plusDays(10))
                    .roomSegment(RoomSegment.SMALL)
                    .paymentMode(PaymentMode.BANK_TRANSFER)
                    .status(ReservationStatus.CANCELLED)  // cancelled — must not block
                    .build());

            boolean exists = reservationRepository
                    .existsByRoomNumberAndStatusInAndStartDateLessThanEqualAndEndDateGreaterThanEqual(
                            "222",
                            List.of(ReservationStatus.CONFIRMED, ReservationStatus.PENDING_PAYMENT),
                            LocalDate.now().plusDays(10),
                            LocalDate.now().plusDays(5));

            assertThat(exists).isFalse();
        }

        @Test
        @DisplayName("existsByRoomNumber — non-overlapping dates → returns false")
        void existsByRoomNumber_nonOverlappingDates_returnsFalse() {
            reservationRepository.save(Reservation.builder()
                    .customerName("Other Guest")
                    .roomNumber("333")
                    .startDate(LocalDate.now().plusDays(1))
                    .endDate(LocalDate.now().plusDays(3))   // ends before our startDate
                    .roomSegment(RoomSegment.SMALL)
                    .paymentMode(PaymentMode.CASH)
                    .status(ReservationStatus.CONFIRMED)
                    .build());

            boolean exists = reservationRepository
                    .existsByRoomNumberAndStatusInAndStartDateLessThanEqualAndEndDateGreaterThanEqual(
                            "333",
                            List.of(ReservationStatus.CONFIRMED, ReservationStatus.PENDING_PAYMENT),
                            LocalDate.now().plusDays(10),  // our endDate
                            LocalDate.now().plusDays(5));  // our startDate — no overlap

            assertThat(exists).isFalse();
        }

        @Test
        @DisplayName("findAllByPaymentModeAndStatusAndStartDateLessThanEqual — returns only matching records")
        void findOverdueReservations_returnsOnlyMatchingRecords() {
            Reservation overdue = reservationRepository.save(Reservation.builder()
                    .customerName("Peter Parker")
                    .roomNumber("444")
                    .startDate(LocalDate.now().plusDays(1))
                    .endDate(LocalDate.now().plusDays(5))
                    .roomSegment(RoomSegment.SMALL)
                    .paymentMode(PaymentMode.BANK_TRANSFER)
                    .status(ReservationStatus.PENDING_PAYMENT)
                    .build());

            // This one is safely in the future — must not appear in results
            reservationRepository.save(Reservation.builder()
                    .customerName("Miles Morales")
                    .roomNumber("555")
                    .startDate(LocalDate.now().plusDays(20))
                    .endDate(LocalDate.now().plusDays(25))
                    .roomSegment(RoomSegment.SMALL)
                    .paymentMode(PaymentMode.BANK_TRANSFER)
                    .status(ReservationStatus.PENDING_PAYMENT)
                    .build());

            List<Reservation> results = reservationRepository
                    .findAllByPaymentModeAndStatusAndStartDateLessThanEqual(
                            PaymentMode.BANK_TRANSFER,
                            ReservationStatus.PENDING_PAYMENT,
                            LocalDate.now().plusDays(2));

            assertThat(results).hasSize(1);
            assertThat(results.get(0).getId()).isEqualTo(overdue.getId());
        }
    }

    // =========================================================================
    // Helper
    // =========================================================================

    private String extractReservationId(MvcResult result) throws Exception {
        String body = result.getResponse().getContentAsString();
        return objectMapper.readTree(body).get("reservationId").asText();
    }
}