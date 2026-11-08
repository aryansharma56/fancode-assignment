package com.fancode.mockpoll;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class MockPollProviderApplication {

    public static void main(String[] args) {
        SpringApplication.run(MockPollProviderApplication.class, args);
    }
}
