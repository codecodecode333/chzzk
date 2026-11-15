package com.chzzk.broadcast.media;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

/**
 * {@link MediaServerGateway} over MediaMTX's control API ({@code /v3}, unauthenticated, bound to loopback in Compose).
 */
@Component
class MediaMtxGateway implements MediaServerGateway {

    private static final int ITEMS_PER_PAGE = 100;

    private final RestClient api;

    MediaMtxGateway(RestClient.Builder builder, @Value("${broadcast.media-server.api-url}") String apiUrl) {
        this.api = builder.baseUrl(apiUrl).build();
    }

    @Override
    public Set<Long> listPublishingPaths() {
        Set<Long> ids = new HashSet<>();
        int pageCount = 1;
        for (int page = 0; page < pageCount; page++) {
            PathList list = api.get()
                    .uri("/v3/paths/list?page={page}&itemsPerPage={n}", page, ITEMS_PER_PAGE)
                    .retrieve()
                    .body(PathList.class);
            if (list == null || list.items() == null) break;
            pageCount = list.pageCount();
            for (PathItem item : list.items()) {
                if (item.online()) MediaPaths.broadcastIdOf(item.name()).ifPresent(ids::add);
            }
        }
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
    record PathItem(String name, boolean online, Source source) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Source(String type, String id) {}
}
