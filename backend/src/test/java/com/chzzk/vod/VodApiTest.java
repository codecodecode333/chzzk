package com.chzzk.vod;

import static org.assertj.core.api.Assertions.assertThat;

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
}
