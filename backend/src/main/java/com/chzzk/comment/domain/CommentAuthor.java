package com.chzzk.comment.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Immutable;

/**
 * A 작성자 as the comments module reads one: a read-only view of the {@code member} row (id and 닉네임), so this
 * module joins 닉네임 without depending on the member module.
 */
@Entity(name = "CommentAuthor")
@Table(name = "member")
@Immutable
class CommentAuthor {

    @Id
    private Long id;

    @Column(nullable = false, length = 20)
    private String nickname;

    protected CommentAuthor() {}
}
