package com.chzzk.broadcast.web;

import com.chzzk.broadcast.domain.BroadcastLifecycle;
import com.chzzk.broadcast.domain.PublishKey;
import com.chzzk.broadcast.media.MediaPaths;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.OptionalLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * MediaMTX's external HTTP auth callback ({@code authMethod: http}), called once per WHIP publish and WHEP read.
 * Any 2xx allows, anything else denies; an allow must carry no body (or valid JSON), so both answers are empty
 * (a documented exception to the Problem Details rule: this is MediaMTX's contract, not our API).
 *
 * <p>Internal: it lives outside {@code /api}, so the frontend's dev proxy (which forwards only {@code /api} and
 * {@code /live}) never exposes it. The backend still listens on every interface, so a call must also carry the shared
 * secret ({@code broadcast.media-server.auth-secret}) as {@code ?secret=}: MediaMTX can't add headers, but it keeps the
 * query of its {@code authHTTPAddress}. Without a configured secret every call is refused.
 */
@RestController
class MediaServerAuthController {

    private static final Logger log = LoggerFactory.getLogger(MediaServerAuthController.class);

    private final BroadcastLifecycle broadcasts;
    private final byte[] secret;

    MediaServerAuthController(BroadcastLifecycle broadcasts,
                              @Value("${broadcast.media-server.auth-secret:}") String secret) {
        this.broadcasts = broadcasts;
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
        if (secret.isBlank()) {
            log.warn("broadcast.media-server.auth-secret (MEDIAMTX_AUTH_SECRET) is not set: MediaMTX's auth callback "
                    + "refuses everything, so no 방송 can be published or watched (see README)");
        }
    }

    /** The fields of MediaMTX's request we use; the 송출 키 arrives as {@code token} from WHIP's Bearer header. */
    record AuthRequest(String action, String path, String token) {}

    @PostMapping("/internal/mediamtx/auth")
    ResponseEntity<Void> authorize(@RequestParam(name = "secret", required = false) String secret,
                                   @RequestBody AuthRequest request) {
        boolean allowed = isMediaServer(secret) && allows(request);
        return ResponseEntity.status(allowed ? HttpStatus.NO_CONTENT : HttpStatus.UNAUTHORIZED).build();
    }

    private boolean isMediaServer(String presented) {
        return secret.length > 0 && presented != null
                && MessageDigest.isEqual(presented.getBytes(StandardCharsets.UTF_8), secret);
    }

    private boolean allows(AuthRequest request) {
        OptionalLong id = MediaPaths.broadcastIdOf(request.path());
        return id.isPresent() && switch (String.valueOf(request.action())) {
            case "publish" -> broadcasts.authorizePublish(id.getAsLong(), PublishKey.presented(request.token()));
            case "read" -> broadcasts.authorizeRead(id.getAsLong());
            default -> false;
        };
    }
}
