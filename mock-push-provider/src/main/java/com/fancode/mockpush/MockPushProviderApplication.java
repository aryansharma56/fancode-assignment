package com.fancode.mockpush;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class MockPushProviderApplication {

    public static void main(String[] args) {
        System.exit(SpringApplication.exit(SpringApplication.run(MockPushProviderApplication.class, args)));
    }
}
