package com.chzzk.vod.web;

import com.chzzk.vod.domain.Vod;
import com.chzzk.vod.domain.VodService;
import com.chzzk.vod.domain.VodService.VideoFileMetadata;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import org.springframework.core.io.Resource;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/vods")
class VodController {

    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 100;
    private static final MediaType VIDEO_MP4 = MediaType.parseMediaType("video/mp4");

    private final VodService vods;

    VodController(VodService vods) {
        this.vods = vods;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    ResponseEntity<VodResponse> register(
            @RequestParam(required = false) String title,
            @RequestParam(required = false) String description,
            @RequestParam(required = false) MultipartFile file) throws IOException {
        if (file == null || file.isEmpty()) {
            return created(vods.register(title, description, null, new VideoFileMetadata("", "")));
        }
        VideoFileMetadata metadata = new VideoFileMetadata(
                Objects.requireNonNullElse(file.getOriginalFilename(), ""),
                Objects.requireNonNullElse(file.getContentType(), MediaType.APPLICATION_OCTET_STREAM_VALUE));
        try (InputStream content = file.getInputStream()) {
            return created(vods.register(title, description, content, metadata));
        }
    }

    private static ResponseEntity<VodResponse> created(Vod vod) {
        return ResponseEntity.created(URI.create(vodPath(vod))).body(VodResponse.of(vod));
    }

    /** The VOD's resource path; the one place API URLs for a VOD are built. */
    private static String vodPath(Vod vod) {
        return "/api/vods/" + vod.getId();
    }

    @GetMapping
    PageResponse<VodSummary> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "" + DEFAULT_PAGE_SIZE) int size) {
        if (page < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "page는 0 이상이어야 합니다.");
        }
        if (size < 1) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "size는 1 이상이어야 합니다.");
        }
        return PageResponse.of(vods.list(page, Math.min(size, MAX_PAGE_SIZE)).map(VodSummary::of));
    }

    @GetMapping("/{id}")
    VodResponse detail(@PathVariable long id) {
        return VodResponse.of(vods.find(id).orElseThrow(() -> notFound(id)));
    }

    /**
     * Progressive MP4 (ADR-0001). Spring MVC serves a {@link Resource} body with HTTP Range support:
     * {@code 206} + {@code Content-Range} for range requests, {@code 200} otherwise.
     */
    @GetMapping("/{id}/stream")
    ResponseEntity<Resource> stream(@PathVariable long id) {
        Resource video = vods.openVideo(id).orElseThrow(() -> notFound(id));
        return ResponseEntity.ok().contentType(VIDEO_MP4).body(video);
    }

    private static ResponseStatusException notFound(long id) {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, id + "번 다시보기를 찾을 수 없습니다.");
    }

    record VodResponse(Long id, String title, String description, Instant createdAt, String streamUrl) {
        static VodResponse of(Vod vod) {
            return new VodResponse(vod.getId(), vod.getTitle(), vod.getDescription(), vod.getCreatedAt(),
                    vodPath(vod) + "/stream");
        }
    }

    record VodSummary(Long id, String title, Instant createdAt) {
        static VodSummary of(Vod vod) {
            return new VodSummary(vod.getId(), vod.getTitle(), vod.getCreatedAt());
        }
    }

    record PageResponse<T>(List<T> content, int page, int size, long totalElements, int totalPages) {
        static <T> PageResponse<T> of(Page<T> page) {
            return new PageResponse<>(page.getContent(), page.getNumber(), page.getSize(),
                    page.getTotalElements(), page.getTotalPages());
        }
    }
}
