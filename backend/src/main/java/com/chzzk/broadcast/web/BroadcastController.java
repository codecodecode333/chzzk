package com.chzzk.broadcast.web;

import com.chzzk.broadcast.domain.Broadcast;
import com.chzzk.broadcast.domain.BroadcastLifecycle;
import com.chzzk.broadcast.domain.BroadcastStatus;
import com.chzzk.broadcast.media.MediaPaths;
import java.net.URI;
import java.time.Instant;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** The public 방송 API. */
@RestController
@RequestMapping("/api/broadcasts")
class BroadcastController {

    private final BroadcastLifecycle broadcasts;

    BroadcastController(BroadcastLifecycle broadcasts) {
        this.broadcasts = broadcasts;
    }

    record StartRequest(String title) {}

    @PostMapping
    ResponseEntity<StartedResponse> start(@RequestBody StartRequest request) {
        BroadcastLifecycle.Started started = broadcasts.start(request.title());
        Broadcast b = started.broadcast();
        return ResponseEntity.created(URI.create("/api/broadcasts/" + b.getId()))
                .body(new StartedResponse(b.getId(), b.getTitle(), b.getStatus(), b.getStartedAt(),
                        started.publishKey(), mediaUrl(b, "whip")));
    }

    @GetMapping("/{id}")
    BroadcastResponse detail(@PathVariable long id) {
        Broadcast b = broadcasts.get(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                id + "번 방송을 찾을 수 없습니다."));
        return new BroadcastResponse(b.getId(), b.getTitle(), b.getStatus(), b.getStartedAt(), b.getEndedAt(),
                b.isLive() ? mediaUrl(b, "whep") : null);
    }

    /** "방송 종료": the 송출 키 comes as {@code Authorization: Bearer}; ending twice is a successful no-op. */
    @PostMapping("/{id}/end")
    ResponseEntity<Void> end(@PathVariable long id,
                             @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization) {
        return switch (broadcasts.end(id, bearerToken(authorization))) {
            case ENDED -> ResponseEntity.noContent().build();
            case WRONG_KEY -> throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "송출 키가 올바르지 않습니다.");
            case NOT_FOUND -> throw new ResponseStatusException(HttpStatus.NOT_FOUND, id + "번 방송을 찾을 수 없습니다.");
        };
    }

    private static String bearerToken(String authorization) {
        String prefix = "Bearer ";
        if (authorization == null || !authorization.regionMatches(true, 0, prefix, 0, prefix.length())) return null;
        return authorization.substring(prefix.length()).trim();
    }

    /**
     * WHIP/WHEP endpoint on the page's own origin: {@code /live/...} is proxied to MediaMTX (ADR-0002), so the page
     * never calls the media server cross-origin.
     */
    private static String mediaUrl(Broadcast b, String protocol) {
        return "/live/" + MediaPaths.of(b.getId()) + "/" + protocol;
    }

    /** The only response that carries the 송출 키. */
    record StartedResponse(Long id, String title, BroadcastStatus status, Instant startedAt, String publishKey,
                           String whipUrl) {}

    /** {@code whepUrl} is {@code null} unless the 방송 is 방송 중. */
    record BroadcastResponse(Long id, String title, BroadcastStatus status, Instant startedAt, Instant endedAt,
                             String whepUrl) {}
}
