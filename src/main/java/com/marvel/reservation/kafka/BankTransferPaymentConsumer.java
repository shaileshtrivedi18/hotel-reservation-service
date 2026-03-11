package com.marvel.reservation.kafka;

import com.marvel.reservation.dto.BankTransferPaymentEvent;
import com.marvel.reservation.service.ReservationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class BankTransferPaymentConsumer {

    private final ReservationService reservationService;

    @KafkaListener(
            topics = "bank-transfer-payment-update",
            groupId = "${spring.kafka.consumer.group-id}"
    )
    public void onPaymentUpdate(
            @Payload BankTransferPaymentEvent event,
            @Header(KafkaHeaders.RECEIVED_PARTITION) int partition,
            @Header(KafkaHeaders.OFFSET) long offset) {

        log.info("Received bank-transfer payment event: paymentId={}, partition={}, offset={}",
                event.getPaymentId(), partition, offset);

            reservationService.processBankTransferPayment(event);
    }
}