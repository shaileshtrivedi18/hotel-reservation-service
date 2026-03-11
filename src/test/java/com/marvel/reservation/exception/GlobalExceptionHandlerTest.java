package com.marvel.reservation.exception;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.marvel.reservation.controller.ReservationController;
import com.marvel.reservation.service.ReservationService;
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

import static org.hamcrest.Matchers.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Dedicated test for GlobalExceptionHandler.
 *
 * Uses the real controller + exception handler wired by @WebMvcTest,
 * with ReservationService mocked to throw specific exceptions.
 * This directly tests every @ExceptionHandler method and the ProblemDetail shape.
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
class GlobalExceptionHandlerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private ReservationService reservationService;

    // Minimal valid request body — used to get past bean validation
    // so the request reaches the service and triggers the exception we want
    private static final String VALID_BODY = """
            {
              "customerName": "Tony Stark",
              "roomNumber": "101",
              "startDate": "%s",
              "endDate": "%s",
              "roomSegment": "LARGE",
              "paymentMode": "CASH"
            }
            """.formatted(
            java.time.LocalDate.now().plusDays(5),
            java.time.LocalDate.now().plusDays(10)
    );

    // =========================================================================
    // InvalidReservationException → 400
    // =========================================================================

    @Nested
    @DisplayName("InvalidReservationException → 400")
    class InvalidReservationExceptionHandler {

        @Test
        @DisplayName("Returns 400 with ProblemDetail title 'Invalid Reservation'")
        void invalidReservation_returns400WithCorrectTitle() throws Exception {
            when(reservationService.confirmReservation(any()))
                    .thenThrow(new InvalidReservationException("End date must be after start date."));

            mockMvc.perform(post("/api/v1/reservations/confirm")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(VALID_BODY))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.title").value("Invalid Reservation"))
                    .andExpect(jsonPath("$.detail").value("End date must be after start date."))
                    .andExpect(jsonPath("$.type").value("urn:marvel:error:invalid-reservation"))
                    .andExpect(jsonPath("$.timestamp").exists());
        }
    }

    // =========================================================================
    // PaymentNotConfirmedException → 402
    // =========================================================================

    @Nested
    @DisplayName("PaymentNotConfirmedException → 402")
    class PaymentNotConfirmedExceptionHandler {

        @Test
        @DisplayName("Returns 402 with ProblemDetail title 'Payment Not Confirmed'")
        void paymentNotConfirmed_returns402WithCorrectTitle() throws Exception {
            when(reservationService.confirmReservation(any()))
                    .thenThrow(new PaymentNotConfirmedException("Credit card payment REJECTED"));

            mockMvc.perform(post("/api/v1/reservations/confirm")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(VALID_BODY))
                    .andExpect(status().isPaymentRequired())
                    .andExpect(jsonPath("$.title").value("Payment Not Confirmed"))
                    .andExpect(jsonPath("$.detail").value("Credit card payment REJECTED"))
                    .andExpect(jsonPath("$.type").value("urn:marvel:error:payment-not-confirmed"))
                    .andExpect(jsonPath("$.timestamp").exists());
        }
    }

    // =========================================================================
    // MethodArgumentNotValidException → 400 (bean validation)
    // =========================================================================

    @Nested
    @DisplayName("MethodArgumentNotValidException → 400 (bean validation)")
    class ValidationExceptionHandler {

        @Test
        @DisplayName("Returns 400 with ProblemDetail title 'Validation Error' and fieldErrors map")
        void validationError_returns400WithFieldErrorsMap() throws Exception {
            mockMvc.perform(post("/api/v1/reservations/confirm")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))   // empty body triggers @NotNull/@NotBlank on all fields
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.title").value("Validation Error"))
                    .andExpect(jsonPath("$.detail").value("Request validation failed"))
                    .andExpect(jsonPath("$.type").value("urn:marvel:error:validation"))
                    .andExpect(jsonPath("$.fieldErrors").isMap())
                    .andExpect(jsonPath("$.fieldErrors", not(anEmptyMap())))
                    .andExpect(jsonPath("$.timestamp").exists());
        }

        @Test
        @DisplayName("fieldErrors map contains the name of each violating field")
        void validationError_fieldErrorsContainViolatingFields() throws Exception {
            mockMvc.perform(post("/api/v1/reservations/confirm")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.fieldErrors.customerName").exists())
                    .andExpect(jsonPath("$.fieldErrors.roomNumber").exists())
                    .andExpect(jsonPath("$.fieldErrors.startDate").exists())
                    .andExpect(jsonPath("$.fieldErrors.endDate").exists())
                    .andExpect(jsonPath("$.fieldErrors.paymentMode").exists())
                    .andExpect(jsonPath("$.fieldErrors.roomSegment").exists());
        }
    }

    // =========================================================================
    // Generic Exception → 500
    // =========================================================================

    @Nested
    @DisplayName("Unexpected Exception → 500")
    class GenericExceptionHandler {

        @Test
        @DisplayName("Returns 500 with ProblemDetail title 'Internal Server Error'")
        void unexpectedException_returns500WithCorrectTitle() throws Exception {
            when(reservationService.confirmReservation(any()))
                    .thenThrow(new RuntimeException("Unexpected DB connection failure"));

            mockMvc.perform(post("/api/v1/reservations/confirm")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(VALID_BODY))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.title").value("Internal Server Error"))
                    .andExpect(jsonPath("$.detail").value("An unexpected error occurred"))
                    .andExpect(jsonPath("$.type").value("urn:marvel:error:internal"))
                    .andExpect(jsonPath("$.timestamp").exists());
        }

        @Test
        @DisplayName("Returns 500 — internal detail (DB message) is NOT leaked to client")
        void unexpectedException_internalDetailNotLeakedToClient() throws Exception {
            when(reservationService.confirmReservation(any()))
                    .thenThrow(new RuntimeException("password=supersecret host=internal-db-host"));

            mockMvc.perform(post("/api/v1/reservations/confirm")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(VALID_BODY))
                    .andExpect(status().isInternalServerError())
                    // Must NOT expose the raw exception message
                    .andExpect(jsonPath("$.detail").value("An unexpected error occurred"))
                    .andExpect(jsonPath("$.detail", not(containsString("supersecret"))))
                    .andExpect(jsonPath("$.detail", not(containsString("internal-db-host"))));
        }
    }
}