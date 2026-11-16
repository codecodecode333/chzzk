package com.chzzk.vod.domain;

import com.chzzk.common.auth.CurrentMember;
import com.chzzk.common.auth.MemberRef;
import com.chzzk.common.error.ForbiddenException;
import com.chzzk.common.error.UnauthenticatedException;
import com.chzzk.common.text.Text;
import com.chzzk.common.text.Title;
import com.chzzk.vod.storage.VideoStorage;
import com.chzzk.vod.storage.VideoStorage.StoredVideo;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * VOD registration, query, edit and delete. Owns the registration rules, and saves the 영상 파일 first, then inserts
 * the row, deleting the file again if the insert fails so that every listed VOD is playable.
 *
 * <p>Registering needs a logged-in 회원, who becomes the VOD's 소유자; only the 소유자 may edit or delete it. These
 * checks live here, next to the data, using {@link CurrentMember}: a visitor gets {@link UnauthenticatedException}
 * (401) and another 회원 {@link ForbiddenException} (403).
 */
@Service
public class VodService {

    private static final Logger log = LoggerFactory.getLogger(VodService.class);

    static final String NOT_YOUR_VOD = "내 다시보기만 수정하거나 삭제할 수 있습니다.";

    private static final Sort NEWEST_FIRST = Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"));

    private static final int DESCRIPTION_MAX_CHARS = 2_000;
    private static final int FTYP_SNIFF_BYTES = 8;

    private final VodRepository repository;
    private final VodOwnerRepository owners;
    private final VideoStorage storage;
    private final CurrentMember currentMember;

    VodService(VodRepository repository, VodOwnerRepository owners, VideoStorage storage, CurrentMember currentMember) {
        this.repository = repository;
        this.owners = owners;
        this.storage = storage;
        this.currentMember = currentMember;
    }

    /**
     * Registers a VOD owned by the logged-in 회원.
     *
     * @param file the 영상 파일 bytes, or {@code null} when no (or an empty) file was sent
     */
    public Vod register(String title, String description, InputStream file, VideoFileMetadata metadata) {
        MemberRef me = currentMember.require();
        title = normalizeTitle(title);
        description = normalizeDescription(description);
        if (file == null) {
            throw new InvalidVodException("file", "영상 파일을 선택해 주세요.");
        }
        String filename = metadata.originalFilename() == null ? "" : metadata.originalFilename();
        if (!filename.toLowerCase(Locale.ROOT).endsWith(".mp4")) {
            throw new UnsupportedVideoException("MP4(.mp4) 파일만 등록할 수 있습니다.");
        }
        InputStream content = new BufferedInputStream(file);
        if (!startsWithFtypBox(content)) {
            throw new UnsupportedVideoException("MP4 형식의 영상 파일이 아닙니다.");
        }

        VodOwner owner = owners.findById(me.id()).orElseThrow(UnauthenticatedException::new);
        StoredVideo stored = storage.store(content);
        Vod vod = new Vod(owner, title, description, stored, metadata,
                Instant.now().truncatedTo(ChronoUnit.MICROS)); // match TIMESTAMPTZ precision
        try {
            return repository.save(vod);
        } catch (RuntimeException insertFailure) {
            // Compensation: a 영상 파일 without a row would be an orphan, so remove it.
            try {
                storage.delete(stored.storageKey());
            } catch (RuntimeException deleteFailure) {
                insertFailure.addSuppressed(deleteFailure);
            }
            throw insertFailure;
        }
    }

    /**
     * The 소유자 changes the title and/or description; {@code null} leaves that one unchanged, and a blank description
     * removes it. Same rules as {@link #register}.
     */
    @Transactional
    public Vod update(long id, String title, String description) {
        Vod vod = ownedByCurrentMember(id);
        if (title != null) vod.changeTitle(normalizeTitle(title));
        if (description != null) vod.changeDescription(normalizeDescription(description));
        return vod;
    }

    /**
     * The 소유자 deletes the VOD: the row goes first (its 댓글 with it, by cascade), then the 영상 파일 best-effort.
     * A file that can't be removed is logged and left behind; the VOD is gone either way.
     */
    public void delete(long id) {
        Vod vod = ownedByCurrentMember(id);
        repository.delete(vod);
        try {
            storage.delete(vod.getStorageKey());
        } catch (RuntimeException e) {
            log.warn("Failed to delete the video file of deleted VOD {} ({})", id, vod.getStorageKey(), e);
        }
    }

    private Vod ownedByCurrentMember(long id) {
        MemberRef me = currentMember.require();
        Vod vod = repository.findById(id).orElseThrow(() -> new VodNotFoundException(id));
        if (!vod.getOwner().is(me)) throw new ForbiddenException(NOT_YOUR_VOD);
        return vod;
    }

    private static String normalizeTitle(String title) {
        return Title.normalize(title, message -> new InvalidVodException("title", message));
    }

    /** A blank description is no description ({@code null}). */
    private static String normalizeDescription(String description) {
        if (description == null || Text.trimWhitespace(description).isEmpty()) return null;
        if (Text.charCount(description) > DESCRIPTION_MAX_CHARS) {
            throw new InvalidVodException("description",
                    String.format(Locale.ROOT, "설명은 %,d자 이하로 입력해 주세요.", DESCRIPTION_MAX_CHARS));
        }
        return description;
    }

    public Page<Vod> list(int page, int size) {
        return repository.findAll(PageRequest.of(page, size, NEWEST_FIRST));
    }

    /** An MP4 starts with a box whose type, at bytes 4..7, is {@code ftyp}. Leaves the stream unconsumed. */
    private static boolean startsWithFtypBox(InputStream content) {
        try {
            content.mark(FTYP_SNIFF_BYTES);
            byte[] head = content.readNBytes(FTYP_SNIFF_BYTES);
            content.reset();
            return head.length == FTYP_SNIFF_BYTES
                    && head[4] == 'f' && head[5] == 't' && head[6] == 'y' && head[7] == 'p';
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read video file", e);
        }
    }

    public Optional<Vod> find(long id) {
        return repository.findById(id);
    }

    /** The VOD's 영상 파일, or empty when the VOD is unknown or its stored file is missing. */
    public Optional<Resource> openVideo(long id) {
        return repository.findById(id)
                .map(vod -> storage.open(vod.getStorageKey()))
                .filter(Resource::exists);
    }

    public record VideoFileMetadata(String originalFilename, String contentType) {}
}
