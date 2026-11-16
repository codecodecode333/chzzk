package com.chzzk.comment.domain;

/** No such 댓글, or no such VOD/방송 to comment on. Maps to {@code 404}. */
public class CommentNotFoundException extends RuntimeException {

    public CommentNotFoundException(String message) {
        super(message);
    }
}
