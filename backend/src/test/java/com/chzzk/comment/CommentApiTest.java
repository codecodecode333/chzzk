package com.chzzk.comment;

import static org.assertj.core.api.Assertions.assertThat;

import com.chzzk.support.ApiSession;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** HTTP API seam for 댓글 on a VOD: full Spring context against Testcontainers PostgreSQL. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class CommentApiTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @TempDir
    static Path storageRoot;

    @DynamicPropertySource
    static void storage(DynamicPropertyRegistry registry) {
        registry.add("vod.storage.local.root", () -> storageRoot.toString());
    }

    @Autowired
    TestRestTemplate http;

    /** Registers a VOD as {@code as} and returns its id. */
    long vodBy(ApiSession as) {
        MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
        form.add("title", "댓글 달 VOD");
        byte[] header = {0, 0, 0, 0x18, 'f', 't', 'y', 'p', 'i', 's', 'o', 'm'};
        byte[] bytes = new byte[header.length + 4];
        System.arraycopy(header, 0, bytes, 0, header.length);
        System.arraycopy("body".getBytes(StandardCharsets.US_ASCII), 0, bytes, header.length, 4);
        form.add("file", new ByteArrayResource(bytes) {
            @Override
            public String getFilename() {
                return "clip.mp4";
            }
        });
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        ResponseEntity<Map<String, Object>> created = as.exchange("/api/vods", HttpMethod.POST,
                new HttpEntity<>(form, headers), ApiSession.JSON);
        assertThat(created.getStatusCode()).as("register: %s", created.getBody()).isEqualTo(HttpStatus.CREATED);
        return ((Number) created.getBody().get("id")).longValue();
    }

    long vod() {
        return vodBy(ApiSession.member(http));
    }

    static String commentsOf(long vodId) {
        return "/api/vods/" + vodId + "/comments";
    }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> content(ResponseEntity<Map<String, Object>> page) {
        return (List<Map<String, Object>>) page.getBody().get("content");
    }

    static List<Object> contents(ResponseEntity<Map<String, Object>> page) {
        return content(page).stream().map(c -> c.get("content")).toList();
    }

    @Test
    void memberWritesCommentAndVisitorReadsIt() {
        long vodId = vod();
        ApiSession author = ApiSession.member(http);

        ResponseEntity<Map<String, Object>> written = author.post(commentsOf(vodId), Map.of("content", "  좋은 영상  "));

        assertThat(written.getStatusCode()).as("%s", written.getBody()).isEqualTo(HttpStatus.CREATED);
        assertThat(written.getBody()).containsOnlyKeys("id", "content", "author", "createdAt", "edited");
        assertThat(written.getBody())
                .containsEntry("content", "좋은 영상")
                .containsEntry("edited", false)
                .containsEntry("author", Map.of("id", (int) author.member().id(), "nickname", author.member().nickname()));
        assertThat(written.getBody().get("createdAt")).isNotNull();

        ResponseEntity<Map<String, Object>> listed = ApiSession.visitor(http).get(commentsOf(vodId));

        assertThat(listed.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(content(listed)).containsExactly(written.getBody());
        assertThat(listed.getBody()).containsEntry("nextBefore", null);
    }

    @Test
    void listIsNewestFirstAndPagesOlderCommentsWithACursor() {
        long vodId = vod();
        ApiSession member = ApiSession.member(http);
        for (String text : List.of("첫째", "둘째", "셋째")) {
            assertThat(member.post(commentsOf(vodId), Map.of("content", text)).getStatusCode())
                    .isEqualTo(HttpStatus.CREATED);
        }
        ApiSession visitor = ApiSession.visitor(http);

        ResponseEntity<Map<String, Object>> first = visitor.get(commentsOf(vodId) + "?size=2");

        assertThat(contents(first)).containsExactly("셋째", "둘째");
        Object nextBefore = first.getBody().get("nextBefore");
        assertThat(nextBefore).isEqualTo(content(first).get(1).get("id"));

        ResponseEntity<Map<String, Object>> second = visitor.get(commentsOf(vodId) + "?size=2&before=" + nextBefore);

        assertThat(contents(second)).containsExactly("첫째");
        assertThat(second.getBody()).containsEntry("nextBefore", null);
    }

    @Test
    void pageSizeDefaultsTo20AndIsCapped() {
        long vodId = vod();
        ApiSession member = ApiSession.member(http);
        for (int i = 1; i <= 21; i++) member.post(commentsOf(vodId), Map.of("content", "댓글 " + i));

        ResponseEntity<Map<String, Object>> byDefault = http.exchange(commentsOf(vodId), HttpMethod.GET, null,
                ApiSession.JSON);
        assertThat(content(byDefault)).hasSize(20);
        assertThat(contents(byDefault).getFirst()).isEqualTo("댓글 21");
        assertThat(byDefault.getBody().get("nextBefore")).isNotNull();

        ResponseEntity<Map<String, Object>> huge = http.exchange(commentsOf(vodId) + "?size=100000", HttpMethod.GET,
                null, ApiSession.JSON);
        assertThat(huge.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(content(huge)).hasSize(21);
    }

    @Test
    void commentsOnAnotherVodAreNotListed() {
        long mine = vod();
        long other = vod();
        ApiSession.member(http).post(commentsOf(other), Map.of("content", "다른 VOD"));

        assertThat(content(ApiSession.visitor(http).get(commentsOf(mine)))).isEmpty();
    }

    @Test
    void unknownVodIs404ForListAndWrite() {
        assertThat(ApiSession.visitor(http).get(commentsOf(999_999)).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(ApiSession.member(http).post(commentsOf(999_999), Map.of("content", "안녕")).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void visitorCannotWrite() {
        long vodId = vod();

        ResponseEntity<Map<String, Object>> written = ApiSession.visitor(http).post(commentsOf(vodId),
                Map.of("content", "몰래"));

        assertThat(written.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(written.getBody()).containsEntry("detail", "로그인이 필요합니다.");
        assertThat(content(ApiSession.visitor(http).get(commentsOf(vodId)))).isEmpty();
    }

    @Test
    void contentMustBe1To500CharsAfterTrimming() {
        long vodId = vod();
        ApiSession member = ApiSession.member(http);

        ResponseEntity<Map<String, Object>> blank = member.post(commentsOf(vodId), Map.of("content", "   "));
        assertThat(blank.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(blank.getBody()).containsEntry("errors",
                List.of(Map.of("field", "content", "message", "댓글 내용을 입력해 주세요.")));

        ResponseEntity<Map<String, Object>> tooLong = member.post(commentsOf(vodId),
                Map.of("content", "가".repeat(501)));
        assertThat(tooLong.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(tooLong.getBody()).containsEntry("detail", "댓글은 500자 이하로 입력해 주세요.");

        // 500 emoji are 500 characters (code points), and surrounding spaces don't count
        ResponseEntity<Map<String, Object>> longest = member.post(commentsOf(vodId),
                Map.of("content", " " + "😀".repeat(500) + " "));
        assertThat(longest.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    @Test
    void onlyTheAuthorEditsAndTheCommentIsMarkedEdited() {
        long vodId = vod();
        ApiSession author = ApiSession.member(http);
        Object id = author.post(commentsOf(vodId), Map.of("content", "오타 있음")).getBody().get("id");
        String path = "/api/comments/" + id;

        assertThat(ApiSession.visitor(http).patch(path, Map.of("content", "x")).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        ResponseEntity<Map<String, Object>> byOther = ApiSession.member(http).patch(path, Map.of("content", "x"));
        assertThat(byOther.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(byOther.getBody()).containsEntry("detail", "내 댓글만 수정할 수 있습니다.");
        assertThat(author.patch("/api/comments/999999", Map.of("content", "x")).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(author.patch(path, Map.of("content", " ")).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        ResponseEntity<Map<String, Object>> edited = author.patch(path, Map.of("content", " 오타 고침 "));

        assertThat(edited.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(edited.getBody()).containsEntry("id", id).containsEntry("content", "오타 고침")
                .containsEntry("edited", true);
        assertThat(content(ApiSession.visitor(http).get(commentsOf(vodId))))
                .singleElement().isEqualTo(edited.getBody());
    }

    @Test
    void deletingTheVodRemovesItsComments() {
        ApiSession owner = ApiSession.member(http);
        long vodId = vodBy(owner);
        ApiSession author = ApiSession.member(http);
        Object id = author.post(commentsOf(vodId), Map.of("content", "사라질 댓글")).getBody().get("id");

        assertThat(owner.delete("/api/vods/" + vodId).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        assertThat(ApiSession.visitor(http).get(commentsOf(vodId)).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        // the 댓글 itself is gone, not merely unlisted
        assertThat(author.patch("/api/comments/" + id, Map.of("content", "x")).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void onlyTheAuthorDeletes() {
        long vodId = vod();
        ApiSession author = ApiSession.member(http);
        Object id = author.post(commentsOf(vodId), Map.of("content", "지울 댓글")).getBody().get("id");
        String path = "/api/comments/" + id;

        assertThat(ApiSession.visitor(http).delete(path).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        ResponseEntity<Map<String, Object>> byOther = ApiSession.member(http).delete(path);
        assertThat(byOther.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(byOther.getBody()).containsEntry("detail", "내 댓글만 삭제할 수 있습니다.");
        assertThat(content(ApiSession.visitor(http).get(commentsOf(vodId)))).hasSize(1);

        assertThat(author.delete(path).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        assertThat(content(ApiSession.visitor(http).get(commentsOf(vodId)))).isEmpty();
        assertThat(author.delete(path).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }
}
