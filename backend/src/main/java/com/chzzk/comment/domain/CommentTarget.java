package com.chzzk.comment.domain;

/**
 * What a 댓글 is on: exactly one VOD or one 방송. The comments module, its API and the frontend's comment section are
 * the same for both; only the target differs.
 */
public sealed interface CommentTarget {

    long id();

    static CommentTarget vod(long id) {
        return new Vod(id);
    }

    static CommentTarget broadcast(long id) {
        return new Broadcast(id);
    }

    record Vod(long id) implements CommentTarget {}

    record Broadcast(long id) implements CommentTarget {}
}
