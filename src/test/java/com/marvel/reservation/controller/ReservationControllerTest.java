package com.marvel.reservation.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.marvel.reservation.dto.ReservationRequest;
import com.marvel.reservation.dto.ReservationResponse;
import com.marvel.reservation.enums.PaymentMode;
import com.marvel.reservation.enums.ReservationStatus;
import com.marvel.reservation.enums.RoomSegment;
import com.marvel.reservation.exception.InvalidReservationException;
import com.marvel.reservation.exception.PaymentNotConfirmedException;
import com.marvel.reservation.service.ReservationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;

import static org.hamcrest.Matchers.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Controller slice test — verifies HTTP contract:
 * status codes, response body shape, ProblemDetail error structure.
 */
@WebMvcTest(
        value = ReservationController.class,
        excludeAutoConfiguration = KafkaAutoConfiguration.class,
        excludeFilters = @ComponentScan.Filter(
                type = FilterType.ASSIGNABLE_TYPE,
                classes = {
                        com.marvel.reservation.config.RestClientConfig.class,
                        com.marvel.reservation.client.CreditCardPaymentClient.class
                }
        )
)
@ActiveProfiles("test")
class ReservationControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private ReservationService reservationService;

    private ReservationRequest validCashRequest;

    @BeforeEach
    void setUp() {
        validCashRequest = new ReservationRequest();
        validCashRequest.setCustomerName("Bruce Banner");
        validCashRequest.setRoomNumber("202");
        validCashRequest.setStartDate(LocalDate.now().plusDays(3));
        validCashRequest.setEndDate(LocalDate.now().plusDays(7));
        validCashRequest.setRoomSegment(RoomSegment.MEDIUM);
        validCashRequest.setPaymentMode(PaymentMode.CASH);
    }

    // =========================================================================
    // HAPPY PATHS — all 3 payment modes
    // =========================================================================

    @Nested
    @DisplayName("Happy paths")
    class HappyPaths {

        @Test
        @DisplayName("CASH → 201 with CONFIRMED status and reservationId in body")
        void cash_returns201WithConfirmedStatus() throws Exception {
            when(reservationService.confirmReservation(any()))
                    .thenReturn(new ReservationResponse("RES00001", ReservationStatus.CONFIRMED));

            mockMvc.perform(post("/api/v1/reservations/confirm")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(validCashRequest)))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.reservationId").value("RES00001"))
                    .andExpect(jsonPath("$.status").value("CONFIRMED"));
        }

        @Test
        @DisplayName("BANK_TRANSFER → 201 with PENDING_PAYMENT status")
        void bankTransfer_returns201WithPendingStatus() throws Exception {
            validCashRequest.setPaymentMode(PaymentMode.BANK_TRANSFER);

            when(reservationService.confirmReservation(any()))
                    .thenReturn(new ReservationResponse("RES00002", ReservationStatus.PENDING_PAYMENT));

            mockMvc.perform(post("/api/v1/reservations/confirm")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(validCashRequest)))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.reservationId").value("RES00002"))
                    .andExpect(jsonPath("$.status").value("PENDING_PAYMENT"));
        }

        @Test
        @DisplayName("CREDIT_CARD confirmed → 201 with CONFIRMED status")
        void creditCard_confirmed_returns201() throws Exception {
            validCashRequest.setPaymentMode(PaymentMode.CREDIT_CARD);
            validCashRequest.setPaymentReference("CC-REF-9876");

            when(reservationService.confirmReservation(any()))
                    .thenReturn(new ReservationResponse("RES00003", ReservationStatus.CONFIRMED));

            mockMvc.perform(post("/api/v1/reservations/confirm")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(validCashRequest)))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.reservationId").value("RES00003"))
                    .andExpect(jsonPath("$.status").value("CONFIRMED"));
        }
    }

    // =========================================================================
    // BUSINESS RULE ERRORS — ProblemDetail body assertions
    // =========================================================================

    @Nested
    @DisplayName("Business rule errors — ProblemDetail body")
    class BusinessRuleErrors {

        @Test
        @DisplayName("CREDIT_CARD rejected → 402 with ProblemDetail title and detail")
        void creditCard_rejected_returns402WithProblemDetail() throws Exception {
            validCashRequest.setPaymentMode(PaymentMode.CREDIT_CARD);
            validCashRequest.setPaymentReference("CC-BAD");

            when(reservationService.confirmReservation(any()))
                    .thenThrow(new PaymentNotConfirmedException("Credit card payment REJECTED"));

            mockMvc.perform(post("/api/v1/reservations/confirm")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(validCashRequest)))
                    .andExpect(status().isPaymentRequired())
                    .andExpect(jsonPath("$.title").value("Payment Not Confirmed"))
                    .andExpect(jsonPath("$.detail").value("Credit card payment REJECTED"))
                    .andExpect(jsonPath("$.timestamp").exists());
        }

        @Test
        @DisplayName("Exceeds 30 days → 400 with ProblemDetail title and detail")
        void exceeds30Days_returns400WithProblemDetail() throws Exception {
            when(reservationService.confirmReservation(any()))
                    .thenThrow(new InvalidReservationException("A room cannot be reserved for more than 30 days."));

            mockMvc.perform(post("/api/v1/reservations/confirm")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(validCashRequest)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.title").value("Invalid Reservation"))
                    .andExpect(jsonPath("$.detail").value("A room cannot be reserved for more than 30 days."))
                    .andExpect(jsonPath("$.timestamp").exists());
        }
    }

    // =========================================================================
    // VALIDATION ERRORS — @NotNull / @NotBlank / @Future fields
    // =========================================================================

    @Nested
    @DisplayName("Bean validation errors — missing required fields")
    class ValidationErrors {

        @Test
        @DisplayName("Missing customerName → 400 with fieldErrors.customerName")
        void missingCustomerName_returns400WithFieldError() throws Exception {
            validCashRequest.setCustomerName(null);

            mockMvc.perform(post("/api/v1/reservations/confirm")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(validCashRequest)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.title").value("Validation Error"))
                    .andExpect(jsonPath("$.fieldErrors.customerName").exists());
        }

        @Test
        @DisplayName("Missing roomNumber → 400 with fieldErrors.roomNumber")
        void missingRoomNumber_returns400WithFieldError() throws Exception {
            validCashRequest.setRoomNumber(null);

            mockMvc.perform(post("/api/v1/reservations/confirm")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(validCashRequest)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.fieldErrors.roomNumber").exists());
        }

        @Test
        @DisplayName("Missing startDate → 400 with fieldErrors.startDate")
        void missingStartDate_returns400WithFieldError() throws Exception {
            validCashRequest.setStartDate(null);

            mockMvc.perform(post("/api/v1/reservations/confirm")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(validCashRequest)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.fieldErrors.startDate").exists());
        }

        @Test
        @DisplayName("Missing endDate → 400 with fieldErrors.endDate")
        void missingEndDate_returns400WithFieldError() throws Exception {
            validCashRequest.setEndDate(null);

            mockMvc.perform(post("/api/v1/reservations/confirm")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(validCashRequest)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.fieldErrors.endDate").exists());
        }

        @Test
        @DisplayName("Missing paymentMode → 400 with fieldErrors.paymentMode")
        void missingPaymentMode_returns400WithFieldError() throws Exception {
            validCashRequest.setPaymentMode(null);

            mockMvc.perform(post("/api/v1/reservations/confirm")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(validCashRequest)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.fieldErrors.paymentMode").exists());
        }

        @Test
        @DisplayName("Missing roomSegment → 400 with fieldErrors.roomSegment")
        void missingRoomSegment_returns400WithFieldError() throws Exception {
            validCashRequest.setRoomSegment(null);

            mockMvc.perform(post("/api/v1/reservations/confirm")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(validCashRequest)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.fieldErrors.roomSegment").exists());
        }

        @Test
        @DisplayName("Past startDate → 400 (@Future constraint)")
        void pastStartDate_returns400() throws Exception {
            validCashRequest.setStartDate(LocalDate.now().minusDays(1));

            mockMvc.perform(post("/api/v1/reservations/confirm")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(validCashRequest)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.fieldErrors.startDate").exists());
        }

        @Test
        @DisplayName("Multiple missing fields → 400 with all fieldErrors present")
        void multipleFieldsMissing_allErrorsReturned() throws Exception {
            validCashRequest.setCustomerName(null);
            validCashRequest.setRoomNumber(null);

            mockMvc.perform(post("/api/v1/reservations/confirm")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(validCashRequest)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.fieldErrors", aMapWithSize(greaterThanOrEqualTo(2))))
                    .andExpect(jsonPath("$.fieldErrors.customerName").exists())
                    .andExpect(jsonPath("$.fieldErrors.roomNumber").exists());
        }
    }

    // =========================================================================
    // MALFORMED / BAD REQUESTS
    // =========================================================================

    @Nested
    @DisplayName("Malformed requests")
    class MalformedRequests {

        @Test
        @DisplayName("Malformed JSON body → 400")
        void malformedJson_returns400() throws Exception {
            mockMvc.perform(post("/api/v1/reservations/confirm")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{ this is not valid json }"))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("Wrong Content-Type (text/plain) → 415 Unsupported Media Type")
        void wrongContentType_returns415() throws Exception {
            mockMvc.perform(post("/api/v1/reservations/confirm")
                            .contentType(MediaType.TEXT_PLAIN)
                            .content("some text"))
                    .andExpect(status().isUnsupportedMediaType());
        }

        @Test
        @DisplayName("Empty body → 400")
        void emptyBody_returns400() throws Exception {
            mockMvc.perform(post("/api/v1/reservations/confirm")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andExpect(status().isBadRequest());
        }
    }
}