package com.cinemahall;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.time.Clock;

@SpringBootApplication
@EnableScheduling
@EnableJpaRepositories(considerNestedRepositories = true) // small repositories are grouped per module
public class CinemaHallApplication {

    public static void main(String[] args) {
        SpringApplication.run(CinemaHallApplication.class, args);
    }

    /** All time-dependent rules read this clock, so tests can move time forward instead of sleeping. */
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
