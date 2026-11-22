package com.chzzk.common.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.chzzk.broadcast.BroadcastTestConfig;
import com.chzzk.support.ApiSession;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * HTTP API seam for the 데스크톱 앱's cross-origin reads (ADR-0006): its own origin (default {@code app://chzzk}) may
 * preflight and {@code GET} the public 방송 list and detail, without credentials. Every other origin, endpoint and
 * method gets no CORS allowance, so a browser keeps refusing them.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "broadcast.publishing-monitor.enabled=false")
@Testcontainers
@Import(BroadcastTestConfig.class)
class DesktopAppCorsApiTest {

    static final String DESKTOP_APP = "app://chzzk";
    static final String OTHER_SITE = "https://evil.example";

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    TestRestTemplate http;

    long liveBroadcast() {
        ResponseEntity<Map<String, Object>> started = ApiSession.member(http)
                .post("/api/broadcasts", Map.of("title", "데스크톱 앱으로 볼 방송"));
        assertThat(started.getStatusCode()).as("start: %s", started.getBody()).isEqualTo(HttpStatus.CREATED);
        return ((Number) started.getBody().get("id")).longValue();
    }

    ResponseEntity<String> preflight(String path, String origin, String method) {
        HttpHeaders headers = new HttpHeaders();
        headers.setOrigin(origin);
        headers.setAccessControlRequestMethod(HttpMethod.valueOf(method));
        return http.exchange(path, HttpMethod.OPTIONS, new HttpEntity<>(headers), String.class);
    }

    ResponseEntity<String> request(String path, String origin, HttpMethod method) {
        HttpHeaders headers = new HttpHeaders();
        headers.setOrigin(origin);
        return http.exchange(path, method, new HttpEntity<>(headers), String.class);
    }

    static void assertAllowedFor(ResponseEntity<String> response, String origin) {
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getAccessControlAllowOrigin()).isEqualTo(origin);
        assertThat(response.getHeaders().getFirst(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS)).isNull();
    }

    static void assertNoCorsAllowance(ResponseEntity<String> response) {
        assertThat(response.getHeaders().getAccessControlAllowOrigin()).isNull();
        assertThat(response.getHeaders().getAccessControlAllowMethods()).isEmpty();
    }

    @Test
    void desktopAppMayPreflightTheBroadcastListAndDetail() {
        long id = liveBroadcast();

        for (String path : new String[] {"/api/broadcasts", "/api/broadcasts/" + id}) {
            ResponseEntity<String> response = preflight(path, DESKTOP_APP, "GET");

            assertAllowedFor(response, DESKTOP_APP);
            assertThat(response.getHeaders().getAccessControlAllowMethods()).as(path).containsExactly(HttpMethod.GET);
        }
    }

    @Test
    void desktopAppReadsTheBroadcastListAndDetailCrossOrigin() {
        long id = liveBroadcast();

        ResponseEntity<String> list = request("/api/broadcasts?status=LIVE", DESKTOP_APP, HttpMethod.GET);
        ResponseEntity<String> detail = request("/api/broadcasts/" + id, DESKTOP_APP, HttpMethod.GET);

        assertAllowedFor(list, DESKTOP_APP);
        assertThat(list.getBody()).contains("데스크톱 앱으로 볼 방송");
        assertAllowedFor(detail, DESKTOP_APP);
        assertThat(detail.getBody()).contains("데스크톱 앱으로 볼 방송");
    }

    @Test
    void otherOriginsGetNoCorsAllowance() {
        long id = liveBroadcast();

        assertNoCorsAllowance(preflight("/api/broadcasts", OTHER_SITE, "GET"));
        assertNoCorsAllowance(request("/api/broadcasts", OTHER_SITE, HttpMethod.GET));
        assertNoCorsAllowance(request("/api/broadcasts/" + id, OTHER_SITE, HttpMethod.GET));
    }

    @Test
    void desktopAppGetsNoCorsForOtherEndpoints() {
        long id = liveBroadcast();

        for (String path : new String[] {"/api/vods", "/api/me", "/api/auth/csrf",
                "/api/broadcasts/" + id + "/donations", "/api/broadcasts/" + id + "/comments",
                "/healthz", "/actuator/health"}) {
            assertNoCorsAllowance(preflight(path, DESKTOP_APP, "GET"));
            assertNoCorsAllowance(request(path, DESKTOP_APP, HttpMethod.GET));
        }
    }

    @Test
    void desktopAppGetsNoCorsForMethodsOtherThanGet() {
        long id = liveBroadcast();

        assertNoCorsAllowance(preflight("/api/broadcasts", DESKTOP_APP, "POST"));
        assertNoCorsAllowance(preflight("/api/broadcasts/" + id, DESKTOP_APP, "PATCH"));
        assertNoCorsAllowance(preflight("/api/broadcasts/" + id, DESKTOP_APP, "DELETE"));
        assertNoCorsAllowance(request("/api/broadcasts/" + id, DESKTOP_APP, HttpMethod.DELETE));
    }

    @Test
    void sameOriginWritesThroughAProxyStillWork() {
        // The SPA's own POSTs carry its Origin; behind a proxy that origin differs from the backend's own address.
        ApiSession owner = ApiSession.member(http);
        HttpHeaders headers = new HttpHeaders();
        headers.setOrigin("http://localhost:5173");
        headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);

        ResponseEntity<Map<String, Object>> started = owner.exchange("/api/broadcasts", HttpMethod.POST,
                new HttpEntity<>(Map.of("title", "프록시 뒤의 방송"), headers), ApiSession.JSON);

        assertThat(started.getStatusCode()).as("start: %s", started.getBody()).isEqualTo(HttpStatus.CREATED);
    }
}
