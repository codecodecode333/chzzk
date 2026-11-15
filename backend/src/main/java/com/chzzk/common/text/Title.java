package com.chzzk.common.text;

import java.util.function.Function;

/**
 * The title rule shared by every feature (VOD and 방송), mirrored by the frontend's {@code forms/title.ts}: trimmed
 * like JavaScript's {@code trim()}, then 1–100 characters (code points).
 */
public final class Title {

    public static final int MAX_CHARS = 100;

    private Title() {
    }

    /**
     * The trimmed title.
     *
     * @param invalid turns the user-facing message into the caller's own exception for an invalid title
     */
    public static <E extends RuntimeException> String normalize(String title, Function<String, E> invalid) {
        String trimmed = title == null ? "" : Text.trimWhitespace(title);
        if (trimmed.isEmpty()) throw invalid.apply("제목을 입력해 주세요.");
        if (Text.charCount(trimmed) > MAX_CHARS) throw invalid.apply("제목은 " + MAX_CHARS + "자 이하로 입력해 주세요.");
        return trimmed;
    }
}
