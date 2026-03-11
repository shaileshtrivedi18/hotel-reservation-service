package com.marvel.reservation.scheduler;

import com.marvel.reservation.service.ReservationService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ReservationAutoCancelSchedulerTest {

    @Mock
    private ReservationService reservationService;

    @InjectMocks
    private ReservationAutoCancelScheduler scheduler;

    @Test
    @DisplayName("Scheduler delegates to service and completes without error")
    void autoCancelJob_delegatesToService() {
        when(reservationService.cancelOverdueBankTransferReservations()).thenReturn(3);

        scheduler.autoCancelPendingBankTransferReservations();

        verify(reservationService).cancelOverdueBankTransferReservations();
    }

    @Test
    @DisplayName("Scheduler completes normally when no reservations to cancel (returns 0)")
    void autoCancelJob_noReservations_completesNormally() {
        when(reservationService.cancelOverdueBankTransferReservations()).thenReturn(0);

        scheduler.autoCancelPendingBankTransferReservations();

        verify(reservationService).cancelOverdueBankTransferReservations();
    }

    @Test
    @DisplayName("Scheduler calls service exactly once per trigger — no duplicate calls")
    void autoCancelJob_callsServiceExactlyOnce() {
        when(reservationService.cancelOverdueBankTransferReservations()).thenReturn(0);

        scheduler.autoCancelPendingBankTransferReservations();

        verify(reservationService, times(1)).cancelOverdueBankTransferReservations();
        verifyNoMoreInteractions(reservationService);
    }
}