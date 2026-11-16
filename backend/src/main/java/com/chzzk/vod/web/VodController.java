package com.chzzk.vod.web;

import com.chzzk.common.auth.MemberRef;
import com.chzzk.common.web.PageResponse;
import com.chzzk.common.web.Paging;
import com.chzzk.vod.domain.Vod;
import com.chzzk.vod.domain.VodNotFoundException;
import com.chzzk.vod.domain.VodService;
import com.chzzk.vod.domain.VodService.VideoFileMetadata;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.time.Instant;
import java.util.Objects;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/vods")
class VodController {

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
            @RequestParam(defaultValue = "" + Paging.DEFAULT_PAGE_SIZE) int size) {
        Paging.PageParams params = Paging.of(page, size);
        return PageResponse.of(vods.list(params.page(), params.size()).map(VodSummary::of));
    }

    @GetMapping("/{id}")
    VodResponse detail(@PathVariable long id) {
        return VodResponse.of(vods.find(id).orElseThrow(() -> new VodNotFoundException(id)));
    }

    /** 소유자 only. An absent (or {@code null}) field is left unchanged. */
    @PatchMapping(path = "/{id}", consumes = MediaType.APPLICATION_JSON_VALUE)
    VodResponse update(@PathVariable long id, @RequestBody UpdateVodRequest request) {
        return VodResponse.of(vods.update(id, request.title(), request.description()));
    }

    /** 소유자 only. */
    @DeleteMapping("/{id}")
    ResponseEntity<Void> delete(@PathVariable long id) {
        vods.delete(id);
        return ResponseEntity.noContent().build();
    }

    /**
     * Progressive MP4 (ADR-0001). Spring MVC serves a {@link Resource} body with HTTP Range support:
     * {@code 206} + {@code Content-Range} for range requests, {@code 200} otherwise.
     */
    @GetMapping("/{id}/stream")
    ResponseEntity<Resource> stream(@PathVariable long id) {
        Resource video = vods.openVideo(id).orElseThrow(() -> new VodNotFoundException(id));
        return ResponseEntity.ok().contentType(VIDEO_MP4).body(video);
    }

    record UpdateVodRequest(String title, String description) {}

    record VodResponse(Long id, String title, String description, Instant createdAt, String streamUrl,
                       MemberRef owner) {
        static VodResponse of(Vod vod) {
            return new VodResponse(vod.getId(), vod.getTitle(), vod.getDescription(), vod.getCreatedAt(),
                    vodPath(vod) + "/stream", vod.getOwner().toRef());
        }
    }

    record VodSummary(Long id, String title, Instant createdAt, MemberRef owner) {
        static VodSummary of(Vod vod) {
            return new VodSummary(vod.getId(), vod.getTitle(), vod.getCreatedAt(), vod.getOwner().toRef());
        }
    }
}
