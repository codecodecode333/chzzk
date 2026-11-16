package com.chzzk.broadcast;

import static org.assertj.core.api.Assertions.assertThat;

import com.chzzk.support.ApiSession;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** HTTP API seam for 댓글 on a 방송 (live or ended): full Spring context against Testcontainers PostgreSQL. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "broadcast.publishing-monitor.enabled=false")
@Testcontainers
@Import(BroadcastTestConfig.class)
class BroadcastCommentApiTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    TestRestTemplate http;

    @Autowired
    JdbcTemplate jdbc;

    /** Starts a 방송 as a fresh 회원 (one 방송 중 방송 per 회원) and returns its 소유자's session and id. */
    record Started(ApiSession owner, long id) {}

    Started live() {
        ApiSession owner = ApiSession.member(http);
        ResponseEntity<Map<String, Object>> started = owner.exchange("/api/broadcasts", HttpMethod.POST,
                Map.of("title", "댓글 달 방송"), ApiSession.JSON);
        assertThat(started.getStatusCode()).as("start: %s", started.getBody()).isEqualTo(HttpStatus.CREATED);
        return new Started(owner, ((Number) started.getBody().get("id")).longValue());
    }

    Started ended() {
        Started b = live();
        assertThat(b.owner().exchange("/api/broadcasts/" + b.id() + "/end", HttpMethod.POST, null, ApiSession.JSON)
                .getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        return b;
    }

    static String commentsOf(long broadcastId) {
        return "/api/broadcasts/" + broadcastId + "/comments";
    }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> content(ResponseEntity<Map<String, Object>> page) {
        return (List<Map<String, Object>>) page.getBody().get("content");
    }

    static List<Object> contents(ResponseEntity<Map<String, Object>> page) {
        return content(page).stream().map(c -> c.get("content")).toList();
    }

    @Test
    void memberWritesOnALiveBroadcastAndVisitorReadsIt() {
        long id = live().id();
        ApiSession author = ApiSession.member(http);

        ResponseEntity<Map<String, Object>> written = author.post(commentsOf(id), Map.of("content", "  반가워요  "));

        assertThat(written.getStatusCode()).as("%s", written.getBody()).isEqualTo(HttpStatus.CREATED);
        assertThat(written.getBody())
                .containsOnlyKeys("id", "content", "author", "createdAt", "edited")
                .containsEntry("content", "반가워요")
                .containsEntry("edited", false)
                .containsEntry("author", Map.of("id", (int) author.member().id(), "nickname", author.member().nickname()));

        ResponseEntity<Map<String, Object>> listed = ApiSession.visitor(http).get(commentsOf(id));

        assertThat(listed.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(content(listed)).containsExactly(written.getBody());
        assertThat(listed.getBody()).containsEntry("nextBefore", null);
    }

    @Test
    void memberWritesOnAnEndedBroadcast() {
        long id = ended().id();

        ResponseEntity<Map<String, Object>> written = ApiSession.member(http).post(commentsOf(id),
                Map.of("content", "다시보기 좋았어요"));

        assertThat(written.getStatusCode()).as("%s", written.getBody()).isEqualTo(HttpStatus.CREATED);
        assertThat(contents(ApiSession.visitor(http).get(commentsOf(id)))).containsExactly("다시보기 좋았어요");
    }

    @Test
    void listIsNewestFirstAndPagesWithACursor() {
        long id = live().id();
        ApiSession member = ApiSession.member(http);
        for (String text : List.of("첫째", "둘째", "셋째")) member.post(commentsOf(id), Map.of("content", text));
        ApiSession visitor = ApiSession.visitor(http);

        ResponseEntity<Map<String, Object>> first = visitor.get(commentsOf(id) + "?size=2");
        assertThat(contents(first)).containsExactly("셋째", "둘째");
        Object nextBefore = first.getBody().get("nextBefore");

        ResponseEntity<Map<String, Object>> second = visitor.get(commentsOf(id) + "?size=2&before=" + nextBefore);
        assertThat(contents(second)).containsExactly("첫째");
        assertThat(second.getBody()).containsEntry("nextBefore", null);
    }

    @Test
    void commentsOnAnotherBroadcastAreNotListed() {
        long mine = live().id();
        long other = live().id();
        ApiSession.member(http).post(commentsOf(other), Map.of("content", "다른 방송"));

        assertThat(content(ApiSession.visitor(http).get(commentsOf(mine)))).isEmpty();
    }

    @Test
    void unknownBroadcastIs404ForListAndWrite() {
        assertThat(ApiSession.visitor(http).get(commentsOf(999_999)).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(ApiSession.member(http).post(commentsOf(999_999), Map.of("content", "안녕")).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void visitorCannotWriteOnLiveOrEnded() {
        for (long id : List.of(live().id(), ended().id())) {
            ResponseEntity<Map<String, Object>> written = ApiSession.visitor(http).post(commentsOf(id),
                    Map.of("content", "몰래"));

            assertThat(written.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
            assertThat(written.getBody()).containsEntry("detail", "로그인이 필요합니다.");
            assertThat(content(ApiSession.visitor(http).get(commentsOf(id)))).isEmpty();
        }
    }

    @Test
    void contentRuleIsTheSameAsOnVods() {
        long id = live().id();
        ApiSession member = ApiSession.member(http);

        ResponseEntity<Map<String, Object>> blank = member.post(commentsOf(id), Map.of("content", "   "));
        assertThat(blank.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(blank.getBody()).containsEntry("errors",
                List.of(Map.of("field", "content", "message", "댓글 내용을 입력해 주세요.")));
        assertThat(member.post(commentsOf(id), Map.of("content", "가".repeat(501))).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void onlyTheAuthorEditsABroadcastComment() {
        long id = live().id();
        ApiSession author = ApiSession.member(http);
        Object commentId = author.post(commentsOf(id), Map.of("content", "오타 있음")).getBody().get("id");
        String path = "/api/comments/" + commentId;

        assertThat(ApiSession.visitor(http).patch(path, Map.of("content", "x")).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        ResponseEntity<Map<String, Object>> byOther = ApiSession.member(http).patch(path, Map.of("content", "x"));
        assertThat(byOther.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(byOther.getBody()).containsEntry("detail", "내 댓글만 수정할 수 있습니다.");

        ResponseEntity<Map<String, Object>> edited = author.patch(path, Map.of("content", "오타 고침"));

        assertThat(edited.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(edited.getBody()).containsEntry("content", "오타 고침").containsEntry("edited", true);
        assertThat(content(ApiSession.visitor(http).get(commentsOf(id)))).singleElement().isEqualTo(edited.getBody());
    }

    @Test
    void onlyTheAuthorDeletesABroadcastComment() {
        long id = ended().id();
        ApiSession author = ApiSession.member(http);
        Object commentId = author.post(commentsOf(id), Map.of("content", "지울 댓글")).getBody().get("id");
        String path = "/api/comments/" + commentId;

        assertThat(ApiSession.visitor(http).delete(path).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        ResponseEntity<Map<String, Object>> byOther = ApiSession.member(http).delete(path);
        assertThat(byOther.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(byOther.getBody()).containsEntry("detail", "내 댓글만 삭제할 수 있습니다.");

        assertThat(author.delete(path).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        assertThat(content(ApiSession.visitor(http).get(commentsOf(id)))).isEmpty();
    }

    /** Ticket 04's delete API isn't here yet, so this covers the cascade at the DB level. */
    @Test
    void deletingABroadcastRemovesItsComments() {
        long id = ended().id();
        ApiSession author = ApiSession.member(http);
        Object commentId = author.post(commentsOf(id), Map.of("content", "사라질 댓글")).getBody().get("id");

        jdbc.update("DELETE FROM broadcast WHERE id = ?", id);

        assertThat(ApiSession.visitor(http).get(commentsOf(id)).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        // the 댓글 itself is gone, not merely unlisted
        assertThat(author.patch("/api/comments/" + commentId, Map.of("content", "x")).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }
}
