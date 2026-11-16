package com.chzzk.vod.domain;

import com.chzzk.vod.domain.VodService.VideoFileMetadata;
import com.chzzk.vod.storage.StorageType;
import com.chzzk.vod.storage.VideoStorage.StoredVideo;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * A registered VOD and its single 영상 파일 (1:1, so the file columns live on this row), owned by the 회원 who
 * registered it. Only the title and description change after registration.
 */
@Entity
@Table(name = "vod")
public class Vod {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String title;

    private String description;

    @Enumerated(EnumType.STRING)
    @Column(name = "storage_type", nullable = false)
    private StorageType storageType;

    @Column(name = "storage_key", nullable = false, unique = true)
    private String storageKey;

    @Column(name = "original_filename", nullable = false)
    private String originalFilename;

    @Column(name = "content_type", nullable = false)
    private String contentType;

    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "owner_id", nullable = false, updatable = false)
    private VodOwner owner;

    protected Vod() {}

    Vod(VodOwner owner, String title, String description, StoredVideo video, VideoFileMetadata metadata,
        Instant createdAt) {
        this.owner = owner;
        this.title = title;
        this.description = description;
        this.storageType = video.storageType();
        this.storageKey = video.storageKey();
        this.sizeBytes = video.sizeBytes();
        this.originalFilename = metadata.originalFilename();
        this.contentType = metadata.contentType();
        this.createdAt = createdAt;
    }

    public Long getId() { return id; }
    public String getTitle() { return title; }
    public String getDescription() { return description; }
    public StorageType getStorageType() { return storageType; }
    public String getStorageKey() { return storageKey; }
    public String getOriginalFilename() { return originalFilename; }
    public String getContentType() { return contentType; }
    public long getSizeBytes() { return sizeBytes; }
    public Instant getCreatedAt() { return createdAt; }
    public VodOwner getOwner() { return owner; }

    void changeTitle(String title) { this.title = title; }
    void changeDescription(String description) { this.description = description; }
}
