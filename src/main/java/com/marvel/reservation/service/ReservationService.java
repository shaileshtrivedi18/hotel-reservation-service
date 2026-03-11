package com.marvel.reservation.service;

import com.marvel.reservation.client.CreditCardPaymentClient;
import com.marvel.reservation.dto.BankTransferPaymentEvent;
import com.marvel.reservation.dto.ReservationRequest;
import com.marvel.reservation.dto.ReservationResponse;
import com.marvel.reservation.entity.Reservation;
import com.marvel.reservation.enums.PaymentMode;
import com.marvel.reservation.enums.ReservationStatus;
import com.marvel.reservation.exception.InvalidReservationException;
import com.marvel.reservation.repository.ReservationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class ReservationService {

    private static final int MAX_RESERVATION_DAYS = 30;
    private static final int DAYS_BEFORE_CHECKIN_CANCELLATION = 2;

    private final ReservationRepository reservationRepository;
    private final CreditCardPaymentClient creditCardPaymentClient;

    /**
     * Confirms a room reservation based on payment mode:
     * - CASH:          CONFIRMED immediately
     * - CREDIT_CARD:   Calls credit-card-payment-service; CONFIRMED or throws
     * - BANK_TRANSFER: PENDING_PAYMENT until update arrives via Kafka event
     */
    @Transactional
    public ReservationResponse confirmReservation(ReservationRequest request) {
        validateReservationDates(request.getStartDate(), request.getEndDate());
        validateCreditCardReference(request);
        ensureRoomIsAvailable(request);

        ReservationStatus status = switch (request.getPaymentMode()) {
            case CASH -> ReservationStatus.CONFIRMED;
            case CREDIT_CARD -> {
                creditCardPaymentClient.verifyPaymentConfirmed(request.getPaymentReference());
                yield ReservationStatus.CONFIRMED;
            }
            case BANK_TRANSFER -> ReservationStatus.PENDING_PAYMENT;
        };

        Reservation reservation = Reservation.builder()
                .customerName(request.getCustomerName())
                .roomNumber(request.getRoomNumber())
                .startDate(request.getStartDate())
                .endDate(request.getEndDate())
                .roomSegment(request.getRoomSegment())
                .paymentMode(request.getPaymentMode())
                .paymentReference(request.getPaymentReference())
                .status(status)
                .build();

        Reservation saved = reservationRepository.save(reservation);

        log.info("Reservation created: id={}, status={}, paymentMode={}",
                saved.getId(), saved.getStatus(), saved.getPaymentMode());

        return new ReservationResponse(saved.getId(), saved.getStatus());
    }


/**
 * Checks if the room is already booked for the requested dates.
 * If it is, throws an InvalidReservationException.
 */
private void ensureRoomIsAvailable(ReservationRequest request) {
    var blockingStatuses = java.util.List.of(
            ReservationStatus.CONFIRMED,
            ReservationStatus.PENDING_PAYMENT
    );

    boolean alreadyBooked = reservationRepository
            .existsByRoomNumberAndStatusInAndStartDateLessThanEqualAndEndDateGreaterThanEqual(
                    request.getRoomNumber(),
                    blockingStatuses,
                    request.getEndDate(),
                    request.getStartDate()
            );

    if (alreadyBooked) {
        throw new InvalidReservationException(
                "Room " + request.getRoomNumber() + " is already booked for the requested dates.");
    }
}

    /**
     * Processes a bank-transfer Kafka event.
     * Parses reservationId from transactionDescription and confirms the reservation.
     */
    @Transactional
    public void processBankTransferPayment(BankTransferPaymentEvent event) {
        String reservationId = event.extractReservationId();

        if (reservationId == null) {
            log.warn("Could not extract reservationId from transactionDescription: '{}'",
                    event.getTransactionDescription());
            return;
        }

        reservationRepository
                .findByIdAndStatusAndPaymentMode(
                        reservationId,
                        ReservationStatus.PENDING_PAYMENT,
                        PaymentMode.BANK_TRANSFER)
                .ifPresentOrElse(reservation -> {

                    reservation.setStatus(ReservationStatus.CONFIRMED);
                    reservationRepository.save(reservation);

                    log.info("Reservation {} confirmed via bank transfer. PaymentId={}",
                            reservationId, event.getPaymentId());
                }, () -> log.warn(
                        "No PENDING_PAYMENT bank-transfer reservation found for id={}. " +
                        "Event may be duplicate or already processed.", reservationId));
    }

    /**
     * Cancels all BANK_TRANSFER reservations in PENDING_PAYMENT
     * whose start date is within 2 days. Called by the scheduler.
     */
    @Transactional
    public int cancelOverdueBankTransferReservations() {
        LocalDate deadline = LocalDate.now().plusDays(DAYS_BEFORE_CHECKIN_CANCELLATION);

        List<Reservation> overdue = reservationRepository
                .findAllByPaymentModeAndStatusAndStartDateLessThanEqual(
                        PaymentMode.BANK_TRANSFER,
                        ReservationStatus.PENDING_PAYMENT,
                        deadline);

        if (overdue.isEmpty()) {
            log.debug("Auto-cancel job: no overdue bank-transfer reservations found.");
            return 0;
        }

        overdue.forEach(r -> {
            r.setStatus(ReservationStatus.CANCELLED);
            log.info("Auto-cancelling reservation {} (startDate={}) due to unpaid bank transfer.",
                    r.getId(), r.getStartDate());
        });

        reservationRepository.saveAll(overdue);

        log.info("Auto-cancel job: cancelled {} reservation(s).", overdue.size());
        return overdue.size();
    }

    // ─── Private helpers ─────────────────────────────────────────────────────

    private void validateReservationDates(LocalDate startDate, LocalDate endDate) {
        if (!endDate.isAfter(startDate)) {
            throw new InvalidReservationException("End date must be after start date.");
        }

        long days = ChronoUnit.DAYS.between(startDate, endDate);
        if (days > MAX_RESERVATION_DAYS) {
            throw new InvalidReservationException(
                    "A room cannot be reserved for more than " + MAX_RESERVATION_DAYS +
                    " days. Requested: " + days + " days.");
        }
    }

    private void validateCreditCardReference(ReservationRequest request) {
        if (request.getPaymentMode() == PaymentMode.CREDIT_CARD &&
                (request.getPaymentReference() == null || request.getPaymentReference().isBlank())) {
            throw new InvalidReservationException(
                    "Payment reference is required for CREDIT_CARD payment mode.");
        }
    }
}
