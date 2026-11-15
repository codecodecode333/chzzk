package com.chzzk.broadcast.media;

import java.util.Set;

/**
 * What the backend needs from the media server (MediaMTX, ADR-0002), which owns the media while we own 방송 state.
 * Each 방송 is the media path {@link MediaPaths#of(long) broadcasts/{id}}. Faked in tests.
 */
public interface MediaServerGateway {

    /** Ids of the 방송 whose media path is currently receiving media from a publisher (asked every 10s). */
    Set<Long> listPublishingPaths();

    /** Cuts the publisher's media connection to the 방송, if any; a 방송 with no publisher is a no-op. */
    void kickPublisher(long broadcastId);
}
