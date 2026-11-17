package com.chzzk.donation.domain;

import java.util.List;

/**
 * One page of a 방송's confirmed 후원, newest first, with its 후원 합계. The cursors work like 댓글's: {@code nextBefore}
 * is the {@code before} for the next older page ({@code null}: none older), {@code nextAfter} the {@code after} for
 * the next newer page ({@code null}: this page reaches the newest).
 */
public record DonationHistoryPage(List<DonationEntry> content, Long nextBefore, Long nextAfter, long total) {}
