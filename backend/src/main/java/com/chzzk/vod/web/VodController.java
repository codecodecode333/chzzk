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
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.ErrorResponseException;
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
    private static final int MAX_PAGE_SIZE = 100;

    private final VodService vods;

    VodController(VodService vods) {
        this.vods = vods;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    ResponseEntity<VodResponse> register(
            @RequestParam String title,
            @RequestParam(required = false) String description,
            @RequestParam MultipartFile file) throws IOException {
        VideoFileMetadata metadata = new VideoFileMetadata(
                Objects.requireNonNullElse(file.getOriginalFilename(), ""),
                Objects.requireNonNullElse(file.getContentType(), MediaType.APPLICATION_OCTET_STREAM_VALUE));
        Vod vod;
        try (InputStream content = file.getInputStream()) {
            vod = vods.register(title, description, content, metadata);
        }
        return ResponseEntity.created(URI.create("/api/vods/" + vod.getId())).body(VodResponse.of(vod));
    }

    @GetMapping
    PageResponse<VodSummary> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "" + DEFAULT_PAGE_SIZE) int size) {
        if (page < 0) {
            throw badRequest("page must be 0 or greater");
        }
        if (size < 1) {
            throw badRequest("size must be 1 or greater");
        }
        return PageResponse.of(vods.list(page, Math.min(size, MAX_PAGE_SIZE)).map(VodSummary::of));
    }

    private static ErrorResponseException badRequest(String detail) {
        return new ErrorResponseException(HttpStatus.BAD_REQUEST,
                ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, detail), null);
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

    record PageResponse<T>(List<T> content, int page, int size, long totalElements, int totalPages) {
        static <T> PageResponse<T> of(Page<T> page) {
            return new PageResponse<>(page.getContent(), page.getNumber(), page.getSize(),
                    page.getTotalElements(), page.getTotalPages());
        }
    }
}
