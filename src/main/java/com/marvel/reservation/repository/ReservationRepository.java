package com.marvel.reservation.repository;

import com.marvel.reservation.entity.Reservation;
import com.marvel.reservation.enums.PaymentMode;
import com.marvel.reservation.enums.ReservationStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface ReservationRepository extends JpaRepository<Reservation, String> {

    /**
     * Used by Kafka consumer to match a bank-transfer event
     * to an existing PENDING_PAYMENT reservation.
     */
    Optional<Reservation> findByIdAndStatusAndPaymentMode(
            String id,
            ReservationStatus status,
            PaymentMode paymentMode
    );

    /**
     * Used by auto-cancel scheduler.
     * Finds all BANK_TRANSFER reservations still PENDING_PAYMENT
     * whose start date is on or before the given deadline.
     */
    List<Reservation> findAllByPaymentModeAndStatusAndStartDateLessThanEqual(
            PaymentMode paymentMode,
            ReservationStatus status,
            LocalDate deadlineDate
    );

    /**
     * Used to check if a room is already reserved for the given date range.
     */
    boolean existsByRoomNumberAndStatusInAndStartDateLessThanEqualAndEndDateGreaterThanEqual(
        String roomNumber,
        List<ReservationStatus> statuses,
        LocalDate endDate,
        LocalDate startDate
);
}
