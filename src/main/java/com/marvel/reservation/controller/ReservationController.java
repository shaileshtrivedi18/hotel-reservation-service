package com.marvel.reservation.controller;

import com.marvel.reservation.dto.ReservationRequest;
import com.marvel.reservation.dto.ReservationResponse;
import com.marvel.reservation.service.ReservationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
@RequestMapping("/api/v1/reservations")
@RequiredArgsConstructor
public class ReservationController {

    private final ReservationService reservationService;

    /**
     * POST /api/v1/reservations/confirm
     *
     * Confirms a room reservation.
     * - CASH:          Returns CONFIRMED immediately.
     * - CREDIT_CARD:   Calls credit-card-payment-service; returns CONFIRMED or 402.
     * - BANK_TRANSFER: Returns PENDING_PAYMENT.
     */
    @PostMapping("/confirm")
    public ResponseEntity<ReservationResponse> confirmReservation(
            @Valid @RequestBody ReservationRequest request) {

        log.info("Received reservation request for customer={}, room={}, paymentMode={}",
                request.getCustomerName(), request.getRoomNumber(), request.getPaymentMode());

        ReservationResponse response = reservationService.confirmReservation(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }
}
