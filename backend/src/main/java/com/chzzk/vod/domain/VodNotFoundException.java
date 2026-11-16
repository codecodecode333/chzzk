package com.chzzk.vod.domain;

/** No VOD with this id. Maps to {@code 404}. */
public class VodNotFoundException extends RuntimeException {

    public VodNotFoundException(long id) {
        super(id + "번 다시보기를 찾을 수 없습니다.");
    }
}
