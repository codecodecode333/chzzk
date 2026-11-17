package com.chzzk.donation.domain;

import java.util.List;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The read side of 후원: a 방송's 내역 and 합계, public, counting {@link DonationStatus#CONFIRMED} 후원 only so
 * pending, failed or expired orders never appear or count. Ordered by id, like 댓글.
 */
@Service
public class DonationHistory {

    private final DonationRepository repository;

    DonationHistory(DonationRepository repository) {
        this.repository = repository;
    }

    /**
     * The 방송's confirmed 후원 newest first, {@code size} at a time, with the 합계. Unknown 방송: {@code 404}.
     *
     * @param before only 후원 older than this id (the previous page's {@code nextBefore}); {@code null} for the newest
     */
    @Transactional(readOnly = true)
    public DonationHistoryPage list(long broadcastId, Long before, int size) {
        requireBroadcast(broadcastId);
        long cursor = before == null ? Long.MAX_VALUE : before;
        List<DonationEntry> found = repository.findConfirmed(broadcastId, cursor, Limit.of(size + 1));
        long total = repository.totalConfirmed(broadcastId);
        if (found.size() <= size) return new DonationHistoryPage(found, null, null, total);
        List<DonationEntry> page = List.copyOf(found.subList(0, size));
        return new DonationHistoryPage(page, page.getLast().id(), null, total);
    }

    /**
     * The {@code size} confirmed 후원 right after {@code after} (the oldest of those newer than it), still newest first,
     * with the 합계, so a poller that keeps passing {@code nextAfter} gets every newer 후원 exactly once.
     * Unknown 방송: {@code 404}.
     *
     * @param after the newest id the caller has read (0 for none)
     */
    @Transactional(readOnly = true)
    public DonationHistoryPage listAfter(long broadcastId, long after, int size) {
        requireBroadcast(broadcastId);
        List<DonationEntry> found = repository.findConfirmedAfter(broadcastId, after, Limit.of(size + 1));
        List<DonationEntry> page = found.subList(0, Math.min(size, found.size())).reversed();
        return new DonationHistoryPage(List.copyOf(page), null, found.size() > size ? page.getFirst().id() : null,
                repository.totalConfirmed(broadcastId));
    }

    private void requireBroadcast(long broadcastId) {
        if (repository.findBroadcast(broadcastId).isEmpty()) {
            throw new DonationNotFoundException(broadcastId + "번 방송을 찾을 수 없습니다.");
        }
    }
}
