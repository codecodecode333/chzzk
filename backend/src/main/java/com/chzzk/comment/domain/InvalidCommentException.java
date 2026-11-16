package com.chzzk.comment.domain;

/** The 댓글 text broke the content rule; the field is always {@code content}. Maps to {@code 400}. */
public class InvalidCommentException extends RuntimeException {

    public InvalidCommentException(String message) {
        super(message);
    }
}
