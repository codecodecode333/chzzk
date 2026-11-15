package com.chzzk.broadcast.domain;

import com.chzzk.broadcast.media.MediaServerGateway;
import com.chzzk.common.text.Text;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The broadcast lifecycle: the single owner of 방송 status rules, the 송출 키 and who may publish or read.
 *
 * <p>State lives only in the database, so it survives restarts. Time comes from the injected {@link Clock}.
 * The 60-second auto-end ({@link #sweep}) works the same way: it reads every 방송 중 방송 from the database.
 */
@Service
public class BroadcastLifecycle {

    static final int TITLE_MAX_CHARS = 100;

    /** A 방송 중 방송 that receives no media for longer than this ends on its own. */
    static final Duration AUTO_END_AFTER = Duration.ofSeconds(60);

    private static final Logger log = LoggerFactory.getLogger(BroadcastLifecycle.class);

    private final BroadcastRepository repository;
    private final Clock clock;
    private final MediaServerGateway mediaServer;
    private final TransactionTemplate transaction;

    BroadcastLifecycle(BroadcastRepository repository, Clock clock, MediaServerGateway mediaServer,
                       TransactionTemplate transaction) {
        this.repository = repository;
        this.clock = clock;
        this.mediaServer = mediaServer;
        this.transaction = transaction;
    }

    /** A just-started 방송 and its 송출 키, which is handed out only this once. */
    public record Started(Broadcast broadcast, String publishKey) {}

    /** "방송 시작": a new 방송 중 방송 with a fresh 송출 키. */
    @Transactional
    public Started start(String title) {
        title = title == null ? "" : Text.trimWhitespace(title);
        if (title.isEmpty()) {
            throw new InvalidBroadcastException("title", "제목을 입력해 주세요.");
        }
        if (Text.charCount(title) > TITLE_MAX_CHARS) {
            throw new InvalidBroadcastException("title", "제목은 " + TITLE_MAX_CHARS + "자 이하로 입력해 주세요.");
        }
        String publishKey = PublishKeys.generate();
        Broadcast broadcast = repository.save(new Broadcast(title, PublishKeys.hash(publishKey), now()));
        return new Started(broadcast, publishKey);
    }

    @Transactional(readOnly = true)
    public Optional<Broadcast> get(long id) {
        return repository.findById(id);
    }

    /** How an {@link #end} request turned out. */
    public enum EndResult {
        /** Ended now, or had already ended (ending twice is harmless). */
        ENDED,
        /** The 송출 키 is missing or belongs to another 방송; nothing changed. */
        WRONG_KEY,
        NOT_FOUND
    }

    /**
     * "방송 종료" by the 송출자: 방송 중 → 종료 with {@code ended_at = now}, then the publisher's media connection is cut
     * (which also drops its viewers). Only the 송출 키 holder may end; ending an ended 방송 keeps its {@code ended_at}.
     *
     * <p>The kick happens after the commit and is best effort: if the media server is unreachable, the 방송 is still
     * 종료, and the auth callback already refuses any re-publish or new viewer.
     */
    public EndResult end(long id, String publishKey) {
        EndResult result = transaction.execute(tx -> repository.findById(id)
                .map(b -> {
                    if (!PublishKeys.matches(publishKey, b.getPublishKeyHash())) return EndResult.WRONG_KEY;
                    b.end(now());
                    return EndResult.ENDED;
                })
                .orElse(EndResult.NOT_FOUND));
        if (result == EndResult.ENDED) kickPublisher(id);
        return result;
    }

    private void kickPublisher(long id) {
        try {
            mediaServer.kickPublisher(id);
        } catch (RuntimeException e) {
            log.warn("Could not kick the publisher of 방송 {}; the media server will refuse it anyway", id, e);
        }
    }

    /** Notes that media is arriving right now for these 방송 (ids of ended or unknown 방송 are ignored). */
    @Transactional
    public void recordPublishing(Set<Long> ids) {
        if (ids.isEmpty()) return;
        repository.recordPublishing(ids, BroadcastStatus.LIVE, now());
    }

    /**
     * The auto-end: every 방송 중 방송 whose media last arrived more than 60s before {@code now} (or that started more
     * than 60s ago and never sent any) becomes 종료, with {@code ended_at} = when its media stopped (the last
     * publishing time, or the start time). Their publishers, if any reappeared meanwhile, are cut afterwards.
     *
     * @return the ids of the 방송 it ended
     */
    public List<Long> sweep(Instant now) {
        Instant cutoff = now.minus(AUTO_END_AFTER);
        List<Long> ended = transaction.execute(tx -> repository.findLiveSilentSince(BroadcastStatus.LIVE, cutoff)
                .stream()
                .peek(b -> b.end(b.getLastActiveAt()))
                .map(Broadcast::getId)
                .toList());
        ended.forEach(this::kickPublisher);
        return ended;
    }

    /** 방송 중 newest started first; 종료 most recently ended first. */
    @Transactional(readOnly = true)
    public Page<Broadcast> list(BroadcastStatus status, int page, int size) {
        Sort order = switch (status) {
            case LIVE -> Sort.by(Sort.Order.desc("startedAt"), Sort.Order.desc("id"));
            case ENDED -> Sort.by(Sort.Order.desc("endedAt"), Sort.Order.desc("id"));
        };
        return repository.findByStatus(status, PageRequest.of(page, size, order));
    }

    /** Publishing is allowed only to a 방송 중 방송, and only with its 송출 키. */
    @Transactional(readOnly = true)
    public boolean authorizePublish(long id, String publishKey) {
        return repository.findById(id)
                .filter(Broadcast::isLive)
                .filter(b -> PublishKeys.matches(publishKey, b.getPublishKeyHash()))
                .isPresent();
    }

    /** Anyone may watch a 방송 중 방송; an ended or unknown one cannot be watched. */
    @Transactional(readOnly = true)
    public boolean authorizeRead(long id) {
        return repository.findById(id).filter(Broadcast::isLive).isPresent();
    }

    /** Now, by the injected clock, at the database's precision. */
    public Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS); // match TIMESTAMPTZ precision
    }
}
