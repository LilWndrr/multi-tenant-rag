package com.example.worker;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

@TestConfiguration(proxyBeanMethods = false)
public class FakeGatewayConfiguration {

    @Bean
    @Primary
    FakeModelGateway fakeModelGateway() {
        return new FakeModelGateway();
    }
}
