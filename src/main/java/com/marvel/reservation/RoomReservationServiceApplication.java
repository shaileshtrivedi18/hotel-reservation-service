package com.marvel.reservation;

import com.marvel.reservation.config.CreditCardServiceProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties(CreditCardServiceProperties.class)
public class RoomReservationServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(RoomReservationServiceApplication.class, args);
    }
}
