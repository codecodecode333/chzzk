package com.chzzk.broadcast;

import com.chzzk.broadcast.media.MediaServerGateway;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/** Stands in for MediaMTX: tests say which 방송 are receiving media, and read back which publishers were kicked. */
public class FakeMediaServerGateway implements MediaServerGateway {

    private final Set<Long> publishing = ConcurrentHashMap.newKeySet();
    private final List<Long> kicked = new CopyOnWriteArrayList<>();

    public void startPublishing(long broadcastId) {
        publishing.add(broadcastId);
    }

    public void stopPublishing(long broadcastId) {
        publishing.remove(broadcastId);
    }

    public List<Long> kicked() {
        return List.copyOf(kicked);
    }

    public void reset() {
        publishing.clear();
        kicked.clear();
    }

    @Override
    public Set<Long> listPublishingPaths() {
        return Set.copyOf(publishing);
    }

    @Override
    public void kickPublisher(long broadcastId) {
        kicked.add(broadcastId);
        publishing.remove(broadcastId);
    }
}
