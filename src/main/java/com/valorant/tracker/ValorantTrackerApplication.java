package com.valorant.tracker;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling @SpringBootApplication
public class ValorantTrackerApplication {
    public static void main(String[] a) {
        SpringApplication.run(ValorantTrackerApplication.class,a);
    }
}
