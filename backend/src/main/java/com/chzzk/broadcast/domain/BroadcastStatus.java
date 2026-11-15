package com.chzzk.broadcast.domain;

/** 방송 상태: 방송 중 or 종료. 종료 never returns to 방송 중. */
public enum BroadcastStatus {
    LIVE,
    ENDED
}
