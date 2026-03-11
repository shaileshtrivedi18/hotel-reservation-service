package com.marvel.reservation.kafka;

import com.marvel.reservation.dto.BankTransferPaymentEvent;
import com.marvel.reservation.service.ReservationService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;

import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BankTransferPaymentConsumerTest {

    @Mock
    private ReservationService reservationService;

    @InjectMocks
    private BankTransferPaymentConsumer consumer;

    @Test
    @DisplayName("Valid event is delegated to the service")
    void onPaymentUpdate_validEvent_delegatesToService() {
        BankTransferPaymentEvent event = new BankTransferPaymentEvent(
                "PAY001", "NL91ABNA0417164300", new BigDecimal("300.00"), "1401541457 RES00001");

        consumer.onPaymentUpdate(event, 0, 1L);

        verify(reservationService).processBankTransferPayment(event);
    }

@Test
@DisplayName("Service throws RuntimeException → consumer logs and rethrows for framework retry")
void onPaymentUpdate_serviceThrows_rethrowsException() {
    BankTransferPaymentEvent event = new BankTransferPaymentEvent(
            "PAY002", "NL91ABNA0417164300", new BigDecimal("300.00"), "1401541457 RES00002");

    doThrow(new RuntimeException("DB error"))
            .when(reservationService).processBankTransferPayment(event);

    assertThatThrownBy(() -> consumer.onPaymentUpdate(event, 0, 2L))
            .isInstanceOf(RuntimeException.class)
            .hasMessage("DB error");

    verify(reservationService).processBankTransferPayment(event);
}
}
