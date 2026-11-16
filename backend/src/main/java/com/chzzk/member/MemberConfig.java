package com.chzzk.member;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/** Wiring for the member module. */
@Configuration(proxyBeanMethods = false)
class MemberConfig {

    /** Passwords are stored only as BCrypt hashes. */
    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
