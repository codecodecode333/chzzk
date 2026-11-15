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
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
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
    JdbcTemplate jdbc;

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

    /**
     * Test setup only: ends a 방송 directly in the database, standing in for the end API and auto-end that arrive in
     * later tickets. Assertions still go through HTTP.
     */
    void endInDatabase(long id, Instant endedAt) {
        jdbc.update("UPDATE broadcast SET status = 'ENDED', ended_at = ? WHERE id = ?",
                java.sql.Timestamp.from(endedAt), id);
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
        long id = startedId("끝난 방송");
        endInDatabase(id, Instant.parse("2026-11-15T12:30:00Z"));

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

        endInDatabase(id, clock.instant());
        assertThat(mediaServerAsks("publish", "broadcasts/" + id, key)).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void mediaServerMayLetAnyoneWatchOnlyALiveBroadcast() {
        long id = startedId("시청 권한");

        assertThat(mediaServerAsks("read", "broadcasts/" + id, "")).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(mediaServerAsks("read", "broadcasts/987654321", "")).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(mediaServerAsks("playback", "broadcasts/" + id, "")).isEqualTo(HttpStatus.UNAUTHORIZED);

        endInDatabase(id, clock.instant());
        assertThat(mediaServerAsks("read", "broadcasts/" + id, "")).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    /** Test setup only: start from an empty broadcast table so list contents and totals are predictable. */
    void emptyBroadcastTable() {
        jdbc.update("DELETE FROM broadcast");
    }

    ResponseEntity<Map<String, Object>> list(String query) {
        return http.exchange("/api/broadcasts" + query, HttpMethod.GET, null, JSON);
    }

    @SuppressWarnings("unchecked")
    static List<String> titles(ResponseEntity<Map<String, Object>> page) {
        assertThat(page.getStatusCode()).isEqualTo(HttpStatus.OK);
        return ((List<Map<String, Object>>) page.getBody().get("content")).stream()
                .map(b -> (String) b.get("title"))
                .toList();
    }

    @Test
    void listsLiveBroadcastsNewestStartedFirstAndEndedOnesMostRecentlyEndedFirst() {
        emptyBroadcastTable();
        clock.set(Instant.parse("2026-11-15T09:00:00Z"));
        long endedEarly = startedId("일찍 끝난 방송");
        clock.set(Instant.parse("2026-11-15T09:10:00Z"));
        long endedLate = startedId("늦게 끝난 방송");
        clock.set(Instant.parse("2026-11-15T09:20:00Z"));
        startedId("먼저 시작한 방송");
        clock.set(Instant.parse("2026-11-15T09:30:00Z"));
        startedId("나중에 시작한 방송");
        // started first but ended last: ENDED ordering follows endedAt, not startedAt
        endInDatabase(endedEarly, Instant.parse("2026-11-15T09:40:00Z"));
        endInDatabase(endedLate, Instant.parse("2026-11-15T09:35:00Z"));

        ResponseEntity<Map<String, Object>> live = list("?status=LIVE");
        ResponseEntity<Map<String, Object>> ended = list("?status=ENDED");

        assertThat(titles(live)).containsExactly("나중에 시작한 방송", "먼저 시작한 방송");
        assertThat(titles(ended)).containsExactly("일찍 끝난 방송", "늦게 끝난 방송");
        @SuppressWarnings("unchecked")
        Map<String, Object> card = ((List<Map<String, Object>>) ended.getBody().get("content")).get(0);
        assertThat(card)
                .containsEntry("id", (int) endedEarly)
                .containsEntry("status", "ENDED")
                .containsEntry("startedAt", "2026-11-15T09:00:00Z")
                .containsEntry("endedAt", "2026-11-15T09:40:00Z")
                .doesNotContainKey("publishKey")
                .doesNotContainKey("whepUrl");
        assertThat(ended.getBody()).containsEntry("page", 0).containsEntry("size", 20)
                .containsEntry("totalElements", 2).containsEntry("totalPages", 1);
    }

    @Test
    void endedBroadcastsArePagedAcrossThePageBoundaryAndOversizedPagesAreCapped() {
        emptyBroadcastTable();
        clock.set(Instant.parse("2026-11-15T08:00:00Z"));
        for (int i = 1; i <= 21; i++) {
            long id = startedId("종료 " + i);
            endInDatabase(id, Instant.parse("2026-11-15T08:00:00Z").plusSeconds(60L * i));
        }
        startedId("방송 중"); // never appears in the ENDED pages

        ResponseEntity<Map<String, Object>> first = list("?status=ENDED&page=0");
        ResponseEntity<Map<String, Object>> second = list("?status=ENDED&page=1");
        ResponseEntity<Map<String, Object>> sized = list("?status=ENDED&page=2&size=10");
        ResponseEntity<Map<String, Object>> huge = list("?status=ENDED&size=100000");

        assertThat(titles(first)).hasSize(20).startsWith("종료 21").endsWith("종료 2");
        assertThat(first.getBody()).containsEntry("totalElements", 21).containsEntry("totalPages", 2);
        assertThat(titles(second)).containsExactly("종료 1");
        assertThat(second.getBody()).containsEntry("page", 1);
        assertThat(titles(sized)).containsExactly("종료 1");
        assertThat(huge.getBody()).containsEntry("size", 100);
        assertThat(titles(list("?status=LIVE"))).containsExactly("방송 중");
    }

    @Test
    void listWithMissingOrInvalidStatusOrBadPagingIsProblemDetails400() {
        for (String query : List.of("", "?status=", "?status=live", "?status=PAUSED", "?status=LIVE&page=-1",
                "?status=LIVE&size=0", "?status=ENDED&size=-5", "?status=LIVE&page=abc", "?status=ENDED&size=xyz")) {
            ResponseEntity<Map<String, Object>> response = list(query);

            assertThat(response.getStatusCode()).as(query).isEqualTo(HttpStatus.BAD_REQUEST);
            assertProblem(response, HttpStatus.BAD_REQUEST);
        }
    }
}
