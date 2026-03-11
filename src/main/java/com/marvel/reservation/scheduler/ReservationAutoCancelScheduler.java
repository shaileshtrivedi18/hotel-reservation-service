package com.marvel.reservation.scheduler;

import com.marvel.reservation.service.ReservationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Scheduled job that automatically cancels BANK_TRANSFER reservations
 * still in PENDING_PAYMENT when check-in is 2 or fewer days away.
 *
 * Cron configurable via: reservation.auto-cancel.cron
 * Default: daily at 02:00 AM
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReservationAutoCancelScheduler {

    private final ReservationService reservationService;

    @Scheduled(cron = "${reservation.auto-cancel.cron:0 0 2 * * *}")
    public void autoCancelPendingBankTransferReservations() {
        log.info("Auto-cancel scheduler started.");
        int cancelled = reservationService.cancelOverdueBankTransferReservations();
        log.info("Auto-cancel scheduler finished. Cancelled {} reservation(s).", cancelled);
    }
}
