package com.chzzk.vod.domain;

import com.chzzk.common.text.Text;
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
import org.springframework.core.io.Resource;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

/**
 * VOD registration and query. Owns the registration rules, and saves the 영상 파일 first, then inserts the row,
 * deleting the file again if the insert fails so that every listed VOD is playable.
 */
@Service
public class VodService {

    private static final Sort NEWEST_FIRST = Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"));

    private static final int TITLE_MAX_CHARS = 100;
    private static final int DESCRIPTION_MAX_CHARS = 2_000;
    private static final int FTYP_SNIFF_BYTES = 8;

    private final VodRepository repository;
    private final VideoStorage storage;

    VodService(VodRepository repository, VideoStorage storage) {
        this.repository = repository;
        this.storage = storage;
    }

    /** @param file the 영상 파일 bytes, or {@code null} when no (or an empty) file was sent */
    public Vod register(String title, String description, InputStream file, VideoFileMetadata metadata) {
        title = title == null ? "" : Text.trimWhitespace(title);
        if (title.isEmpty()) {
            throw new InvalidVodException("title", "제목을 입력해 주세요.");
        }
        if (Text.charCount(title) > TITLE_MAX_CHARS) {
            throw new InvalidVodException("title", "제목은 " + TITLE_MAX_CHARS + "자 이하로 입력해 주세요.");
        }
        if (description != null && Text.trimWhitespace(description).isEmpty()) {
            description = null; // a blank description is no description
        }
        if (description != null && Text.charCount(description) > DESCRIPTION_MAX_CHARS) {
            throw new InvalidVodException("description", String.format(Locale.ROOT, "설명은 %,d자 이하로 입력해 주세요.", DESCRIPTION_MAX_CHARS));
        }
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

        StoredVideo stored = storage.store(content);
        Vod vod = new Vod(title, description, stored, metadata,
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
