package com.chzzk.vod.storage;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

/** LOCAL 영상 파일 storage rooted at {@code vod.storage.local.root}. */
@Component
public class LocalVideoStorage implements VideoStorage {

    private static final String PREFIX = "videos/";

    private final Path root;

    public LocalVideoStorage(@Value("${vod.storage.local.root}") Path root) {
        this.root = root.toAbsolutePath().normalize();
    }

    @Override
    public StoredVideo store(InputStream content) {
        String key = PREFIX + UUID.randomUUID() + ".mp4";
        Path target = resolve(key);
        try {
            Files.createDirectories(target.getParent());
            long size = Files.copy(content, target);
            return new StoredVideo(StorageType.LOCAL, key, size);
        } catch (IOException e) {
            deleteQuietly(target);
            throw new UncheckedIOException("Failed to store video file", e);
        }
    }

    @Override
    public Resource open(String storageKey) {
        return new FileSystemResource(resolve(storageKey));
    }

    @Override
    public void delete(String storageKey) {
        try {
            Files.deleteIfExists(resolve(storageKey));
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to delete video file", e);
        }
    }

    private Path resolve(String storageKey) {
        Path path = root.resolve(storageKey).normalize();
        if (!path.startsWith(root)) {
            throw new IllegalArgumentException("Storage key escapes storage root: " + storageKey);
        }
        return path;
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // best effort cleanup of a partial write
        }
    }
}
