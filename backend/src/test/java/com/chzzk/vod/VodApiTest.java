package com.chzzk.vod;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Comparator;
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
    }

    @Autowired
    TestRestTemplate http;

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
}
