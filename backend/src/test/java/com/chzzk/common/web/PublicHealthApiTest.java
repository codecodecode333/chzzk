package com.chzzk.common.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.chzzk.support.ApiSession;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * HTTP API seam for the public overall health check (ADR-0006): {@code /healthz} answers without login with only
 * UP or DOWN, and the database is part of it. The liveness and readiness probes stay as they were.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"broadcast.publishing-monitor.enabled=false", "donation.expiry.enabled=false",
                "spring.datasource.hikari.connection-timeout=2000"})
@Testcontainers
class PublicHealthApiTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    TestRestTemplate http;

    @Test
    void overallHealthSaysOnlyUpOrDownAndFollowsTheDatabase() {
        ApiSession visitor = ApiSession.visitor(http);

        ResponseEntity<Map<String, Object>> up = visitor.get("/healthz");

        assertThat(up.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(up.getBody()).containsExactly(Map.entry("status", "UP"));

        postgres.stop();
        ResponseEntity<Map<String, Object>> down = visitor.get("/healthz");
        ResponseEntity<Map<String, Object>> liveness = visitor.get("/actuator/health/liveness");

        assertThat(down.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(down.getBody()).containsExactly(Map.entry("status", "DOWN"));
        assertThat(liveness.getStatusCode()).isEqualTo(HttpStatus.OK);
    }
}
