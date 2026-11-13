package com.chzzk.vod.web;

import com.chzzk.vod.vod.Vod;
import com.chzzk.vod.vod.VodService;
import com.chzzk.vod.vod.VodService.VideoFileMetadata;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import org.springframework.data.domain.Page;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/vods")
class VodController {

    private static final int DEFAULT_PAGE_SIZE = 20;

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
        return ResponseEntity.created(URI.create("/api/vods/" + vod.getId())).body(VodResponse.of(vod));
    }

    @GetMapping
    PageResponse<VodSummary> list() {
        Page<Vod> page = vods.list(0, DEFAULT_PAGE_SIZE);
        return new PageResponse<>(page.map(VodSummary::of).getContent(), page.getNumber(), page.getSize(),
                page.getTotalElements(), page.getTotalPages());
    }

    record VodResponse(Long id, String title, String description, Instant createdAt, String streamUrl) {
        static VodResponse of(Vod vod) {
            return new VodResponse(vod.getId(), vod.getTitle(), vod.getDescription(), vod.getCreatedAt(),
                    "/api/vods/" + vod.getId() + "/stream");
        }
    }

    record VodSummary(Long id, String title, Instant createdAt) {
        static VodSummary of(Vod vod) {
            return new VodSummary(vod.getId(), vod.getTitle(), vod.getCreatedAt());
        }
    }

    record PageResponse<T>(List<T> content, int page, int size, long totalElements, int totalPages) {}
}
