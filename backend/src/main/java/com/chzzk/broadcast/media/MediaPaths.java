package com.chzzk.broadcast.media;

import java.util.OptionalLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** The one mapping between a 방송 and its media server path, {@code broadcasts/{id}} (no leading slash). */
public final class MediaPaths {

    private static final Pattern PATH = Pattern.compile("broadcasts/([1-9][0-9]{0,18})");

    private MediaPaths() {
    }

    public static String of(long broadcastId) {
        return "broadcasts/" + broadcastId;
    }

    /** The 방송 id a media path belongs to, or empty for any other path. */
    public static OptionalLong broadcastIdOf(String path) {
        if (path == null) return OptionalLong.empty();
        Matcher m = PATH.matcher(path);
        if (!m.matches()) return OptionalLong.empty();
        try {
            return OptionalLong.of(Long.parseLong(m.group(1)));
        } catch (NumberFormatException overflow) {
            return OptionalLong.empty();
        }
    }
}
