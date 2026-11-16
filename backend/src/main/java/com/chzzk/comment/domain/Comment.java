package com.chzzk.comment.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/** A 댓글: a 회원's (the 작성자's) short text on exactly one VOD or 방송, removed with it (DB cascade). */
@Entity
@Table(name = "comment")
public class Comment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "vod_id")
    private Long vodId;

    @Column(name = "broadcast_id")
    private Long broadcastId;

    @Column(name = "author_id", nullable = false)
    private long authorId;

    @Column(nullable = false, length = 500)
    private String content;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /** Set on edit; a 댓글 is "(수정됨)" exactly when this is set. */
    @Column(name = "updated_at")
    private Instant updatedAt;

    protected Comment() {}

    Comment(CommentTarget target, long authorId, String content, Instant createdAt) {
        switch (target) {
            case CommentTarget.Vod vod -> this.vodId = vod.id();
            case CommentTarget.Broadcast broadcast -> this.broadcastId = broadcast.id();
        }
        this.authorId = authorId;
        this.content = content;
        this.createdAt = createdAt;
    }

    void edit(String content, Instant at) {
        this.content = content;
        this.updatedAt = at;
    }

    public Long getId() { return id; }
    public long getAuthorId() { return authorId; }
    public String getContent() { return content; }
    public Instant getCreatedAt() { return createdAt; }
    public boolean isEdited() { return updatedAt != null; }
}
