package com.chzzk.vod;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
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
class VodApiTest {

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
    JdbcTemplate db;

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
        assertProblem(register("제목", null, "big.mp4", mp4BytesOfSize(MAX_FILE_SIZE_BYTES + 1)),
                HttpStatus.PAYLOAD_TOO_LARGE);
        assertThat(register("제목", null, "max.mp4", mp4BytesOfSize(MAX_FILE_SIZE_BYTES)).getStatusCode())
                .isEqualTo(HttpStatus.CREATED);
    }

    @Test
    void failedInsertLeavesNoStoredFileAndNoListedVod() throws IOException {
        String doomedTitle = "insert-will-fail";
        db.execute("ALTER TABLE vod ADD CONSTRAINT force_insert_failure CHECK (title <> '" + doomedTitle + "')");
        try {
            long filesBefore = storedFileCount();

            ResponseEntity<Map<String, Object>> response = register(doomedTitle, null, "clip.mp4", mp4Bytes());

            assertProblem(response, HttpStatus.INTERNAL_SERVER_ERROR);
            assertThat(storedFileCount()).isEqualTo(filesBefore);
            assertThat(listContent()).noneMatch(vod -> doomedTitle.equals(vod.get("title")));
        } finally {
            db.execute("ALTER TABLE vod DROP CONSTRAINT force_insert_failure");
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
