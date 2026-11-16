package com.chzzk.comment.domain;

import com.chzzk.common.auth.MemberRef;
import java.time.Instant;

/** A 댓글 as readers see it: with its 작성자's id and 닉네임. */
public record CommentView(long id, String content, MemberRef author, Instant createdAt, boolean edited) {

    CommentView(Comment comment, String authorNickname) {
        this(comment.getId(), comment.getContent(), new MemberRef(comment.getAuthorId(), authorNickname),
                comment.getCreatedAt(), comment.isEdited());
    }
}
