package com.marvel.reservation.dto;

import com.marvel.reservation.enums.ReservationStatus;

/**
 * API response for reservation confirmation.
 * Java Record — immutable, no boilerplate (Java 16+).
 */
public record ReservationResponse(String reservationId, ReservationStatus status) {}
