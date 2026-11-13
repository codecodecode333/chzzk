package com.chzzk.vod.storage;

import java.io.InputStream;
import org.springframework.core.io.Resource;

/**
 * Where 영상 파일 bytes live. Callers only ever hold a storage key (e.g. {@code videos/{uuid}.mp4}),
 * never an absolute path or URL, so an object-storage implementation can replace LOCAL later.
 */
public interface VideoStorage {

    /** Stores the stream under a newly generated key and returns where it went. */
    StoredVideo store(InputStream content);

    /** Opens the stored file; the resource reports its length via {@link Resource#contentLength()}. */
    Resource open(String storageKey);

    /** Deletes the stored file; a missing file is not an error. */
    void delete(String storageKey);

    /** Where a stored 영상 파일 lives ({@code storageType} + {@code storageKey}) and how big it is. */
    record StoredVideo(StorageType storageType, String storageKey, long sizeBytes) {}
}
