package com.chzzk.vod.vod;

/** The uploaded file is not an MP4 (wrong extension or no {@code ftyp} signature). Maps to {@code 415}. */
public class UnsupportedVideoException extends RuntimeException {

    public UnsupportedVideoException(String message) {
        super(message);
    }
}
