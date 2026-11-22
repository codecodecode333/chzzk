package com.chzzk.common.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.chzzk.BackendApplication;
import com.chzzk.broadcast.BroadcastTestConfig;
import com.chzzk.donation.DonationTestConfig;
import com.chzzk.support.ApiSession;
import java.net.HttpCookie;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * HTTP API seam for the {@code prod} profile behind the public HTTPS proxy (ADR-0006): the login cookie is
 * {@code Secure}, the proxy's forwarded headers are trusted, Toss returns to the public base URL, the 데스크톱 앱
 * origin comes from the environment, and the server refuses to start without its required settings.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"broadcast.publishing-monitor.enabled=false", "donation.expiry.enabled=false",
                "donation.toss.client-key=test_ck_for_tests",
                "site.public-base-url=" + ProdProfileApiTest.PUBLIC_BASE_URL,
                "broadcast.media-server.auth-secret=media-secret-for-tests",
                "desktop-app.origin=" + ProdProfileApiTest.DESKTOP_APP})
@ActiveProfiles("prod")
@Testcontainers
@Import({BroadcastTestConfig.class, DonationTestConfig.class})
class ProdProfileApiTest {

    static final String PUBLIC_BASE_URL = "https://live.example.test";
    static final String DESKTOP_APP = "app://chzzk-prod-test";

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    TestRestTemplate http;

    static Optional<HttpCookie> cookie(ResponseEntity<?> response, String name) {
        List<HttpCookie> cookies = new ArrayList<>();
        response.getHeaders().getOrEmpty(HttpHeaders.SET_COOKIE).forEach(h -> cookies.addAll(HttpCookie.parse(h)));
        return cookies.stream().filter(c -> c.getName().equals(name)).findFirst();
    }

    @Test
    void loginSessionCookieIsSecure() {
        ApiSession visitor = ApiSession.visitor(http);

        ResponseEntity<Map<String, Object>> signedUp = visitor.post("/api/auth/signup",
                Map.of("email", "prod-cookie@example.com", "password", ApiSession.PASSWORD, "nickname", "보안쿠키"));

        assertThat(signedUp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(cookie(signedUp, "SESSION")).hasValueSatisfying(c -> {
            assertThat(c.getSecure()).isTrue();
            assertThat(c.isHttpOnly()).isTrue();
        });
    }

    @Test
    void requestsForwardedAsHttpsByTheProxyAreTreatedAsHttps() {
        HttpHeaders forwarded = new HttpHeaders();
        forwarded.add("X-Forwarded-Proto", "https");
        forwarded.add("X-Forwarded-Host", "live.example.test");

        ResponseEntity<String> csrf = http.exchange("/api/auth/csrf", HttpMethod.GET, new HttpEntity<>(forwarded),
                String.class);

        assertThat(csrf.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(cookie(csrf, "XSRF-TOKEN")).hasValueSatisfying(c -> assertThat(c.getSecure()).isTrue());
    }

    @Test
    void tossReturnsToThePublicBaseUrl() {
        ApiSession owner = ApiSession.member(http);
        ResponseEntity<Map<String, Object>> started = owner.post("/api/broadcasts", Map.of("title", "공개 방송"));
        long broadcastId = ((Number) started.getBody().get("id")).longValue();

        ResponseEntity<Map<String, Object>> ordered = ApiSession.member(http)
                .post("/api/broadcasts/" + broadcastId + "/donations", Map.of("amount", 1000, "message", "응원"));

        assertThat(ordered.getStatusCode()).as("order: %s", ordered.getBody()).isEqualTo(HttpStatus.CREATED);
        String orderId = (String) ordered.getBody().get("orderId");
        assertThat(ordered.getBody())
                .containsEntry("successUrl", "https://live.example.test/donations/success")
                .containsEntry("failUrl", "https://live.example.test/donations/fail?orderId=" + orderId
                        + "&broadcastId=" + broadcastId);
    }

    @Test
    void desktopAppOriginComesFromTheEnvironment() {
        HttpHeaders headers = new HttpHeaders();
        headers.setOrigin(DESKTOP_APP);

        ResponseEntity<String> list = http.exchange("/api/broadcasts", HttpMethod.GET, new HttpEntity<>(headers),
                String.class);

        assertThat(list.getHeaders().getAccessControlAllowOrigin()).isEqualTo(DESKTOP_APP);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "broadcast.media-server.auth-secret | ''                    | MEDIAMTX_AUTH_SECRET",
            "site.public-base-url               | ''                    | PUBLIC_BASE_URL",
            "site.public-base-url               | http://live.example.test | PUBLIC_BASE_URL",
            "spring.datasource.password         | ''                    | DB_PASSWORD"})
    void refusesToStartWithoutARequiredSetting(String property, String value, String envName) {
        SpringApplicationBuilder app = new SpringApplicationBuilder(BackendApplication.class).profiles("prod");
        Map<String, String> settings = new LinkedHashMap<>();
        settings.put("server.port", "0");
        settings.put("broadcast.publishing-monitor.enabled", "false");
        settings.put("donation.expiry.enabled", "false");
        settings.put("spring.datasource.url", postgres.getJdbcUrl());
        settings.put("spring.datasource.username", postgres.getUsername());
        settings.put("spring.datasource.password", postgres.getPassword());
        settings.put("broadcast.media-server.auth-secret", "media-secret-for-tests");
        settings.put("site.public-base-url", PUBLIC_BASE_URL);
        settings.put(property, value);
        // Command-line arguments, as they outrank application-prod.yml.
        String[] args = settings.entrySet().stream().map(s -> "--" + s.getKey() + "=" + s.getValue())
                .toArray(String[]::new);

        assertThatThrownBy(() -> {
            try (ConfigurableApplicationContext ignored = app.run(args)) {
                // started: the assertion below fails
            }
        }).hasStackTraceContaining(envName);
    }
}
