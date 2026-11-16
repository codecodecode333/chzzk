package com.chzzk.comment.domain;

import java.util.List;

/**
 * One page of 댓글, newest first. Paging older: {@code nextBefore} is the cursor for the next older page — pass it as
 * {@code before} — or {@code null} when there are no older 댓글. Paging newer (polling, {@code after}):
 * {@code nextAfter} is the cursor for the next newer page — pass it as {@code after} — or {@code null} when this page
 * reaches the newest 댓글.
 */
public record CommentPage(List<CommentView> content, Long nextBefore, Long nextAfter) {}
