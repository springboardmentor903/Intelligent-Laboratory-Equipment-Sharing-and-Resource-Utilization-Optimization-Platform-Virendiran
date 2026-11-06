package com.labresource.platform.auth;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class LabResourceApplication {
    public static void main(String[] args) {
        SpringApplication.run(LabResourceApplication.class, args);
    }
}
