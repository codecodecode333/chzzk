package com.chzzk.common.web;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** The shared paging rules for list APIs: a default size, a cap, and {@code 400} for a bad page or size. */
public final class Paging {

    public static final int DEFAULT_PAGE_SIZE = 20;
    public static final int MAX_PAGE_SIZE = 100;

    private Paging() {
    }

    /** A validated page index and a size capped at {@link #MAX_PAGE_SIZE}. */
    public record PageParams(int page, int size) {}

    public static PageParams of(int page, int size) {
        if (page < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "page는 0 이상이어야 합니다.");
        }
        if (size < 1) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "size는 1 이상이어야 합니다.");
        }
        return new PageParams(page, Math.min(size, MAX_PAGE_SIZE));
    }
}
