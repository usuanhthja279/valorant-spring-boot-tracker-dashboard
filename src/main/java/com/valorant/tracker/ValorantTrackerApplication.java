package com.valorant.tracker;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableCaching
@EnableScheduling
@SpringBootApplication
public class ValorantTrackerApplication {
    public static void main(String[] a) {
        SpringApplication.run(ValorantTrackerApplication.class,a);
    }
}
