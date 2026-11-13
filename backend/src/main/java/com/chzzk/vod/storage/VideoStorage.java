package com.chzzk.vod.storage;

import java.io.InputStream;
import org.springframework.core.io.Resource;

/**
 * Where 영상 파일 bytes live. Callers only ever hold a storage key (e.g. {@code videos/{uuid}.mp4}),
 * never an absolute path or URL, so an object-storage implementation can replace LOCAL later.
 */
public interface VideoStorage {

    /** The {@code storage_type} recorded alongside keys produced by this implementation. */
    StorageType type();

    /** Stores the stream under a newly generated key and returns that key. */
    StoredVideo store(InputStream content);

    /** Opens the stored file; the resource reports its length via {@link Resource#contentLength()}. */
    Resource open(String storageKey);

    /** Deletes the stored file; a missing file is not an error. */
    void delete(String storageKey);

    record StoredVideo(String storageKey, long sizeBytes) {}
}
