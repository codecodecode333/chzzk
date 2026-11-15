package com.chzzk.broadcast;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * HTTP API seam for 방송: full Spring context against Testcontainers PostgreSQL, with the media server gateway faked
 * and the clock injectable (see {@link BroadcastTestConfig}).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@Import(BroadcastTestConfig.class)
class BroadcastApiTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    TestRestTemplate http;

    @Autowired
    MutableClock clock;

    @Autowired
    FakeMediaServerGateway mediaServer;

    static final ParameterizedTypeReference<Map<String, Object>> JSON = new ParameterizedTypeReference<>() {};

    ResponseEntity<Map<String, Object>> start(String title) {
        return http.exchange("/api/broadcasts", HttpMethod.POST, new HttpEntity<>(Map.of("title", title)), JSON);
    }

    @Test
    void startingCreatesALiveBroadcastAndIssuesItsPublishKey() {
        clock.set(Instant.parse("2026-11-15T10:00:00Z"));

        ResponseEntity<Map<String, Object>> response = start("  첫 방송  ");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        Map<String, Object> body = response.getBody();
        Object id = body.get("id");
        assertThat(response.getHeaders().getLocation()).hasToString("/api/broadcasts/" + id);
        assertThat(body)
                .containsEntry("title", "첫 방송")
                .containsEntry("status", "LIVE")
                .containsEntry("startedAt", "2026-11-15T10:00:00Z")
                .containsEntry("whipUrl", "/live/broadcasts/" + id + "/whip");
        String publishKey = (String) body.get("publishKey");
        assertThat(Base64.getUrlDecoder().decode(publishKey)).hasSize(32);
        assertThat(publishKey).doesNotContain("=", "+", "/");
    }

    ResponseEntity<Map<String, Object>> detail(Object id) {
        return http.exchange("/api/broadcasts/" + id, HttpMethod.GET, null, JSON);
    }

    long startedId(String title) {
        return ((Number) start(title).getBody().get("id")).longValue();
    }

    /** What MediaMTX sends to the internal auth callback for one WHIP (publish) or WHEP (read) request. */
    HttpStatus mediaServerAsks(String action, String path, String token) {
        Map<String, Object> request = Map.of("ip", "172.17.0.1", "user", "", "password", "", "token", token,
                "action", action, "path", path, "protocol", "webrtc", "id", "56b4d011-daa4-4214-b30e-1e9fe721cf63",
                "query", "", "userAgent", "Mozilla/5.0");
        ResponseEntity<String> response = http.postForEntity("/internal/mediamtx/auth", request, String.class);
        assertThat(response.getBody()).as("MediaMTX accepts only an empty or JSON body").isNull();
        return HttpStatus.valueOf(response.getStatusCode().value());
    }

    ResponseEntity<Map<String, Object>> end(Object id, String publishKey) {
        HttpHeaders headers = new HttpHeaders();
        if (publishKey != null) headers.setBearerAuth(publishKey);
        return http.exchange("/api/broadcasts/" + id + "/end", HttpMethod.POST, new HttpEntity<>(headers), JSON);
    }

    static void assertProblem(ResponseEntity<Map<String, Object>> response, HttpStatus status) {
        assertThat(response.getStatusCode()).isEqualTo(status);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(response.getBody())
                .containsEntry("status", status.value())
                .containsKeys("type", "title", "detail", "instance");
    }

    @Test
    void invalidTitleIsRejectedNamingTheField() {
        for (String title : List.of("", "  	 ", "가".repeat(101))) {
            ResponseEntity<Map<String, Object>> response = start(title);

            assertProblem(response, HttpStatus.BAD_REQUEST);
            assertThat(response.getBody().get("errors").toString()).as(title).contains("field=title");
        }
    }

    @Test
    void liveBroadcastDetailHasItsWhepUrlButNeverThePublishKey() {
        clock.set(Instant.parse("2026-11-15T11:00:00Z"));
        long id = startedId("시청할 방송");

        ResponseEntity<Map<String, Object>> response = detail(id);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody())
                .containsEntry("id", (int) id)
                .containsEntry("title", "시청할 방송")
                .containsEntry("status", "LIVE")
                .containsEntry("startedAt", "2026-11-15T11:00:00Z")
                .containsEntry("endedAt", null)
                .containsEntry("whepUrl", "/live/broadcasts/" + id + "/whep")
                .doesNotContainKey("publishKey")
                .doesNotContainKey("whipUrl");
    }

    @Test
    void endedBroadcastDetailHasNoWhepUrl() {
        clock.set(Instant.parse("2026-11-15T12:00:00Z"));
        Map<String, Object> started = start("끝난 방송").getBody();
        Object id = started.get("id");
        clock.set(Instant.parse("2026-11-15T12:30:00Z"));
        end(id, (String) started.get("publishKey"));

        ResponseEntity<Map<String, Object>> response = detail(id);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody())
                .containsEntry("status", "ENDED")
                .containsEntry("endedAt", "2026-11-15T12:30:00Z")
                .containsEntry("whepUrl", null);
    }

    @Test
    void detailOfUnknownBroadcastIsProblemDetails404() {
        assertProblem(detail(987654321), HttpStatus.NOT_FOUND);
        assertProblem(detail("abc"), HttpStatus.BAD_REQUEST);
    }

    @Test
    void mediaServerMayAcceptPublishingOnlyWithTheRightKeyToALiveBroadcast() {
        Map<String, Object> started = start("송출 권한").getBody();
        long id = ((Number) started.get("id")).longValue();
        String key = (String) started.get("publishKey");
        String otherKey = (String) start("다른 방송").getBody().get("publishKey");

        assertThat(mediaServerAsks("publish", "broadcasts/" + id, key)).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(mediaServerAsks("publish", "broadcasts/" + id, otherKey)).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(mediaServerAsks("publish", "broadcasts/" + id, "")).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(mediaServerAsks("publish", "broadcasts/987654321", key)).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(mediaServerAsks("publish", "elsewhere/" + id, key)).isEqualTo(HttpStatus.UNAUTHORIZED);

        end(id, key);
        assertThat(mediaServerAsks("publish", "broadcasts/" + id, key)).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void mediaServerMayLetAnyoneWatchOnlyALiveBroadcast() {
        Map<String, Object> started = start("시청 권한").getBody();
        long id = ((Number) started.get("id")).longValue();

        assertThat(mediaServerAsks("read", "broadcasts/" + id, "")).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(mediaServerAsks("read", "broadcasts/987654321", "")).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(mediaServerAsks("playback", "broadcasts/" + id, "")).isEqualTo(HttpStatus.UNAUTHORIZED);

        end(id, (String) started.get("publishKey"));
        assertThat(mediaServerAsks("read", "broadcasts/" + id, "")).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void endingWithThePublishKeyEndsTheBroadcastAndCutsThePublisher() {
        clock.set(Instant.parse("2026-11-15T13:00:00Z"));
        Map<String, Object> started = start("끝낼 방송").getBody();
        long id = ((Number) started.get("id")).longValue();
        mediaServer.startPublishing(id);
        clock.set(Instant.parse("2026-11-15T13:42:00Z"));

        ResponseEntity<Map<String, Object>> response = end(id, (String) started.get("publishKey"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(detail(id).getBody())
                .containsEntry("status", "ENDED")
                .containsEntry("endedAt", "2026-11-15T13:42:00Z")
                .containsEntry("whepUrl", null);
        assertThat(mediaServer.kicked()).contains(id);
    }

    @Test
    void onlyThePublishKeyHolderCanEndABroadcast() {
        long id = startedId("남이 못 끄는 방송");
        String otherKey = (String) start("다른 방송").getBody().get("publishKey");

        assertProblem(end(id, null), HttpStatus.UNAUTHORIZED);
        assertProblem(end(id, otherKey), HttpStatus.UNAUTHORIZED);

        assertThat(detail(id).getBody()).containsEntry("status", "LIVE");
        assertThat(mediaServer.kicked()).doesNotContain(id);
    }

    @Test
    void endingAnUnknownBroadcastIsProblemDetails404() {
        String key = (String) start("아무 방송").getBody().get("publishKey");

        assertProblem(end(987654321, key), HttpStatus.NOT_FOUND);
    }

    @Test
    void endingTwiceIsHarmless() {
        clock.set(Instant.parse("2026-11-15T14:00:00Z"));
        Map<String, Object> started = start("두 번 끄기").getBody();
        Object id = started.get("id");
        String key = (String) started.get("publishKey");
        end(id, key);
        clock.set(Instant.parse("2026-11-15T14:05:00Z"));

        ResponseEntity<Map<String, Object>> again = end(id, key);

        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(detail(id).getBody())
                .containsEntry("status", "ENDED")
                .containsEntry("endedAt", "2026-11-15T14:00:00Z");
    }
}
