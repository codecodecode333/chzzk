package com.chzzk.broadcast.domain;

/** No 방송 with this id. Maps to {@code 404}. */
public class BroadcastNotFoundException extends RuntimeException {

    public BroadcastNotFoundException(long id) {
        super(id + "번 방송을 찾을 수 없습니다.");
    }
}
