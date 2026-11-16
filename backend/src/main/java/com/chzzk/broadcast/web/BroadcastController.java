package com.chzzk.broadcast.web;

import com.chzzk.broadcast.domain.Broadcast;
import com.chzzk.broadcast.domain.BroadcastLifecycle;
import com.chzzk.broadcast.domain.BroadcastStatus;
import com.chzzk.broadcast.domain.PublishKey;
import com.chzzk.broadcast.media.MediaPaths;
import com.chzzk.common.auth.MemberRef;
import com.chzzk.common.web.PageResponse;
import com.chzzk.common.web.Paging;
import java.net.URI;
import java.time.Instant;
import org.springframework.http.HttpStatus;
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
                        Owner.of(b), started.publishKey().value(), mediaUrl(b, "whip")));
    }

    @GetMapping
    PageResponse<BroadcastSummary> list(
            @RequestParam BroadcastStatus status,
            @RequestParam(required = false) Long ownerId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "" + Paging.DEFAULT_PAGE_SIZE) int size) {
        Paging.PageParams params = Paging.of(page, size);
        return PageResponse.of(broadcasts.list(status, ownerId, params.page(), params.size()).map(BroadcastSummary::of));
    }

    @GetMapping("/{id}")
    BroadcastResponse detail(@PathVariable long id) {
        return BroadcastResponse.of(broadcasts.get(id).orElseThrow(() -> notFound(id)));
    }

    record RenameRequest(String title) {}

    /** "제목 수정" by the 소유자, 방송 중 or 종료. */
    @PatchMapping("/{id}")
    BroadcastResponse rename(@PathVariable long id, @RequestBody RenameRequest request) {
        return BroadcastResponse.of(broadcasts.rename(id, request.title()).orElseThrow(() -> notFound(id)));
    }

    /** "삭제" of a 종료 방송 by the 소유자, with its 댓글; a 방송 중 방송 is 409 until it ends. */
    @DeleteMapping("/{id}")
    ResponseEntity<Void> delete(@PathVariable long id) {
        if (!broadcasts.delete(id)) throw notFound(id);
        return ResponseEntity.noContent().build();
    }

    /** "방송 종료" by the 소유자's login session; ending twice is a successful no-op. */
    @PostMapping("/{id}/end")
    ResponseEntity<Void> end(@PathVariable long id) {
        return switch (broadcasts.end(id)) {
            case ENDED, ALREADY_ENDED -> ResponseEntity.noContent().build();
            case NOT_FOUND -> throw notFound(id);
        };
    }

    /** "이어서 송출": a fresh 송출 키 for the 소유자's 방송 중 방송; the previous key stops working. */
    @PostMapping("/{id}/publish-key")
    PublishKeyResponse issuePublishKey(@PathVariable long id) {
        PublishKey publishKey = broadcasts.issuePublishKey(id).orElseThrow(() -> notFound(id));
        return new PublishKeyResponse(publishKey.value(), "/live/" + MediaPaths.of(id) + "/whip");
    }

    private static ResponseStatusException notFound(long id) {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, id + "번 방송을 찾을 수 없습니다.");
    }

    /**
     * WHIP/WHEP endpoint on the page's own origin: {@code /live/...} is proxied to MediaMTX (ADR-0002), so the page
     * never calls the media server cross-origin.
     */
    private static String mediaUrl(Broadcast b, String protocol) {
        return "/live/" + MediaPaths.of(b.getId()) + "/" + protocol;
    }

    /** The 소유자 as every 방송 response shows it. */
    record Owner(long id, String nickname) {
        static Owner of(Broadcast b) {
            MemberRef owner = b.getOwner();
            return new Owner(owner.id(), owner.nickname());
        }
    }

    /** With {@link PublishKeyResponse}, the only responses that carry a 송출 키. */
    record StartedResponse(Long id, String title, BroadcastStatus status, Instant startedAt, Owner owner,
                           String publishKey, String whipUrl) {}

    record PublishKeyResponse(String publishKey, String whipUrl) {}

    /** A 방송 card in the 방송 목록; the player URL comes from the detail. */
    record BroadcastSummary(Long id, String title, BroadcastStatus status, Instant startedAt, Instant endedAt,
                            Owner owner) {
        static BroadcastSummary of(Broadcast b) {
            return new BroadcastSummary(b.getId(), b.getTitle(), b.getStatus(), b.getStartedAt(), b.getEndedAt(),
                    Owner.of(b));
        }
    }

    /** {@code whepUrl} is {@code null} unless the 방송 is 방송 중. */
    record BroadcastResponse(Long id, String title, BroadcastStatus status, Instant startedAt, Instant endedAt,
                             Owner owner, String whepUrl) {
        static BroadcastResponse of(Broadcast b) {
            return new BroadcastResponse(b.getId(), b.getTitle(), b.getStatus(), b.getStartedAt(), b.getEndedAt(),
                    Owner.of(b), b.isLive() ? mediaUrl(b, "whep") : null);
        }
    }
}
