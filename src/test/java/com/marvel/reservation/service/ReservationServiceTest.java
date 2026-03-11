package com.marvel.reservation.service;

import com.marvel.reservation.client.CreditCardPaymentClient;
import com.marvel.reservation.dto.BankTransferPaymentEvent;
import com.marvel.reservation.dto.ReservationRequest;
import com.marvel.reservation.dto.ReservationResponse;
import com.marvel.reservation.entity.Reservation;
import com.marvel.reservation.enums.PaymentMode;
import com.marvel.reservation.enums.ReservationStatus;
import com.marvel.reservation.enums.RoomSegment;
import com.marvel.reservation.exception.InvalidReservationException;
import com.marvel.reservation.exception.PaymentNotConfirmedException;
import com.marvel.reservation.repository.ReservationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ReservationServiceTest {

    @Mock
    private ReservationRepository reservationRepository;

    @Mock
    private CreditCardPaymentClient creditCardPaymentClient;

    @InjectMocks
    private ReservationService reservationService;

    private ReservationRequest baseRequest;

    @BeforeEach
    void setUp() {
        baseRequest = new ReservationRequest();
        baseRequest.setCustomerName("Tony Stark");
        baseRequest.setRoomNumber("101");
        baseRequest.setStartDate(LocalDate.now().plusDays(5));
        baseRequest.setEndDate(LocalDate.now().plusDays(10));
        baseRequest.setRoomSegment(RoomSegment.LARGE);
    }

    // =========================================================================
    // CASH
    // =========================================================================

    @Nested
    @DisplayName("CASH payment")
    class CashPayment {

        @BeforeEach
        void stubAvailable() {
            stubRoomAvailable();  // ← only runs for tests in this nested class
        }

        @Test
        @DisplayName("Cash → confirmed immediately, no credit card client called")
        void cash_confirmsImmediately_noCreditCardInteraction() {
            baseRequest.setPaymentMode(PaymentMode.CASH);
            when(reservationRepository.save(any()))
                    .thenReturn(buildReservation("RES001", ReservationStatus.CONFIRMED, PaymentMode.CASH));

            ReservationResponse response = reservationService.confirmReservation(baseRequest);

            assertThat(response.status()).isEqualTo(ReservationStatus.CONFIRMED);
            verifyNoInteractions(creditCardPaymentClient);
        }

        @Test
        @DisplayName("Cash → persists reservation with CONFIRMED status and correct paymentMode")
        void cash_persistsCorrectStatus() {
            baseRequest.setPaymentMode(PaymentMode.CASH);
            when(reservationRepository.save(any()))
                    .thenReturn(buildReservation("RES001", ReservationStatus.CONFIRMED, PaymentMode.CASH));

            reservationService.confirmReservation(baseRequest);

            ArgumentCaptor<Reservation> captor = ArgumentCaptor.forClass(Reservation.class);
            verify(reservationRepository).save(captor.capture());
            assertThat(captor.getValue().getStatus()).isEqualTo(ReservationStatus.CONFIRMED);
            assertThat(captor.getValue().getPaymentMode()).isEqualTo(PaymentMode.CASH);
        }
    }

    // =========================================================================
    // CREDIT CARD
    // =========================================================================

    @Nested
    @DisplayName("CREDIT_CARD payment")
    class CreditCardPayment {

        // @BeforeEach
        // void stubAvailable() {
        //     stubRoomAvailable();
        // }

        @Test
        @DisplayName("Credit card confirmed by external service → CONFIRMED")
        void creditCard_confirmed() {
            baseRequest.setPaymentMode(PaymentMode.CREDIT_CARD);
            baseRequest.setPaymentReference("CC-REF-123");

            stubRoomAvailable();

            doNothing().when(creditCardPaymentClient).verifyPaymentConfirmed("CC-REF-123");
            when(reservationRepository.save(any()))
                    .thenReturn(buildReservation("RES002", ReservationStatus.CONFIRMED, PaymentMode.CREDIT_CARD));

            ReservationResponse response = reservationService.confirmReservation(baseRequest);

            assertThat(response.status()).isEqualTo(ReservationStatus.CONFIRMED);
            verify(creditCardPaymentClient).verifyPaymentConfirmed("CC-REF-123");
        }

        @Test
        @DisplayName("Credit card confirmed → persists with CONFIRMED status and paymentReference")
        void creditCard_persistsCorrectStatusAndReference() {
            baseRequest.setPaymentMode(PaymentMode.CREDIT_CARD);
            baseRequest.setPaymentReference("CC-REF-123");

            stubRoomAvailable();

            doNothing().when(creditCardPaymentClient).verifyPaymentConfirmed(any());
            when(reservationRepository.save(any()))
                    .thenReturn(buildReservation("RES002", ReservationStatus.CONFIRMED, PaymentMode.CREDIT_CARD));

            reservationService.confirmReservation(baseRequest);

            ArgumentCaptor<Reservation> captor = ArgumentCaptor.forClass(Reservation.class);
            verify(reservationRepository).save(captor.capture());
            assertThat(captor.getValue().getStatus()).isEqualTo(ReservationStatus.CONFIRMED);
            assertThat(captor.getValue().getPaymentReference()).isEqualTo("CC-REF-123");
        }

        @Test
        @DisplayName("Credit card rejected by external service → PaymentNotConfirmedException, no save")
        void creditCard_rejected_noSave() {
            baseRequest.setPaymentMode(PaymentMode.CREDIT_CARD);
            baseRequest.setPaymentReference("CC-REJECTED");

            stubRoomAvailable();

            doThrow(new PaymentNotConfirmedException("Payment REJECTED"))
                    .when(creditCardPaymentClient).verifyPaymentConfirmed("CC-REJECTED");

            assertThatThrownBy(() -> reservationService.confirmReservation(baseRequest))
                    .isInstanceOf(PaymentNotConfirmedException.class)
                    .hasMessageContaining("REJECTED");

            verify(reservationRepository, never()).save(any());
        }

        @Test
        @DisplayName("Credit card — null reference → InvalidReservationException, no client call")
        void creditCard_nullReference_throws() {
            baseRequest.setPaymentMode(PaymentMode.CREDIT_CARD);
            baseRequest.setPaymentReference(null);

            assertThatThrownBy(() -> reservationService.confirmReservation(baseRequest))
                    .isInstanceOf(InvalidReservationException.class)
                    .hasMessageContaining("Payment reference is required");

            verifyNoInteractions(creditCardPaymentClient);
            verify(reservationRepository, never()).save(any());
        }

        @ParameterizedTest(name = "reference=''{0}''")
        @ValueSource(strings = {"", "   ", "\t", "\n"})
        @DisplayName("Credit card — blank/whitespace reference → InvalidReservationException")
        void creditCard_blankReference_throws(String blankRef) {
            baseRequest.setPaymentMode(PaymentMode.CREDIT_CARD);
            baseRequest.setPaymentReference(blankRef);

            assertThatThrownBy(() -> reservationService.confirmReservation(baseRequest))
                    .isInstanceOf(InvalidReservationException.class)
                    .hasMessageContaining("Payment reference is required");

            verifyNoInteractions(creditCardPaymentClient);
            verify(reservationRepository, never()).save(any());
        }
    }

    // =========================================================================
    // BANK TRANSFER
    // =========================================================================

    @Nested
    @DisplayName("BANK_TRANSFER payment")
    class BankTransferPayment {

        @BeforeEach
        void stubAvailable() {
            stubRoomAvailable();
        }

        @Test
        @DisplayName("Bank transfer → PENDING_PAYMENT, no credit card client called")
        void bankTransfer_savedAsPending_noCreditCardInteraction() {
            baseRequest.setPaymentMode(PaymentMode.BANK_TRANSFER);
            when(reservationRepository.save(any()))
                    .thenReturn(buildReservation("RES003", ReservationStatus.PENDING_PAYMENT, PaymentMode.BANK_TRANSFER));

            ReservationResponse response = reservationService.confirmReservation(baseRequest);

            assertThat(response.status()).isEqualTo(ReservationStatus.PENDING_PAYMENT);
            verifyNoInteractions(creditCardPaymentClient);
        }

        @Test
        @DisplayName("Bank transfer → persists with PENDING_PAYMENT status")
        void bankTransfer_persistsCorrectStatus() {
            baseRequest.setPaymentMode(PaymentMode.BANK_TRANSFER);
            when(reservationRepository.save(any()))
                    .thenReturn(buildReservation("RES003", ReservationStatus.PENDING_PAYMENT, PaymentMode.BANK_TRANSFER));

            reservationService.confirmReservation(baseRequest);

            ArgumentCaptor<Reservation> captor = ArgumentCaptor.forClass(Reservation.class);
            verify(reservationRepository).save(captor.capture());
            assertThat(captor.getValue().getStatus()).isEqualTo(ReservationStatus.PENDING_PAYMENT);
            assertThat(captor.getValue().getPaymentMode()).isEqualTo(PaymentMode.BANK_TRANSFER);
        }
    }

    // =========================================================================
    // ROOM AVAILABILITY
    // =========================================================================

    @Nested
    @DisplayName("Room availability check")
    class RoomAvailability {

        @Test
        @DisplayName("Room already booked for overlapping dates → InvalidReservationException, no save")
        void roomAlreadyBooked_throws() {
            stubRoomNotAvailable();
            baseRequest.setPaymentMode(PaymentMode.CASH);

            assertThatThrownBy(() -> reservationService.confirmReservation(baseRequest))
                    .isInstanceOf(InvalidReservationException.class)
                    .hasMessageContaining("already booked");

            verify(reservationRepository, never()).save(any());
            verifyNoInteractions(creditCardPaymentClient);
        }

        @Test
        @DisplayName("Room available → proceeds to save normally")
        void roomAvailable_proceedsNormally() {
            baseRequest.setPaymentMode(PaymentMode.CASH);
            // stubRoomAvailable() is already called in @BeforeEach — explicit here for clarity
            //stubRoomAvailable();
            when(reservationRepository.save(any()))
                    .thenReturn(buildReservation("RES001", ReservationStatus.CONFIRMED, PaymentMode.CASH));

            ReservationResponse response = reservationService.confirmReservation(baseRequest);

            assertThat(response.status()).isEqualTo(ReservationStatus.CONFIRMED);
            verify(reservationRepository).save(any());
        }

        @Test
        @DisplayName("Room booked check uses correct room number from request")
        void roomAvailabilityCheck_usesCorrectRoomNumber() {
            baseRequest.setPaymentMode(PaymentMode.CASH);
            baseRequest.setRoomNumber("999");
            when(reservationRepository.save(any()))
                    .thenReturn(buildReservation("RES001", ReservationStatus.CONFIRMED, PaymentMode.CASH));

            reservationService.confirmReservation(baseRequest);

            verify(reservationRepository)
                    .existsByRoomNumberAndStatusInAndStartDateLessThanEqualAndEndDateGreaterThanEqual(
                            eq("999"), any(), any(), any());
        }

        @Test
        @DisplayName("Room booked check blocks CONFIRMED and PENDING_PAYMENT — not CANCELLED")
        void roomAvailabilityCheck_usesCorrectBlockingStatuses() {
            baseRequest.setPaymentMode(PaymentMode.CASH);
            when(reservationRepository.save(any()))
                    .thenReturn(buildReservation("RES001", ReservationStatus.CONFIRMED, PaymentMode.CASH));

            reservationService.confirmReservation(baseRequest);

            @SuppressWarnings("unchecked")
            ArgumentCaptor<List<ReservationStatus>> statusCaptor =
                    ArgumentCaptor.forClass(List.class);
            verify(reservationRepository)
                    .existsByRoomNumberAndStatusInAndStartDateLessThanEqualAndEndDateGreaterThanEqual(
                            any(), statusCaptor.capture(), any(), any());

            assertThat(statusCaptor.getValue())
                    .containsExactlyInAnyOrder(
                            ReservationStatus.CONFIRMED,
                            ReservationStatus.PENDING_PAYMENT)
                    .doesNotContain(ReservationStatus.CANCELLED);
        }
    }

    // =========================================================================
    // DATE VALIDATIONS
    // =========================================================================

    @Nested
    @DisplayName("Date validations")
    class DateValidations {

        @Test
        @DisplayName("End date before start date → InvalidReservationException")
        void endDateBeforeStartDate_throws() {
            baseRequest.setPaymentMode(PaymentMode.CASH);
            baseRequest.setStartDate(LocalDate.now().plusDays(10));
            baseRequest.setEndDate(LocalDate.now().plusDays(5));

            assertThatThrownBy(() -> reservationService.confirmReservation(baseRequest))
                    .isInstanceOf(InvalidReservationException.class)
                    .hasMessageContaining("End date must be after start date");
        }

        @Test
        @DisplayName("Same start and end date → InvalidReservationException")
        void sameStartAndEndDate_throws() {
            baseRequest.setPaymentMode(PaymentMode.CASH);
            LocalDate sameDay = LocalDate.now().plusDays(5);
            baseRequest.setStartDate(sameDay);
            baseRequest.setEndDate(sameDay);

            assertThatThrownBy(() -> reservationService.confirmReservation(baseRequest))
                    .isInstanceOf(InvalidReservationException.class)
                    .hasMessageContaining("End date must be after start date");
        }

        @Test
        @DisplayName("31+ days reservation → InvalidReservationException")
        void exceeds30Days_throws() {
            baseRequest.setPaymentMode(PaymentMode.CASH);
            baseRequest.setStartDate(LocalDate.now().plusDays(1));
            baseRequest.setEndDate(LocalDate.now().plusDays(32));

            assertThatThrownBy(() -> reservationService.confirmReservation(baseRequest))
                    .isInstanceOf(InvalidReservationException.class)
                    .hasMessageContaining("30 days");
        }

        @Test
        @DisplayName("Exactly 30 days → allowed (upper boundary)")
        void exactly30Days_isAllowed() {
            baseRequest.setPaymentMode(PaymentMode.CASH);
            baseRequest.setStartDate(LocalDate.now().plusDays(1));
            baseRequest.setEndDate(LocalDate.now().plusDays(31));
            when(reservationRepository.save(any()))
                    .thenReturn(buildReservation("RES004", ReservationStatus.CONFIRMED, PaymentMode.CASH));

            assertThat(reservationService.confirmReservation(baseRequest)).isNotNull();
        }

        @Test
        @DisplayName("1 day reservation → allowed (lower boundary)")
        void oneDayReservation_isAllowed() {
            baseRequest.setPaymentMode(PaymentMode.CASH);
            baseRequest.setStartDate(LocalDate.now().plusDays(1));
            baseRequest.setEndDate(LocalDate.now().plusDays(2));
            when(reservationRepository.save(any()))
                    .thenReturn(buildReservation("RES005", ReservationStatus.CONFIRMED, PaymentMode.CASH));

            assertThat(reservationService.confirmReservation(baseRequest)).isNotNull();
        }
    }

    // =========================================================================
    // KAFKA EVENT PROCESSING
    // =========================================================================

    @Nested
    @DisplayName("processBankTransferPayment")
    class ProcessBankTransferPayment {

        @Test
        @DisplayName("Valid event → reservation CONFIRMED and saved")
        void validEvent_confirmsReservation() {
            BankTransferPaymentEvent event = new BankTransferPaymentEvent(
                    "PAY001", "NL91ABNA0417164300", new BigDecimal("250.00"), "1401541457 RES003");

            Reservation pending = buildReservation("RES003", ReservationStatus.PENDING_PAYMENT, PaymentMode.BANK_TRANSFER);
            when(reservationRepository.findByIdAndStatusAndPaymentMode(
                    "RES003", ReservationStatus.PENDING_PAYMENT, PaymentMode.BANK_TRANSFER))
                    .thenReturn(Optional.of(pending));

            reservationService.processBankTransferPayment(event);

            assertThat(pending.getStatus()).isEqualTo(ReservationStatus.CONFIRMED);
            verify(reservationRepository).save(pending);
        }

        @Test
        @DisplayName("Null transactionDescription → silently skipped, no DB call")
        void nullDescription_skipped() {
            BankTransferPaymentEvent event = new BankTransferPaymentEvent(
                    "PAY002", "NL91ABNA0417164300", new BigDecimal("250.00"), null);

            reservationService.processBankTransferPayment(event);

            verifyNoInteractions(reservationRepository);
        }

        @Test
        @DisplayName("Blank transactionDescription → silently skipped, no DB call")
        void blankDescription_skipped() {
            BankTransferPaymentEvent event = new BankTransferPaymentEvent(
                    "PAY003", "NL91ABNA0417164300", new BigDecimal("250.00"), "   ");

            reservationService.processBankTransferPayment(event);

            verifyNoInteractions(reservationRepository);
        }

        @Test
        @DisplayName("Unknown reservationId → silently skipped, no save")
        void unknownReservationId_skipped() {
            BankTransferPaymentEvent event = new BankTransferPaymentEvent(
                    "PAY004", "NL91ABNA0417164300", new BigDecimal("250.00"), "1401541457 UNKNOWN1");

            when(reservationRepository.findByIdAndStatusAndPaymentMode(any(), any(), any()))
                    .thenReturn(Optional.empty());

            reservationService.processBankTransferPayment(event);

            verify(reservationRepository, never()).save(any());
        }

        @Test
        @DisplayName("Duplicate Kafka event (reservation already CONFIRMED) → silently skipped, no save")
        void duplicateEvent_alreadyConfirmed_skipped() {
            BankTransferPaymentEvent event = new BankTransferPaymentEvent(
                    "PAY005", "NL91ABNA0417164300", new BigDecimal("250.00"), "1401541457 RES003");

            when(reservationRepository.findByIdAndStatusAndPaymentMode(
                    "RES003", ReservationStatus.PENDING_PAYMENT, PaymentMode.BANK_TRANSFER))
                    .thenReturn(Optional.empty());

            reservationService.processBankTransferPayment(event);

            verify(reservationRepository, never()).save(any());
        }
    }

    // =========================================================================
    // AUTO-CANCEL
    // =========================================================================

    @Nested
    @DisplayName("cancelOverdueBankTransferReservations")
    class AutoCancel {

        @Test
        @DisplayName("Multiple overdue reservations → all CANCELLED, correct count returned")
        void overdueReservations_cancelledAndCountReturned() {
            Reservation r1 = buildReservation("RES005", ReservationStatus.PENDING_PAYMENT, PaymentMode.BANK_TRANSFER);
            Reservation r2 = buildReservation("RES006", ReservationStatus.PENDING_PAYMENT, PaymentMode.BANK_TRANSFER);

            when(reservationRepository.findAllByPaymentModeAndStatusAndStartDateLessThanEqual(
                    eq(PaymentMode.BANK_TRANSFER), eq(ReservationStatus.PENDING_PAYMENT), any(LocalDate.class)))
                    .thenReturn(List.of(r1, r2));

            int count = reservationService.cancelOverdueBankTransferReservations();

            assertThat(count).isEqualTo(2);
            assertThat(r1.getStatus()).isEqualTo(ReservationStatus.CANCELLED);
            assertThat(r2.getStatus()).isEqualTo(ReservationStatus.CANCELLED);
            verify(reservationRepository).saveAll(List.of(r1, r2));
        }

        @Test
        @DisplayName("No overdue reservations → returns 0, saveAll never called")
        void noOverdueReservations_returnsZero() {
            when(reservationRepository.findAllByPaymentModeAndStatusAndStartDateLessThanEqual(
                    any(), any(), any())).thenReturn(List.of());

            assertThat(reservationService.cancelOverdueBankTransferReservations()).isEqualTo(0);
            verify(reservationRepository, never()).saveAll(any());
        }

        @Test
        @DisplayName("Deadline passed to repo is exactly today + 2 days")
        void deadlineDate_isTodayPlusTwoDays() {
            when(reservationRepository.findAllByPaymentModeAndStatusAndStartDateLessThanEqual(
                    any(), any(), any())).thenReturn(List.of());

            reservationService.cancelOverdueBankTransferReservations();

            ArgumentCaptor<LocalDate> dateCaptor = ArgumentCaptor.forClass(LocalDate.class);
            verify(reservationRepository).findAllByPaymentModeAndStatusAndStartDateLessThanEqual(
                    eq(PaymentMode.BANK_TRANSFER),
                    eq(ReservationStatus.PENDING_PAYMENT),
                    dateCaptor.capture());

            assertThat(dateCaptor.getValue()).isEqualTo(LocalDate.now().plusDays(2));
        }

        @Test
        @DisplayName("Single overdue reservation → cancelled, count is 1")
        void singleOverdueReservation_cancelledCorrectly() {
            Reservation r = buildReservation("RES007", ReservationStatus.PENDING_PAYMENT, PaymentMode.BANK_TRANSFER);

            when(reservationRepository.findAllByPaymentModeAndStatusAndStartDateLessThanEqual(
                    any(), any(), any())).thenReturn(List.of(r));

            int count = reservationService.cancelOverdueBankTransferReservations();

            assertThat(count).isEqualTo(1);
            assertThat(r.getStatus()).isEqualTo(ReservationStatus.CANCELLED);
        }
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    /** Stubs the room-availability check to return "room is free" (the happy path default). */
    private void stubRoomAvailable() {
        when(reservationRepository
                .existsByRoomNumberAndStatusInAndStartDateLessThanEqualAndEndDateGreaterThanEqual(
                        any(), any(), any(), any()))
                .thenReturn(false);
    }

    /** Stubs the room-availability check to return "room is already booked". */
    private void stubRoomNotAvailable() {
        when(reservationRepository
                .existsByRoomNumberAndStatusInAndStartDateLessThanEqualAndEndDateGreaterThanEqual(
                        any(), any(), any(), any()))
                .thenReturn(true);
    }

    private Reservation buildReservation(String id, ReservationStatus status, PaymentMode paymentMode) {
        return Reservation.builder()
                .id(id)
                .customerName("Tony Stark")
                .roomNumber("101")
                .startDate(LocalDate.now().plusDays(5))
                .endDate(LocalDate.now().plusDays(10))
                .roomSegment(RoomSegment.LARGE)
                .paymentMode(paymentMode)
                .status(status)
                .build();
    }
}