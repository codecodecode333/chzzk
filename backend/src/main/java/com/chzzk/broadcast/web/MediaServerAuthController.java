package com.chzzk.broadcast.web;

import com.chzzk.broadcast.domain.BroadcastLifecycle;
import com.chzzk.broadcast.media.MediaPaths;
import java.util.OptionalLong;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * MediaMTX's external HTTP auth callback ({@code authMethod: http}), called once per WHIP publish and WHEP read.
 * Any 2xx allows, anything else denies; an allow must carry no body (or valid JSON), so both answers are empty.
 *
 * <p>Internal: it lives outside {@code /api}, so the frontend's dev proxy (which forwards only {@code /api} and
 * {@code /live}) never exposes it; only MediaMTX calls it.
 */
@RestController
class MediaServerAuthController {

    private final BroadcastLifecycle broadcasts;

    MediaServerAuthController(BroadcastLifecycle broadcasts) {
        this.broadcasts = broadcasts;
    }

    /** The fields of MediaMTX's request we use; the 송출 키 arrives as {@code token} from WHIP's Bearer header. */
    record AuthRequest(String action, String path, String token) {}

    @PostMapping("/internal/mediamtx/auth")
    ResponseEntity<Void> authorize(@RequestBody AuthRequest request) {
        OptionalLong id = MediaPaths.broadcastIdOf(request.path());
        boolean allowed = id.isPresent() && switch (String.valueOf(request.action())) {
            case "publish" -> broadcasts.authorizePublish(id.getAsLong(), request.token());
            case "read" -> broadcasts.authorizeRead(id.getAsLong());
            default -> false;
        };
        return ResponseEntity.status(allowed ? HttpStatus.NO_CONTENT : HttpStatus.UNAUTHORIZED).build();
    }
}
