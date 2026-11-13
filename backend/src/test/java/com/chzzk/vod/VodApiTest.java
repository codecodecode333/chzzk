package com.chzzk.vod;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import java.net.Socket;
import java.time.Duration;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** HTTP API seam: full Spring context against Testcontainers PostgreSQL. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@ExtendWith(OutputCaptureExtension.class)
class VodApiTest {

    static final String CRLF = "\r\n";

    @LocalServerPort
    int port;

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @TempDir
    static Path storageRoot;

    @DynamicPropertySource
    static void storage(DynamicPropertyRegistry registry) {
        registry.add("vod.storage.local.root", () -> storageRoot.toString());
        registry.add("vod.upload.max-file-size", () -> MAX_FILE_SIZE_BYTES + "B"); // stands in for the 1GB default
    }

    // Above a max-length description (servlet part limits apply to every part), far below 1GB.
    static final int MAX_FILE_SIZE_BYTES = 16 * 1024;

    static byte[] mp4BytesOfSize(int size) {
        return Arrays.copyOf(mp4Bytes(), size);
    }

    @Autowired
    TestRestTemplate http;

    @Autowired
    JdbcTemplate jdbc;

    /** Test setup only: start from an empty VOD table so page totals are predictable. */
    void noVods() {
        jdbc.update("DELETE FROM vod");
    }

    ResponseEntity<Map<String, Object>> getList(String query) {
        return http.exchange("/api/vods" + query, HttpMethod.GET, null, new ParameterizedTypeReference<>() {});
    }

    @SuppressWarnings("unchecked")
    static List<String> titles(ResponseEntity<Map<String, Object>> page) {
        return ((List<Map<String, Object>>) page.getBody().get("content")).stream()
                .map(vod -> (String) vod.get("title"))
                .toList();
    }

    static long storedFileCount() throws IOException {
        try (Stream<Path> files = Files.walk(storageRoot)) {
            return files.filter(Files::isRegularFile).count();
        }
    }

    /** Minimal bytes that start with an MP4 `ftyp` box. */
    static byte[] mp4Bytes() {
        byte[] header = {0, 0, 0, 0x18, 'f', 't', 'y', 'p', 'i', 's', 'o', 'm'};
        byte[] body = "rest-of-the-video".getBytes(StandardCharsets.US_ASCII);
        byte[] all = new byte[header.length + body.length];
        System.arraycopy(header, 0, all, 0, header.length);
        System.arraycopy(body, 0, all, header.length, body.length);
        return all;
    }

    ResponseEntity<Map<String, Object>> register(String title, String description, String filename, byte[] bytes) {
        MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
        form.add("title", title);
        if (description != null) {
            form.add("description", description);
        }
        form.add("file", new ByteArrayResource(bytes) {
            @Override
            public String getFilename() {
                return filename;
            }
        });
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        return http.exchange("/api/vods", HttpMethod.POST, new HttpEntity<>(form, headers),
                new ParameterizedTypeReference<>() {});
    }

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> listContent() {
        ResponseEntity<Map<String, Object>> page =
                http.exchange("/api/vods", HttpMethod.GET, null, new ParameterizedTypeReference<>() {});
        assertThat(page.getStatusCode()).isEqualTo(HttpStatus.OK);
        return (List<Map<String, Object>>) page.getBody().get("content");
    }

    @Test
    void registeredVodIsListedFirst() {
        register("older", null, "a.mp4", mp4Bytes());

        ResponseEntity<Map<String, Object>> created = register("내 다시보기", "설명", "clip.mp4", mp4Bytes());

        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        Object id = created.getBody().get("id");
        assertThat(id).isNotNull();
        assertThat(created.getBody()).containsEntry("title", "내 다시보기").containsEntry("description", "설명");
        assertThat(created.getBody().get("createdAt")).isNotNull();
        assertThat(created.getHeaders().getLocation()).hasPath("/api/vods/" + id);

        List<Map<String, Object>> listed = listContent();

        Map<String, Object> first = listed.get(0);
        assertThat(first).containsOnlyKeys("id", "title", "createdAt");
        assertThat(first).containsEntry("id", id).containsEntry("title", "내 다시보기");
        assertThat(listed.get(1)).containsEntry("title", "older");
    }

    ResponseEntity<Map<String, Object>> getJson(String url) {
        return http.exchange(url, HttpMethod.GET, null, new ParameterizedTypeReference<>() {});
    }

    @Test
    void detailReturnsVodWithServerComputedStreamUrl() {
        Object id = register("상세", "설명", "clip.mp4", mp4Bytes()).getBody().get("id");

        ResponseEntity<Map<String, Object>> detail = getJson("/api/vods/" + id);

        assertThat(detail.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(detail.getBody()).containsOnlyKeys("id", "title", "description", "createdAt", "streamUrl");
        assertThat(detail.getBody())
                .containsEntry("id", id)
                .containsEntry("title", "상세")
                .containsEntry("description", "설명")
                .containsEntry("streamUrl", "/api/vods/" + id + "/stream");
        assertThat(detail.getBody().get("createdAt")).isNotNull();
    }

    @Test
    void detailOfUnknownVodIsProblemDetails404() {
        ResponseEntity<Map<String, Object>> detail = getJson("/api/vods/999999");

        assertThat(detail.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(detail.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(detail.getBody()).containsEntry("status", 404);
    }

    ResponseEntity<byte[]> stream(Object id, String range) {
        HttpHeaders headers = new HttpHeaders();
        if (range != null) {
            headers.set(HttpHeaders.RANGE, range);
        }
        return http.exchange("/api/vods/" + id + "/stream", HttpMethod.GET, new HttpEntity<>(headers), byte[].class);
    }

    @Test
    void streamWithoutRangeReturnsWholeFile() {
        byte[] video = mp4Bytes();
        Object id = register("전체", null, "clip.mp4", video).getBody().get("id");

        ResponseEntity<byte[]> response = stream(id, null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.parseMediaType("video/mp4"));
        assertThat(response.getHeaders().getFirst(HttpHeaders.ACCEPT_RANGES)).isEqualTo("bytes");
        assertThat(response.getBody()).isEqualTo(video);
    }

    @Test
    void streamWithRangeReturnsPartialContentSlice() {
        byte[] video = mp4Bytes(); // 29 bytes
        Object id = register("부분", null, "clip.mp4", video).getBody().get("id");

        ResponseEntity<byte[]> middle = stream(id, "bytes=4-11");

        assertThat(middle.getStatusCode()).isEqualTo(HttpStatus.PARTIAL_CONTENT);
        assertThat(middle.getHeaders().getFirst(HttpHeaders.CONTENT_RANGE)).isEqualTo("bytes 4-11/29");
        assertThat(middle.getHeaders().getFirst(HttpHeaders.ACCEPT_RANGES)).isEqualTo("bytes");
        assertThat(middle.getHeaders().getContentType()).isEqualTo(MediaType.parseMediaType("video/mp4"));
        assertThat(new String(middle.getBody(), StandardCharsets.US_ASCII)).isEqualTo("ftypisom");

        ResponseEntity<byte[]> openEnded = stream(id, "bytes=20-");

        assertThat(openEnded.getStatusCode()).isEqualTo(HttpStatus.PARTIAL_CONTENT);
        assertThat(openEnded.getHeaders().getFirst(HttpHeaders.CONTENT_RANGE)).isEqualTo("bytes 20-28/29");
        assertThat(new String(openEnded.getBody(), StandardCharsets.US_ASCII)).isEqualTo("the-video");
    }

    @Test
    void streamIs404WhenVodIsUnknown() {
        assertThat(stream(999999, null).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void streamIs404WhenStoredFileWasRemoved() throws IOException {
        Object id = register("사라진 파일", null, "clip.mp4", mp4Bytes()).getBody().get("id");
        Path newest;
        try (Stream<Path> files = Files.walk(storageRoot)) {
            newest = files.filter(Files::isRegularFile)
                    .max(Comparator.comparing(VodApiTest::lastModified))
                    .orElseThrow();
        }
        Files.delete(newest);

        assertThat(stream(id, null).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(stream(id, "bytes=0-3").getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    static FileTime lastModified(Path file) {
        try {
            return Files.getLastModifiedTime(file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Test
    void listIsPagedNewestFirstAcrossPageBoundary() {
        noVods();
        for (int i = 1; i <= 21; i++) {
            register("vod " + i, null, "v.mp4", mp4Bytes());
        }

        ResponseEntity<Map<String, Object>> first = getList("");
        ResponseEntity<Map<String, Object>> second = getList("?page=1");

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(first.getBody()).containsEntry("page", 0).containsEntry("size", 20)
                .containsEntry("totalElements", 21).containsEntry("totalPages", 2);
        assertThat(titles(first)).hasSize(20).startsWith("vod 21", "vod 20").endsWith("vod 2");
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(second.getBody()).containsEntry("page", 1).containsEntry("totalElements", 21);
        assertThat(titles(second)).containsExactly("vod 1");
    }

    @Test
    void outOfRangePageIsEmptyAndOversizedPageIsCapped() {
        noVods();
        register("only", null, "v.mp4", mp4Bytes());

        ResponseEntity<Map<String, Object>> beyond = getList("?page=5");
        ResponseEntity<Map<String, Object>> huge = getList("?size=100000");

        assertThat(beyond.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(beyond.getBody()).containsEntry("page", 5).containsEntry("totalElements", 1)
                .containsEntry("totalPages", 1);
        assertThat(titles(beyond)).isEmpty();
        assertThat(huge.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(huge.getBody()).containsEntry("size", 100);
        assertThat(titles(huge)).containsExactly("only");
    }

    @Test
    void invalidListParamsAreRejectedAsProblemDetails() {
        for (String query : List.of("?page=-1", "?size=0", "?size=-5", "?page=abc", "?size=xyz")) {
            ResponseEntity<Map<String, Object>> response = getList(query);

            assertThat(response.getStatusCode()).as(query).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(response.getHeaders().getContentType()).as(query)
                    .isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
            assertThat(response.getBody()).as(query).containsEntry("status", 400).containsKey("title");
        }
    }

    @SuppressWarnings("unchecked")
    static void assertProblem(ResponseEntity<Map<String, Object>> response, HttpStatus status) {
        assertThat(response.getStatusCode()).isEqualTo(status);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(response.getBody())
                .containsEntry("status", status.value())
                .containsKeys("type", "title", "detail", "instance");
    }

    @SuppressWarnings("unchecked")
    static List<String> invalidFields(ResponseEntity<Map<String, Object>> response) {
        List<Map<String, Object>> errors = (List<Map<String, Object>>) response.getBody().get("errors");
        return errors.stream().map(e -> (String) e.get("field")).toList();
    }

    @Test
    void whitespaceOnlyTitleIsRejectedNamingTheField() {
        ResponseEntity<Map<String, Object>> response = register("   ", null, "clip.mp4", mp4Bytes());

        assertProblem(response, HttpStatus.BAD_REQUEST);
        assertThat(invalidFields(response)).containsExactly("title");
    }

    @Test
    void titleOverOneHundredCharsIsRejectedButExactlyOneHundredIsAccepted() {
        ResponseEntity<Map<String, Object>> tooLong = register("가".repeat(101), null, "clip.mp4", mp4Bytes());

        assertProblem(tooLong, HttpStatus.BAD_REQUEST);
        assertThat(invalidFields(tooLong)).containsExactly("title");
        assertThat(register("  " + "가".repeat(100) + "  ", null, "clip.mp4", mp4Bytes()).getStatusCode())
                .isEqualTo(HttpStatus.CREATED);
    }

    @Test
    void descriptionOverTwoThousandCharsIsRejectedButExactlyTwoThousandIsAccepted() {
        ResponseEntity<Map<String, Object>> tooLong = register("제목", "a".repeat(2001), "clip.mp4", mp4Bytes());

        assertProblem(tooLong, HttpStatus.BAD_REQUEST);
        assertThat(invalidFields(tooLong)).containsExactly("description");
        assertThat(register("제목", "a".repeat(2000), "clip.mp4", mp4Bytes()).getStatusCode())
                .isEqualTo(HttpStatus.CREATED);
    }

    @Test
    void fileWithoutMp4ExtensionIsRejectedAsUnsupported() {
        assertProblem(register("제목", null, "clip.avi", mp4Bytes()), HttpStatus.UNSUPPORTED_MEDIA_TYPE);
    }

    @Test
    void mp4ExtensionWithoutFtypSignatureIsRejectedAsUnsupported() {
        byte[] spoofed = "this is really a text file".getBytes(StandardCharsets.US_ASCII);

        assertProblem(register("제목", null, "clip.mp4", spoofed), HttpStatus.UNSUPPORTED_MEDIA_TYPE);
    }

    @Test
    void fileOverTheConfiguredMaxSizeIsRejectedButExactlyTheMaxIsAccepted() {
        ResponseEntity<Map<String, Object>> tooBig = register("제목", null, "big.mp4", mp4BytesOfSize(MAX_FILE_SIZE_BYTES + 1));

        assertProblem(tooBig, HttpStatus.PAYLOAD_TOO_LARGE);
        assertThat(tooBig.getBody()).containsEntry("detail", "영상 파일은 16KB 이하만 등록할 수 있습니다.");
        assertThat(register("제목", null, "max.mp4", mp4BytesOfSize(MAX_FILE_SIZE_BYTES)).getStatusCode())
                .isEqualTo(HttpStatus.CREATED);
    }

    @Test
    void requestFarBeyondTheConfiguredFileLimitStillGetsTheProblemResponse() {
        // Several MB more than the limit: the server must read past the rejected body so the 413 reaches the client.
        ResponseEntity<Map<String, Object>> response =
                register("제목", null, "huge.mp4", mp4BytesOfSize(8 * 1024 * 1024));

        assertProblem(response, HttpStatus.PAYLOAD_TOO_LARGE);
    }

    @Test
    void requestSizeLimitFollowsTheConfiguredFileLimit() {
        MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
        form.add("title", "제목");
        form.add("file", new ByteArrayResource(mp4Bytes()) {
            @Override
            public String getFilename() {
                return "clip.mp4";
            }
        });
        // Every part is under the file limit, but together they are far above it plus headroom
        // (Tomcat's default max-part-count is 10).
        String filler = "x".repeat(MAX_FILE_SIZE_BYTES - 1024);
        for (int i = 0; i < 8; i++) {
            form.add("filler" + i, filler);
        }
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);

        ResponseEntity<Map<String, Object>> response = http.exchange("/api/vods", HttpMethod.POST,
                new HttpEntity<>(form, headers), new ParameterizedTypeReference<>() {});

        assertProblem(response, HttpStatus.PAYLOAD_TOO_LARGE);
        assertThat(response.getBody()).containsEntry("detail", "영상 파일은 16KB 이하만 등록할 수 있습니다.");
    }

    @Test
    void titleOfOnlyNonBreakingAndIdeographicSpacesIsRejected() {
        // Same whitespace as the browser's String.prototype.trim(), which the form validates with.
        ResponseEntity<Map<String, Object>> response =
                register("\u00A0\u3000\uFEFF\u2007", null, "clip.mp4", mp4Bytes());

        assertProblem(response, HttpStatus.BAD_REQUEST);
        assertThat(invalidFields(response)).containsExactly("title");
    }

    @Test
    void titleIsTrimmedOfNonBreakingSpaces() {
        ResponseEntity<Map<String, Object>> created = register("\u00A0제목\u00A0", null, "clip.mp4", mp4Bytes());

        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(created.getBody()).containsEntry("title", "제목");
    }

    @Test
    void blankDescriptionIsStoredAsNoDescription() {
        Object emptyId = register("빈 설명", "", "clip.mp4", mp4Bytes()).getBody().get("id");
        Object blankId = register("공백 설명", "  \n\u00A0", "clip.mp4", mp4Bytes()).getBody().get("id");

        assertThat(getJson("/api/vods/" + emptyId).getBody()).containsEntry("description", null);
        assertThat(getJson("/api/vods/" + blankId).getBody()).containsEntry("description", null);
    }

    @Test
    void clientDisconnectDuringStreamingIsNotLoggedAsAnError(CapturedOutput output) throws Exception {
        Object id = register("끊김", null, "clip.mp4", mp4Bytes()).getBody().get("id");
        Path stored;
        try (Stream<Path> files = Files.walk(storageRoot)) {
            stored = files.filter(Files::isRegularFile)
                    .max(Comparator.comparing(VodApiTest::lastModified))
                    .orElseThrow();
        }
        Files.write(stored, new byte[64 * 1024 * 1024]); // far more than socket buffers hold

        try (Socket socket = new Socket("localhost", port)) {
            socket.setReceiveBufferSize(1024);
            socket.getOutputStream().write(("GET /api/vods/" + id + "/stream HTTP/1.1" + CRLF
                            + "Host: localhost" + CRLF + CRLF)
                    .getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();
            socket.getInputStream().readNBytes(1024); // the response has started
            socket.setSoLinger(true, 0); // abort with RST, like a <video> seeking away
        }

        Awaitility.await().during(Duration.ofSeconds(2)).atMost(Duration.ofSeconds(3))
                .until(() -> !output.getAll().contains("Unhandled API error"));
    }

    @Test
    void clientAbortingAnUploadIsNotLoggedAsAnError(CapturedOutput output) throws Exception {
        String boundary = "abort-boundary";
        String head = "--" + boundary + CRLF
                + "Content-Disposition: form-data; name=\"title\"" + CRLF + CRLF + "제목" + CRLF
                + "--" + boundary + CRLF
                + "Content-Disposition: form-data; name=\"file\"; filename=\"clip.mp4\"" + CRLF
                + "Content-Type: video/mp4" + CRLF + CRLF;
        byte[] partialBody = head.getBytes(StandardCharsets.UTF_8);

        try (Socket socket = new Socket("localhost", port)) {
            OutputStream out = socket.getOutputStream();
            out.write(("POST /api/vods HTTP/1.1" + CRLF + "Host: localhost" + CRLF
                    + "Content-Type: multipart/form-data; boundary=" + boundary + CRLF
                    + "Content-Length: " + (partialBody.length + 10_000) + CRLF + CRLF)
                    .getBytes(StandardCharsets.US_ASCII));
            out.write(partialBody);
            out.write(mp4Bytes());
            out.flush();
            Thread.sleep(300); // let the server start reading the body
            socket.setSoLinger(true, 0); // the viewer closed the tab mid-upload
        }

        Awaitility.await().during(Duration.ofSeconds(2)).atMost(Duration.ofSeconds(3))
                .until(() -> !output.getAll().contains("Unhandled API error"));
    }

    @Test
    void failedInsertLeavesNoStoredFileAndNoListedVod() throws IOException {
        String doomedTitle = "insert-will-fail";
        jdbc.execute("ALTER TABLE vod ADD CONSTRAINT force_insert_failure CHECK (title <> '" + doomedTitle + "')");
        try {
            long filesBefore = storedFileCount();

            ResponseEntity<Map<String, Object>> response = register(doomedTitle, null, "clip.mp4", mp4Bytes());

            assertProblem(response, HttpStatus.INTERNAL_SERVER_ERROR);
            assertThat(storedFileCount()).isEqualTo(filesBefore);
            assertThat(listContent()).noneMatch(vod -> doomedTitle.equals(vod.get("title")));
        } finally {
            jdbc.execute("ALTER TABLE vod DROP CONSTRAINT force_insert_failure");
        }
    }

    @Test
    void duplicateTitlesCanBothBeRegistered() {
        assertThat(register("같은 제목", null, "a.mp4", mp4Bytes()).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(register("같은 제목", null, "b.mp4", mp4Bytes()).getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    @Test
    void unknownApiRouteAnswersInTheSameProblemShape() {
        ResponseEntity<Map<String, Object>> response =
                http.exchange("/api/nothing-here", HttpMethod.GET, null, new ParameterizedTypeReference<>() {});

        assertProblem(response, HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).containsEntry("instance", "/api/nothing-here");
    }

    @Test
    void emptyFileIsRejectedNamingTheField() {
        ResponseEntity<Map<String, Object>> response = register("제목", null, "clip.mp4", new byte[0]);

        assertProblem(response, HttpStatus.BAD_REQUEST);
        assertThat(invalidFields(response)).containsExactly("file");
    }
}
