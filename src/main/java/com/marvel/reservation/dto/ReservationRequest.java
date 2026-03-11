package com.marvel.reservation.dto;

import com.marvel.reservation.enums.PaymentMode;
import com.marvel.reservation.enums.RoomSegment;
import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.time.LocalDate;

@Data
public class ReservationRequest {

    @NotBlank(message = "Customer name is required")
    private String customerName;

    @NotBlank(message = "Room number is required")
    private String roomNumber;

    @NotNull(message = "Reservation start date is required")
    @Future(message = "Start date must be in the future")
    private LocalDate startDate;

    @NotNull(message = "Reservation end date is required")
    @Future(message = "End date must be in the future")
    private LocalDate endDate;

    @NotNull(message = "Room segment is required")
    private RoomSegment roomSegment;

    @NotNull(message = "Payment mode is required")
    private PaymentMode paymentMode;

    /** Required for CREDIT_CARD payments; optional for others. */
    private String paymentReference;
}
