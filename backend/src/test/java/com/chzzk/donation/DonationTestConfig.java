package com.chzzk.donation;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/** Replaces Toss with the scriptable {@link FakePaymentGateway}. */
@TestConfiguration(proxyBeanMethods = false)
public class DonationTestConfig {

    @Bean
    @Primary
    FakePaymentGateway fakePaymentGateway() {
        return new FakePaymentGateway();
    }
}
