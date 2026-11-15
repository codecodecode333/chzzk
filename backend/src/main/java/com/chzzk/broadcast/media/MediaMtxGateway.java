package com.chzzk.broadcast.media;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

/**
 * {@link MediaServerGateway} over MediaMTX's control API ({@code /v3}, unauthenticated, bound to loopback in Compose).
 *
 * <p>A path counts as receiving media when it is {@code online} and its {@code inboundBytes} moved since the previous
 * look. {@code online} alone stays true for ~25s after a publisher vanishes without hanging up (a closed tab, a dead
 * phone) until MediaMTX's WebRTC session times out; the byte counter stops at once. A path seen for the first time
 * counts as receiving.
 */
@Component
class MediaMtxGateway implements MediaServerGateway {

    private static final int ITEMS_PER_PAGE = 100;

    private final RestClient api;

    /** {@code inboundBytes} per online path at the previous {@link #listPublishingPaths} call. */
    private Map<String, Long> previousInboundBytes = Map.of();

    MediaMtxGateway(RestClient.Builder builder, @Value("${broadcast.media-server.api-url}") String apiUrl) {
        this.api = builder.baseUrl(apiUrl).build();
    }

    @Override
    public synchronized Set<Long> listPublishingPaths() {
        Set<Long> ids = new HashSet<>();
        Map<String, Long> inboundBytes = new HashMap<>();
        int pageCount = 1;
        for (int page = 0; page < pageCount; page++) {
            PathList list = api.get()
                    .uri("/v3/paths/list?page={page}&itemsPerPage={n}", page, ITEMS_PER_PAGE)
                    .retrieve()
                    .body(PathList.class);
            if (list == null || list.items() == null) break;
            pageCount = list.pageCount();
            for (PathItem item : list.items()) {
                if (!item.online()) continue;
                inboundBytes.put(item.name(), item.inboundBytes());
                Long before = previousInboundBytes.get(item.name());
                if (before == null || before != item.inboundBytes()) {
                    MediaPaths.broadcastIdOf(item.name()).ifPresent(ids::add);
                }
            }
        }
        previousInboundBytes = inboundBytes;
        return ids;
    }

    @Override
    public void kickPublisher(long broadcastId) {
        PathItem path;
        try {
            // The path has a '/' that a URI template variable would encode, and it is built from a long, so it is safe.
            path = api.get().uri("/v3/paths/get/" + MediaPaths.of(broadcastId)).retrieve().body(PathItem.class);
        } catch (HttpClientErrorException.NotFound noPublisher) {
            return;
        }
        if (path == null || path.source() == null || !"webRTCSession".equals(path.source().type())) return;
        try {
            api.post().uri("/v3/webrtc/sessions/kick/{id}", path.source().id()).retrieve().toBodilessEntity();
        } catch (HttpClientErrorException e) {
            if (e.getStatusCode() != HttpStatus.NOT_FOUND) throw e; // already gone
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record PathList(int pageCount, List<PathItem> items) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record PathItem(String name, boolean online, long inboundBytes, Source source) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Source(String type, String id) {}
}
