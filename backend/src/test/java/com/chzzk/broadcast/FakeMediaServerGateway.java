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
    private volatile boolean unreachable;

    public void startPublishing(long broadcastId) {
        publishing.add(broadcastId);
    }

    public void stopPublishing(long broadcastId) {
        publishing.remove(broadcastId);
    }

    /** From now until {@link #reset()}, asking who is publishing fails as if the media server were down. */
    public void becomeUnreachable() {
        unreachable = true;
    }

    public List<Long> kicked() {
        return List.copyOf(kicked);
    }

    public void reset() {
        publishing.clear();
        kicked.clear();
        unreachable = false;
    }

    @Override
    public Set<Long> listPublishingPaths() {
        if (unreachable) throw new IllegalStateException("media server unreachable (fake)");
        return Set.copyOf(publishing);
    }

    @Override
    public void kickPublisher(long broadcastId) {
        kicked.add(broadcastId);
        publishing.remove(broadcastId);
    }
}
