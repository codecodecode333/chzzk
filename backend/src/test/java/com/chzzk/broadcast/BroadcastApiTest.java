package com.chzzk.broadcast;

import static org.assertj.core.api.Assertions.assertThat;

import com.chzzk.support.ApiSession;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * HTTP API seam for 방송: full Spring context against Testcontainers PostgreSQL, with the media server gateway faked
 * and the clock injectable (see {@link BroadcastTestConfig}).
 */
// The publishing monitor's schedule is off: tests run its checks by hand, with the clock where they want it.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"broadcast.publishing-monitor.enabled=false",
                "broadcast.media-server.auth-secret=" + BroadcastApiTest.AUTH_SECRET})
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

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    PublishingMonitor publishingMonitor;

    /** The shared secret MediaMTX sends on every auth callback (in the query of its {@code authHTTPAddress}). */
    static final String AUTH_SECRET = "test-auth-secret";

    @Autowired
    com.chzzk.broadcast.domain.BroadcastLifecycle lifecycle;

    static final ParameterizedTypeReference<Map<String, Object>> JSON = new ParameterizedTypeReference<>() {};

    private ApiSession owner;

    /** The 회원 the test acts as by default: a fresh one per test, signed up and logged in through the auth API. */
    ApiSession owner() {
        if (owner == null) owner = ApiSession.member(http);
        return owner;
    }

    ResponseEntity<Map<String, Object>> start(String title) {
        return startAs(owner(), title);
    }

    ResponseEntity<Map<String, Object>> startAs(ApiSession who, String title) {
        return who.exchange("/api/broadcasts", HttpMethod.POST, Map.of("title", title), JSON);
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

    /** 송출 키 and 소유자 of every 방송 started through {@link #startedId}. */
    final Map<Long, String> publishKeys = new java.util.concurrent.ConcurrentHashMap<>();
    final Map<Long, ApiSession> owners = new java.util.concurrent.ConcurrentHashMap<>();

    /** Starts a 방송 as a fresh 회원 of its own (a 회원 has at most one 방송 중 방송). */
    long startedId(String title) {
        ApiSession who = ApiSession.member(http);
        ResponseEntity<Map<String, Object>> response = startAs(who, title);
        assertThat(response.getStatusCode()).as("start: %s", response.getBody()).isEqualTo(HttpStatus.CREATED);
        long id = ((Number) response.getBody().get("id")).longValue();
        publishKeys.put(id, (String) response.getBody().get("publishKey"));
        owners.put(id, who);
        return id;
    }

    /** Ends a 방송 started by {@link #startedId} through the end API as its 소유자, at the given moment. */
    void endAt(long id, Instant endedAt) {
        clock.set(endedAt);
        assertThat(endAs(owners.get(id), id).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    }

    /** What MediaMTX sends to the internal auth callback for one WHIP (publish) or WHEP (read) request. */
    HttpStatus mediaServerAsks(String action, String path, String token) {
        return mediaServerAsks(action, path, token, "?secret=" + AUTH_SECRET);
    }

    /** As {@link #mediaServerAsks(String, String, String)}, calling the callback with the given query string. */
    HttpStatus mediaServerAsks(String action, String path, String token, String query) {
        Map<String, Object> request = Map.of("ip", "172.17.0.1", "user", "", "password", "", "token", token,
                "action", action, "path", path, "protocol", "webrtc", "id", "56b4d011-daa4-4214-b30e-1e9fe721cf63",
                "query", "", "userAgent", "Mozilla/5.0");
        ResponseEntity<String> response = http.postForEntity("/internal/mediamtx/auth" + query, request, String.class);
        assertThat(response.getBody()).as("MediaMTX accepts only an empty or JSON body").isNull();
        return HttpStatus.valueOf(response.getStatusCode().value());
    }

    /** "방송 종료" as {@code who}, by its login session. */
    ResponseEntity<Map<String, Object>> endAs(ApiSession who, Object id) {
        return who.exchange("/api/broadcasts/" + id + "/end", HttpMethod.POST, null, JSON);
    }

    /** "방송 종료" by the default {@link #owner()}. */
    ResponseEntity<Map<String, Object>> end(Object id) {
        return endAs(owner(), id);
    }

    /** "이어서 송출": a fresh 송출 키 for {@code who}. */
    ResponseEntity<Map<String, Object>> issuePublishKeyAs(ApiSession who, Object id) {
        return who.exchange("/api/broadcasts/" + id + "/publish-key", HttpMethod.POST, null, JSON);
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
        end(id);

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
        String otherKey = publishKeys.get(startedId("다른 방송"));

        assertThat(mediaServerAsks("publish", "broadcasts/" + id, key)).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(mediaServerAsks("publish", "broadcasts/" + id, otherKey)).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(mediaServerAsks("publish", "broadcasts/" + id, "")).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(mediaServerAsks("publish", "broadcasts/987654321", key)).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(mediaServerAsks("publish", "elsewhere/" + id, key)).isEqualTo(HttpStatus.UNAUTHORIZED);

        end(id);
        assertThat(mediaServerAsks("publish", "broadcasts/" + id, key)).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void mediaServerMayLetAnyoneWatchOnlyALiveBroadcast() {
        Map<String, Object> started = start("시청 권한").getBody();
        long id = ((Number) started.get("id")).longValue();

        assertThat(mediaServerAsks("read", "broadcasts/" + id, "")).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(mediaServerAsks("read", "broadcasts/987654321", "")).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(mediaServerAsks("playback", "broadcasts/" + id, "")).isEqualTo(HttpStatus.UNAUTHORIZED);

        end(id);
        assertThat(mediaServerAsks("read", "broadcasts/" + id, "")).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void mediaServerCallbackWithoutTheSharedSecretIsRefused() {
        long id = startedId("비밀 없는 콜백");
        String key = publishKeys.get(id);
        String path = "broadcasts/" + id;

        for (String query : List.of("", "?secret=", "?secret=wrong", "?secret=" + AUTH_SECRET + "x",
                "?other=" + AUTH_SECRET)) {
            assertThat(mediaServerAsks("read", path, "", query)).as(query).isEqualTo(HttpStatus.UNAUTHORIZED);
            assertThat(mediaServerAsks("publish", path, key, query)).as(query).isEqualTo(HttpStatus.UNAUTHORIZED);
        }
        assertThat(mediaServerAsks("read", path, "")).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(mediaServerAsks("publish", path, key)).isEqualTo(HttpStatus.NO_CONTENT);
    }

    @Test
    void endingByTheOwnerEndsTheBroadcastAndCutsThePublisher() {
        clock.set(Instant.parse("2026-11-15T13:00:00Z"));
        Map<String, Object> started = start("끝낼 방송").getBody();
        long id = ((Number) started.get("id")).longValue();
        mediaServer.startPublishing(id);
        clock.set(Instant.parse("2026-11-15T13:42:00Z"));

        ResponseEntity<Map<String, Object>> response = end(id);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(detail(id).getBody())
                .containsEntry("status", "ENDED")
                .containsEntry("endedAt", "2026-11-15T13:42:00Z")
                .containsEntry("whepUrl", null);
        assertThat(mediaServer.kicked()).contains(id);
    }

    @Test
    void startingABroadcastRequiresLogin() {
        ApiSession visitor = ApiSession.visitor(http);

        ResponseEntity<Map<String, Object>> response = startAs(visitor, "익명 방송");

        assertProblem(response, HttpStatus.UNAUTHORIZED);
        assertThat(titles(list("?status=LIVE&size=100"))).doesNotContain("익명 방송");
    }

    @Test
    void theStarterIsTheOwnerShownInListAndDetail() {
        ApiSession.Member me = owner().member();
        long id = ((Number) start("내 방송").getBody().get("id")).longValue();

        Map<String, Object> expectedOwner = Map.of("id", (int) me.id(), "nickname", me.nickname());
        assertThat(detail(id).getBody()).containsEntry("owner", expectedOwner);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> cards = (List<Map<String, Object>>) list("?status=LIVE&size=100").getBody()
                .get("content");
        assertThat(cards).filteredOn(c -> ((Number) c.get("id")).longValue() == id)
                .singleElement().satisfies(c -> assertThat(c).containsEntry("owner", expectedOwner));
    }

    @Test
    void aSecondLiveBroadcastIsRefusedWithTheIdOfTheLiveOne() {
        long live = ((Number) start("진행 중").getBody().get("id")).longValue();

        ResponseEntity<Map<String, Object>> second = start("두 번째");

        assertProblem(second, HttpStatus.CONFLICT);
        assertThat(second.getBody()).containsEntry("broadcastId", (int) live);
        assertThat(titles(list("?status=LIVE&size=100"))).doesNotContain("두 번째");

        end(live);
        assertThat(start("끝내고 새로").getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    @Test
    void theLiveBroadcastsOfOneOwnerCanBeListed() {
        long mine = ((Number) start("내 방송 중").getBody().get("id")).longValue();
        startedId("남의 방송 중");

        ResponseEntity<Map<String, Object>> page = list("?status=LIVE&ownerId=" + owner().member().id());

        assertThat(titles(page)).containsExactly("내 방송 중");
        end(mine);
        assertThat(titles(list("?status=LIVE&ownerId=" + owner().member().id()))).isEmpty();
    }

    @Test
    void onlyTheOwnerCanEndABroadcastBySession() {
        Map<String, Object> started = start("남이 못 끄는 방송").getBody();
        long id = ((Number) started.get("id")).longValue();
        String publishKey = (String) started.get("publishKey"); // the 송출 키 no longer ends a 방송
        ApiSession visitor = ApiSession.visitor(http);
        HttpHeaders bearer = new HttpHeaders();
        bearer.setBearerAuth(publishKey);

        assertProblem(endAs(visitor, id), HttpStatus.UNAUTHORIZED);
        assertProblem(visitor.exchange("/api/broadcasts/" + id + "/end", HttpMethod.POST, new HttpEntity<>(bearer),
                JSON), HttpStatus.UNAUTHORIZED);
        assertProblem(endAs(ApiSession.member(http), id), HttpStatus.FORBIDDEN);
        assertThat(detail(id).getBody()).containsEntry("status", "LIVE");
        assertThat(mediaServer.kicked()).doesNotContain(id);

        assertThat(end(id).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(detail(id).getBody()).containsEntry("status", "ENDED");
        assertProblem(endAs(ApiSession.member(http), id), HttpStatus.FORBIDDEN);
    }

    @Test
    void endingAnUnknownBroadcastIsProblemDetails404() {
        assertProblem(end(987654321), HttpStatus.NOT_FOUND);
    }

    @Test
    void resumingIssuesAFreshPublishKeyAndTheOldOneStopsWorking() {
        Map<String, Object> started = start("이어서 송출").getBody();
        long id = ((Number) started.get("id")).longValue();
        String oldKey = (String) started.get("publishKey");
        mediaServer.startPublishing(id);
        String path = "broadcasts/" + id;
        assertThat(mediaServerAsks("publish", path, oldKey)).isEqualTo(HttpStatus.NO_CONTENT);

        ResponseEntity<Map<String, Object>> resumed = issuePublishKeyAs(owner(), id);

        assertThat(resumed.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resumed.getBody()).containsEntry("whipUrl", "/live/broadcasts/" + id + "/whip");
        String newKey = (String) resumed.getBody().get("publishKey");
        assertThat(Base64.getUrlDecoder().decode(newKey)).hasSize(32);
        assertThat(newKey).isNotEqualTo(oldKey);
        assertThat(mediaServerAsks("publish", path, oldKey)).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(mediaServerAsks("publish", path, newKey)).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(mediaServer.kicked()).as("the old key's publisher is cut").contains(id);
        assertThat(detail(id).getBody()).containsEntry("status", "LIVE").doesNotContainKey("publishKey");
    }

    @Test
    void onlyTheOwnerOfALiveBroadcastCanGetAPublishKey() {
        Map<String, Object> started = start("내 키").getBody();
        long id = ((Number) started.get("id")).longValue();
        String key = (String) started.get("publishKey");

        assertProblem(issuePublishKeyAs(ApiSession.visitor(http), id), HttpStatus.UNAUTHORIZED);
        assertProblem(issuePublishKeyAs(ApiSession.member(http), id), HttpStatus.FORBIDDEN);
        assertProblem(issuePublishKeyAs(owner(), 987654321), HttpStatus.NOT_FOUND);
        assertThat(mediaServerAsks("publish", "broadcasts/" + id, key)).as("refusals change nothing")
                .isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(mediaServer.kicked()).doesNotContain(id);

        end(id);
        assertProblem(issuePublishKeyAs(owner(), id), HttpStatus.CONFLICT);
        assertThat(mediaServerAsks("publish", "broadcasts/" + id, key)).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void endingTwiceIsHarmless() {
        clock.set(Instant.parse("2026-11-15T14:00:00Z"));
        Map<String, Object> started = start("두 번 끄기").getBody();
        Object id = started.get("id");
        String key = (String) started.get("publishKey");
        end(id);
        clock.set(Instant.parse("2026-11-15T14:05:00Z"));

        ResponseEntity<Map<String, Object>> again = end(id);

        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(detail(id).getBody())
                .containsEntry("status", "ENDED")
                .containsEntry("endedAt", "2026-11-15T14:00:00Z");
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
        endAt(endedEarly, Instant.parse("2026-11-15T09:40:00Z"));
        endAt(endedLate, Instant.parse("2026-11-15T09:35:00Z"));

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
            endAt(id, Instant.parse("2026-11-15T08:00:00Z").plusSeconds(60L * i));
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

    /** One run of the publishing monitor (normally every 10s) at the given moment. */
    void monitorChecksAt(Instant now) {
        clock.set(now);
        publishingMonitor.check();
    }

    @Test
    void aPublishingBroadcastStaysLiveAsTimeAdvances() {
        Instant start = Instant.parse("2026-11-15T15:00:00Z");
        clock.set(start);
        long id = startedId("오래 하는 방송");
        mediaServer.startPublishing(id);

        for (int second = 10; second <= 600; second += 10) {
            monitorChecksAt(start.plusSeconds(second));
        }

        assertThat(detail(id).getBody()).containsEntry("status", "LIVE").containsEntry("endedAt", null);
    }

    @Test
    void aBroadcastWhoseMediaStoppedEndsAfterMoreThan60SecondsAtItsLastPublishingTime() {
        Instant start = Instant.parse("2026-11-15T16:00:00Z");
        clock.set(start);
        long id = startedId("끊긴 방송");
        mediaServer.startPublishing(id);
        monitorChecksAt(start.plusSeconds(10));
        Instant lastSeen = start.plusSeconds(20);
        monitorChecksAt(lastSeen);

        mediaServer.stopPublishing(id);
        for (int second = 30; second <= 80; second += 10) {
            monitorChecksAt(start.plusSeconds(second));
        }
        assertThat(detail(id).getBody()).as("exactly 60s without media").containsEntry("status", "LIVE");

        monitorChecksAt(start.plusSeconds(90));

        assertThat(detail(id).getBody())
                .containsEntry("status", "ENDED")
                .containsEntry("endedAt", "2026-11-15T16:00:20Z")
                .containsEntry("whepUrl", null);
    }

    @Test
    void aBroadcastThatNeverPublishedEnds60SecondsAfterItStarted() {
        Instant start = Instant.parse("2026-11-15T17:00:00Z");
        clock.set(start);
        long id = startedId("시작만 한 방송");

        monitorChecksAt(start.plusSeconds(60));
        assertThat(detail(id).getBody()).containsEntry("status", "LIVE");
        monitorChecksAt(start.plusSeconds(61));

        assertThat(detail(id).getBody())
                .containsEntry("status", "ENDED")
                .containsEntry("endedAt", "2026-11-15T17:00:00Z");
    }

    @Test
    void nothingEndsWhileTheMediaServerCannotBeAsked() {
        Instant start = Instant.parse("2026-11-15T18:00:00Z");
        clock.set(start);
        long id = startedId("미디어 서버 점검 중");
        mediaServer.startPublishing(id);
        monitorChecksAt(start.plusSeconds(10));
        mediaServer.becomeUnreachable();
        try {
            monitorChecksAt(start.plusSeconds(300));
        } finally {
            mediaServer.reset();
        }

        assertThat(detail(id).getBody()).containsEntry("status", "LIVE");
    }

    @Test
    void aBroadcastThatNeverPublishedStillEndsWhileTheMediaServerCannotBeAsked() {
        Instant start = Instant.parse("2026-11-15T19:00:00Z");
        clock.set(start);
        long silent = startedId("시작만 하고 점검 중");
        long publishing = startedId("송출하다 점검 중");
        mediaServer.startPublishing(publishing);
        monitorChecksAt(start.plusSeconds(10));
        mediaServer.becomeUnreachable();
        try {
            monitorChecksAt(start.plusSeconds(60));
            assertThat(detail(silent).getBody()).as("only 60s").containsEntry("status", "LIVE");
            monitorChecksAt(start.plusSeconds(300));
        } finally {
            mediaServer.reset();
        }

        assertThat(detail(silent).getBody())
                .containsEntry("status", "ENDED")
                .containsEntry("endedAt", "2026-11-15T19:00:00Z");
        assertThat(detail(publishing).getBody()).as("silence can't be told from an outage")
                .containsEntry("status", "LIVE");
    }

    @Test
    void theAutoEndWorksFromTheDatabaseAloneAfterARestart() {
        Instant start = Instant.parse("2026-11-15T20:00:00Z");
        clock.set(start);
        long id = startedId("재시작 전에 송출한 방송");
        mediaServer.startPublishing(id);
        monitorChecksAt(start.plusSeconds(10));

        // A restarted backend: a new publishing monitor over a media server that has never seen this 방송.
        FakeMediaServerGateway restartedMediaServer = new FakeMediaServerGateway();
        PublishingMonitor restartedMonitor = new PublishingMonitor(restartedMediaServer, lifecycle);
        clock.set(start.plusSeconds(70));
        restartedMonitor.check();
        assertThat(detail(id).getBody()).as("exactly 60s since its last media").containsEntry("status", "LIVE");
        clock.set(start.plusSeconds(80));
        restartedMonitor.check();

        assertThat(detail(id).getBody())
                .containsEntry("status", "ENDED")
                .containsEntry("endedAt", "2026-11-15T20:00:10Z");
    }

    @Test
    void endingAndTheAutoEndRacingEndEachBroadcastExactlyOnce() throws Exception {
        Instant start = Instant.parse("2026-11-15T21:00:00Z");
        clock.set(start);
        List<Long> ids = new java.util.ArrayList<>();
        for (int i = 0; i < 40; i++) ids.add(startedId("경쟁 " + i));
        mediaServer.reset();
        clock.set(start.plusSeconds(61)); // every one is due for the auto-end, and the 송출자 ends it now

        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(9);
        java.util.concurrent.CountDownLatch go = new java.util.concurrent.CountDownLatch(1);
        try {
            List<java.util.concurrent.Future<?>> work = new java.util.ArrayList<>();
            for (long id : ids) {
                work.add(pool.submit(() -> {
                    go.await();
                    return endAs(owners.get(id), id).getStatusCode();
                }));
            }
            for (int i = 0; i < 4; i++) {
                work.add(pool.submit(() -> {
                    go.await();
                    publishingMonitor.check();
                    return null;
                }));
            }
            go.countDown();
            for (java.util.concurrent.Future<?> f : work) f.get(60, java.util.concurrent.TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        for (long id : ids) {
            assertThat(mediaServer.kicked()).as("kicks of %d", id).containsOnlyOnce(id);
            Object endedAt = detail(id).getBody().get("endedAt");
            assertThat(endedAt).as("ended_at of %d is the auto-end's or the 송출자's, whole", id)
                    .isIn("2026-11-15T21:00:00Z", "2026-11-15T21:01:01Z");
            Number count = jdbc.queryForObject("select count(*) from broadcast where id = ? and status = 'ENDED'",
                    Number.class, id);
            assertThat(count.intValue()).isEqualTo(1);
        }
    }

    // --- 제목 수정과 삭제: the 소유자's alone ---

    @Test
    void ownerRenamesALiveOrEndedBroadcast() {
        long live = startedId("원래 제목");
        long ended = startedId("끝난 방송");
        endAt(ended, Instant.parse("2026-11-15T12:00:00Z"));

        for (long id : List.of(live, ended)) {
            ResponseEntity<Map<String, Object>> renamed = owners.get(id).patch("/api/broadcasts/" + id,
                    Map.of("title", "  바뀐 제목  "));

            assertThat(renamed.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(renamed.getBody()).containsEntry("id", (int) id).containsEntry("title", "바뀐 제목");
            assertThat(detail(id).getBody()).containsEntry("title", "바뀐 제목");
        }
        assertThat(detail(live).getBody()).containsEntry("status", "LIVE");
        assertThat(detail(ended).getBody()).containsEntry("status", "ENDED")
                .containsEntry("endedAt", "2026-11-15T12:00:00Z");
    }

    @Test
    void renameFollowsTheStartTitleRule() {
        long id = startedId("제목");

        for (String title : List.of("", "  	 ", "가".repeat(101))) {
            ResponseEntity<Map<String, Object>> response = owners.get(id).patch("/api/broadcasts/" + id,
                    Map.of("title", title));

            assertProblem(response, HttpStatus.BAD_REQUEST);
            assertThat(response.getBody().get("errors").toString()).as(title).contains("field=title");
        }
        assertThat(detail(id).getBody()).containsEntry("title", "제목");
    }

    @Test
    void onlyTheOwnerMayRename() {
        long id = startedId("제목");

        assertProblem(ApiSession.visitor(http).patch("/api/broadcasts/" + id, Map.of("title", "익명")),
                HttpStatus.UNAUTHORIZED);
        assertProblem(ApiSession.member(http).patch("/api/broadcasts/" + id, Map.of("title", "남의 것")),
                HttpStatus.FORBIDDEN);
        assertProblem(owners.get(id).patch("/api/broadcasts/999999", Map.of("title", "없음")), HttpStatus.NOT_FOUND);
        assertThat(detail(id).getBody()).containsEntry("title", "제목");
    }

    @Test
    void ownerDeletingAnEndedBroadcastRemovesItAndItsComments() {
        long id = startedId("지울 방송");
        endAt(id, Instant.parse("2026-11-15T13:00:00Z"));
        ApiSession owner = owners.get(id);
        jdbc.update("insert into comment (broadcast_id, author_id, content, created_at) values (?, ?, ?, now())",
                id, owner.member().id(), "곧 사라질 댓글");

        ResponseEntity<Map<String, Object>> deleted = owner.delete("/api/broadcasts/" + id);

        assertThat(deleted.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(detail(id).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(http.exchange("/api/broadcasts?status=ENDED&size=100", HttpMethod.GET, null, JSON).getBody()
                .get("content")).asList().noneMatch(b -> ((Map<?, ?>) b).get("id").equals((int) id));
        Number comments = jdbc.queryForObject("select count(*) from comment where broadcast_id = ?", Number.class, id);
        assertThat(comments.intValue()).isZero();
    }

    @Test
    void deletingALiveBroadcastIsRefusedUntilItEnds() {
        long id = startedId("방송 중");

        ResponseEntity<Map<String, Object>> refused = owners.get(id).delete("/api/broadcasts/" + id);

        assertProblem(refused, HttpStatus.CONFLICT);
        assertThat((String) refused.getBody().get("detail")).contains("방송을 먼저 종료");
        assertThat(detail(id).getBody()).containsEntry("status", "LIVE");
    }

    @Test
    void onlyTheOwnerMayDelete() {
        long id = startedId("남의 방송");
        endAt(id, Instant.parse("2026-11-15T14:00:00Z"));

        assertProblem(ApiSession.visitor(http).delete("/api/broadcasts/" + id), HttpStatus.UNAUTHORIZED);
        assertProblem(ApiSession.member(http).delete("/api/broadcasts/" + id), HttpStatus.FORBIDDEN);
        assertProblem(owners.get(id).delete("/api/broadcasts/999999"), HttpStatus.NOT_FOUND);
        assertThat(detail(id).getStatusCode()).isEqualTo(HttpStatus.OK);
    }
}
