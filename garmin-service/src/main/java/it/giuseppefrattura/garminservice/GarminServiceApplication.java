package it.giuseppefrattura.garminservice;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.retry.annotation.EnableRetry;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableRetry
@EnableScheduling
public class GarminServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(GarminServiceApplication.class, args);
    }
}
