package com.chzzk.common.text;

/** Text rules shared by every feature's input validation (VOD and 방송 titles), matching the frontend's. */
public final class Text {

    private Text() {
    }

    /**
     * Trims whitespace exactly as JavaScript's {@code String.prototype.trim()} does, which the forms validate with,
     * so a title a form rejects (e.g. only NBSPs) is rejected here too. Java's {@code strip()} differs: it keeps
     * NBSP/U+FEFF and removes U+001C..U+001F.
     */
    public static String trimWhitespace(String s) {
        int start = 0;
        int end = s.length();
        while (start < end && isJsWhitespace(s.charAt(start))) start++;
        while (end > start && isJsWhitespace(s.charAt(end - 1))) end--;
        return s.substring(start, end);
    }

    /** ECMAScript WhiteSpace and LineTerminator code points (all in the BMP). */
    private static boolean isJsWhitespace(char c) {
        return switch (c) {
            case '\t', '\n', '\u000B', '\f', '\r', ' ', ' ', ' ', ' ', '﻿' -> true;
            default -> Character.getType(c) == Character.SPACE_SEPARATOR;
        };
    }

    /** Characters as PostgreSQL VARCHAR counts them (code points), so an emoji is one char. */
    public static int charCount(String s) {
        return s.codePointCount(0, s.length());
    }
}
