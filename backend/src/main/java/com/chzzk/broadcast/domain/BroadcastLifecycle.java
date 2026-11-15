package com.chzzk.broadcast.domain;

import com.chzzk.common.text.Text;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The broadcast lifecycle: the single owner of 방송 status rules, the 송출 키 and who may publish or read.
 *
 * <p>State lives only in the database, so it survives restarts. Time comes from the injected {@link Clock}.
 * Later additions belong here too: {@code list(status, page)}, {@code end(id, publishKey)} (then kick the publisher via
 * the media server gateway), and {@code sweep(now)} for the 60-second auto-end.
 */
@Service
public class BroadcastLifecycle {

    static final int TITLE_MAX_CHARS = 100;

    private final BroadcastRepository repository;
    private final Clock clock;

    BroadcastLifecycle(BroadcastRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
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

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS); // match TIMESTAMPTZ precision
    }
}
