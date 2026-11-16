package com.chzzk.comment.domain;

import java.util.List;

/**
 * One page of 댓글, newest first. {@code nextBefore} is the cursor for the next (older) page — pass it as
 * {@code before} — or {@code null} when there are no older 댓글.
 */
public record CommentPage(List<CommentView> content, Long nextBefore) {}
