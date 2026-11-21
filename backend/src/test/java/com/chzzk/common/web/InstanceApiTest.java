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
 * HTTP API seam for running as one of many backend instances (ADR-0005): every response names the instance that
 * served it, and only the health probes are reachable under actuator.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "instance.name=backend-7f9c-x2k4q")
@Testcontainers
class InstanceApiTest {

    static final String INSTANCE = "backend-7f9c-x2k4q";

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    TestRestTemplate http;

    @Test
    void publicResponsesNameTheServingInstance() {
        ResponseEntity<Map<String, Object>> response = ApiSession.visitor(http).get("/api/broadcasts?status=LIVE");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getFirst("X-Served-By")).isEqualTo(INSTANCE);
    }

    @Test
    void authenticatedResponsesNameTheServingInstance() {
        ResponseEntity<Map<String, Object>> response = ApiSession.member(http).get("/api/me");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getFirst("X-Served-By")).isEqualTo(INSTANCE);
    }

    @Test
    void errorResponsesNameTheServingInstance() {
        ResponseEntity<Map<String, Object>> unauthenticated = ApiSession.visitor(http).get("/api/me");
        ResponseEntity<Map<String, Object>> csrfRefused = ApiSession.member(http).withoutCsrfToken()
                .post("/api/auth/logout", Map.of());
        ResponseEntity<Map<String, Object>> notFound = ApiSession.visitor(http).get("/api/vods/999999");

        assertThat(unauthenticated.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(csrfRefused.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(notFound.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(unauthenticated.getHeaders().getFirst("X-Served-By")).isEqualTo(INSTANCE);
        assertThat(csrfRefused.getHeaders().getFirst("X-Served-By")).isEqualTo(INSTANCE);
        assertThat(notFound.getHeaders().getFirst("X-Served-By")).isEqualTo(INSTANCE);
    }

    @Test
    void livenessAndReadinessProbesAnswerWithoutLogin() {
        ResponseEntity<Map<String, Object>> liveness = ApiSession.visitor(http).get("/actuator/health/liveness");
        ResponseEntity<Map<String, Object>> readiness = ApiSession.visitor(http).get("/actuator/health/readiness");

        assertThat(liveness.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(liveness.getBody()).containsEntry("status", "UP");
        assertThat(readiness.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(readiness.getBody()).containsEntry("status", "UP");
        assertThat(readiness.getHeaders().getFirst("X-Served-By")).isEqualTo(INSTANCE);
    }

    @Test
    void noOtherManagementEndpointIsReachable() {
        ApiSession visitor = ApiSession.visitor(http);

        for (String path : new String[] {"/actuator", "/actuator/info", "/actuator/env", "/actuator/beans",
                "/actuator/metrics", "/actuator/configprops", "/actuator/loggers", "/actuator/mappings",
                "/actuator/heapdump", "/actuator/threaddump", "/actuator/shutdown"}) {
            assertThat(visitor.get(path).getStatusCode()).as(path).isIn(HttpStatus.NOT_FOUND, HttpStatus.UNAUTHORIZED);
        }
        assertThat(visitor.post("/actuator/shutdown", Map.of()).getStatusCode())
                .isIn(HttpStatus.NOT_FOUND, HttpStatus.UNAUTHORIZED, HttpStatus.FORBIDDEN);
    }
}
