package com.chzzk.vod.vod;

import com.chzzk.vod.storage.VideoStorage;
import com.chzzk.vod.storage.VideoStorage.StoredVideo;
import java.io.InputStream;
import java.time.Instant;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

/** VOD registration and query. Saves the 영상 파일 first, then inserts the row. */
@Service
public class VodService {

    private static final Sort NEWEST_FIRST = Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"));

    private final VodRepository repository;
    private final VideoStorage storage;

    VodService(VodRepository repository, VideoStorage storage) {
        this.repository = repository;
        this.storage = storage;
    }

    public Vod register(String title, String description, InputStream file, VideoFileMetadata metadata) {
        StoredVideo stored = storage.store(file);
        Vod vod = new Vod(title, description, storage.type(), stored.storageKey(),
                metadata.originalFilename(), metadata.contentType(), stored.sizeBytes(), Instant.now());
        return repository.save(vod);
    }

    public Page<Vod> list(int page, int size) {
        return repository.findAll(PageRequest.of(page, size, NEWEST_FIRST));
    }

    public record VideoFileMetadata(String originalFilename, String contentType) {}
}
