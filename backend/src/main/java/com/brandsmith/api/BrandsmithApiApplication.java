package com.brandsmith.api;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class BrandsmithApiApplication {

    public static void main(String[] args) {
        SpringApplication.run(BrandsmithApiApplication.class, args);
    }
}
