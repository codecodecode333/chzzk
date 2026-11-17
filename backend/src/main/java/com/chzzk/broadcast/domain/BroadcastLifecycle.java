package com.chzzk.broadcast.domain;

import com.chzzk.broadcast.media.MediaServerGateway;
import com.chzzk.common.auth.CurrentMember;
import com.chzzk.common.auth.MemberRef;
import com.chzzk.common.error.ConflictException;
import com.chzzk.common.error.ForbiddenException;
import com.chzzk.common.error.UnauthenticatedException;
import com.chzzk.common.text.Title;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The broadcast lifecycle: the single owner of 방송 status rules, the 송출 키 and who may publish or read.
 *
 * <p>Who may control a 방송 is decided here too: only a logged-in 회원 starts one and becomes its 소유자 (and 송출자),
 * and only the 소유자, by their login session ({@link CurrentMember}), ends it, gets a fresh 송출 키, renames it or deletes it once ended. The 송출 키
 * itself only authenticates the media connection (the MediaMTX auth callback, {@link #authorizePublish}).
 *
 * <p>State lives only in the database, so it survives restarts. Time comes from the injected {@link Clock}.
 * The 60-second auto-end ({@link #sweep}) works the same way: it reads every 방송 중 방송 from the database.
 */
@Service
public class BroadcastLifecycle {

    /** A 방송 중 방송 that receives no media for longer than this ends on its own. */
    public static final Duration AUTO_END_AFTER = Duration.ofSeconds(60);

    private static final Logger log = LoggerFactory.getLogger(BroadcastLifecycle.class);

    private final BroadcastRepository repository;
    private final Clock clock;
    private final MediaServerGateway mediaServer;
    private final TransactionTemplate transaction;
    private final CurrentMember currentMember;
    private final BroadcastOwnerRepository owners;

    static final String ALREADY_LIVE = "이미 방송 중인 방송이 있습니다. 진행 중인 방송을 이어서 송출해 주세요.";
    static final String NOT_OWNER = "내 방송만 제어할 수 있습니다.";
    static final String NOT_LIVE = "이미 종료된 방송입니다.";
    static final String STILL_LIVE = "방송 중인 방송은 삭제할 수 없습니다. 방송을 먼저 종료하세요.";
    static final String HAS_DONATIONS = "후원 내역이 있는 방송은 삭제할 수 없습니다";

    BroadcastLifecycle(BroadcastRepository repository, Clock clock, MediaServerGateway mediaServer,
                       TransactionTemplate transaction, CurrentMember currentMember, BroadcastOwnerRepository owners) {
        this.repository = repository;
        this.clock = clock;
        this.mediaServer = mediaServer;
        this.transaction = transaction;
        this.currentMember = currentMember;
        this.owners = owners;
    }

    /** A just-started 방송 and its 송출 키, which is handed out only this once. */
    public record Started(Broadcast broadcast, PublishKey publishKey) {}

    /**
     * "방송 시작" by the logged-in 회원 (401 otherwise), who becomes its 소유자: a new 방송 중 방송 with a fresh 송출 키.
     * A 회원 who already has a 방송 중 방송 gets a {@link ConflictException} carrying its id as {@code broadcastId},
     * so the client can offer to resume it; the partial unique index settles concurrent starts the same way.
     */
    public Started start(String title) {
        MemberRef owner = currentMember.require();
        String normalized = Title.normalize(title, message -> new InvalidBroadcastException("title", message));
        try {
            return transaction.execute(tx -> {
                repository.findFirstByOwnerIdAndStatus(owner.id(), BroadcastStatus.LIVE).ifPresent(live -> {
                    throw alreadyLive(live.getId());
                });
                PublishKey publishKey = PublishKey.generate();
                BroadcastOwner member = owners.findById(owner.id()).orElseThrow(UnauthenticatedException::new);
                Broadcast broadcast = repository.saveAndFlush(
                        new Broadcast(member, normalized, publishKey.hash(), now()));
                return new Started(broadcast, publishKey);
            });
        } catch (DataIntegrityViolationException raced) {
            long liveId = repository.findFirstByOwnerIdAndStatus(owner.id(), BroadcastStatus.LIVE)
                    .map(Broadcast::getId)
                    .orElseThrow(() -> raced);
            throw alreadyLive(liveId);
        }
    }

    private static ConflictException alreadyLive(long id) {
        return new ConflictException(ALREADY_LIVE).with("broadcastId", id);
    }

    @Transactional(readOnly = true)
    public Optional<Broadcast> get(long id) {
        return repository.findById(id);
    }

    /** How an {@link #end} request turned out. */
    public enum EndResult {
        /** Ended now. */
        ENDED,
        /** Had already ended (by the 송출자 or the auto-end); ending twice is harmless and changes nothing. */
        ALREADY_ENDED,
        NOT_FOUND
    }

    /**
     * "방송 종료" by the 송출자: 방송 중 → 종료 with {@code ended_at = now}, then the publisher's media connection is cut
     * (which also drops its viewers). Only the 소유자 may end, by their login session (401 for a visitor, 403 for
     * another 회원); ending an ended 방송 keeps its {@code ended_at}.
     *
     * <p>Racing the auto-end, exactly one of them ends the 방송 (and sets {@code ended_at}); only that one kicks.
     * The kick happens after the commit and is best effort: if the media server is unreachable, the 방송 is still
     * 종료, and the auth callback already refuses any re-publish or new viewer.
     */
    public EndResult end(long id) {
        MemberRef member = currentMember.require();
        EndResult result = transaction.execute(tx -> repository.findById(id)
                .map(b -> {
                    requireOwner(b, member);
                    boolean endedNow = repository.endIfLive(id, now(), BroadcastStatus.LIVE, BroadcastStatus.ENDED) == 1;
                    return endedNow ? EndResult.ENDED : EndResult.ALREADY_ENDED;
                })
                .orElse(EndResult.NOT_FOUND));
        if (result == EndResult.ENDED) kickPublisher(id);
        return result;
    }

    /**
     * "이어서 송출": a fresh 송출 키 for the 소유자's 방송 중 방송 (401/403 otherwise, 409 once it has ended). Only the
     * new key's hash is stored, so the previous key stops working at once, and a publisher still connected with it
     * is cut (best effort, after the commit) so the new one takes over.
     *
     * @return the new key, or empty for an unknown 방송
     */
    public Optional<PublishKey> issuePublishKey(long id) {
        MemberRef member = currentMember.require();
        Optional<PublishKey> issued = transaction.execute(tx -> repository.findById(id).map(b -> {
            requireOwner(b, member);
            PublishKey publishKey = PublishKey.generate();
            if (repository.replacePublishKeyIfLive(id, publishKey.hash(), BroadcastStatus.LIVE) == 0) {
                throw new ConflictException(NOT_LIVE);
            }
            return publishKey;
        }));
        if (issued.isPresent()) kickPublisher(id);
        return issued;
    }

    /**
     * "제목 수정" by the 소유자 (401/403 otherwise), whether the 방송 is 방송 중 or 종료; the same title rule as
     * {@link #start}, checked only after login, existence and ownership (as for VOD).
     *
     * @return the renamed 방송, or empty for an unknown 방송
     */
    public Optional<Broadcast> rename(long id, String title) {
        MemberRef member = currentMember.require();
        return transaction.execute(tx -> repository.findById(id).map(b -> {
            requireOwner(b, member);
            b.rename(Title.normalize(title, message -> new InvalidBroadcastException("title", message)));
            return b;
        }));
    }

    /**
     * "삭제" by the 소유자 (401/403 otherwise) of a 종료 방송, with its 댓글 (by cascade). A 방송 중 방송 is refused
     * with a {@link ConflictException} until it ends; 종료 is final, so the check can't go stale before the delete.
     *
     * <p>Payment records must not disappear: a 방송 with any confirmed 후원 is refused with a {@link ConflictException}
     * ({@link #HAS_DONATIONS}). Orders that never became 후원 (PENDING, FAILED, EXPIRED) are deleted with the 방송 in the
     * same transaction. A 후원 confirmed meanwhile is caught by the {@code donation} FK ({@code ON DELETE RESTRICT}),
     * which rolls everything back and gives the same 409.
     *
     * @return {@code false} for an unknown 방송
     */
    public boolean delete(long id) {
        MemberRef member = currentMember.require();
        try {
            return transaction.execute(tx -> repository.findById(id).map(b -> {
                requireOwner(b, member);
                if (b.isLive()) throw new ConflictException(STILL_LIVE);
                if (repository.hasConfirmedDonations(id)) throw new ConflictException(HAS_DONATIONS);
                repository.deleteUnconfirmedDonations(id);
                repository.delete(b);
                repository.flush();
                return true;
            }).orElse(false));
        } catch (DataIntegrityViolationException confirmedMeanwhile) {
            throw new ConflictException(HAS_DONATIONS);
        }
    }

    private static void requireOwner(Broadcast broadcast, MemberRef member) {
        if (!broadcast.isOwnedBy(member)) throw new ForbiddenException(NOT_OWNER);
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
     * A 방송 that "방송 종료" ends at the same moment is ended (and kicked) by exactly one of the two.
     *
     * @return the ids of the 방송 it ended
     */
    public List<Long> sweep(Instant now) {
        return endSilent(now, false);
    }

    /**
     * The auto-end while the media server can't be asked who is publishing: silence can't be told from an outage
     * for a 방송 that has published, so only those that never sent any media (started more than 60s ago) end.
     *
     * @return the ids of the 방송 it ended
     */
    public List<Long> sweepNeverPublished(Instant now) {
        return endSilent(now, true);
    }

    private List<Long> endSilent(Instant now, boolean onlyNeverPublished) {
        Instant cutoff = now.minus(AUTO_END_AFTER);
        List<Long> ended = transaction.execute(tx -> repository
                .findLiveSilentSince(BroadcastStatus.LIVE, cutoff, onlyNeverPublished)
                .stream()
                .filter(id -> repository.endIfSilentSince(id, cutoff, BroadcastStatus.LIVE, BroadcastStatus.ENDED) == 1)
                .toList());
        ended.forEach(this::kickPublisher);
        return ended;
    }

    /** 방송 중 newest started first; 종료 most recently ended first. With an {@code ownerId}, only that 회원's. */
    @Transactional(readOnly = true)
    public Page<Broadcast> list(BroadcastStatus status, Long ownerId, int page, int size) {
        Sort order = switch (status) {
            case LIVE -> Sort.by(Sort.Order.desc("startedAt"), Sort.Order.desc("id"));
            case ENDED -> Sort.by(Sort.Order.desc("endedAt"), Sort.Order.desc("id"));
        };
        PageRequest request = PageRequest.of(page, size, order);
        return ownerId == null
                ? repository.findByStatus(status, request)
                : repository.findByStatusAndOwnerId(status, ownerId, request);
    }

    /** Publishing is allowed only to a 방송 중 방송, and only with its 송출 키. */
    @Transactional(readOnly = true)
    public boolean authorizePublish(long id, PublishKey publishKey) {
        return repository.findById(id)
                .filter(Broadcast::isLive)
                .filter(b -> publishKey.matches(b.getPublishKeyHash()))
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
