package com.chzzk.vod.web;

import com.chzzk.common.web.ApiProblemHandler;
import com.chzzk.common.web.Problems;
import com.chzzk.vod.domain.InvalidVodException;
import com.chzzk.vod.domain.UnsupportedVideoException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.util.unit.DataSize;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

/** Maps VOD's own failures (bad registration input, non-MP4 files, oversize uploads) to the shared Problem Details. */
@RestControllerAdvice
@Order(ApiProblemHandler.FEATURE_ORDER)
class VodProblemHandler {

    private final DataSize maxFileSize;

    VodProblemHandler(@Value("${vod.upload.max-file-size}") DataSize maxFileSize) {
        this.maxFileSize = maxFileSize;
    }

    /** Raised while parsing the multipart body, before any controller runs (see {@link UploadLimits}). */
    @ExceptionHandler
    ProblemDetail uploadTooLarge(MaxUploadSizeExceededException e) {
        return ProblemDetail.forStatusAndDetail(e.getStatusCode(),
                "영상 파일은 " + humanReadable(maxFileSize) + " 이하만 등록할 수 있습니다.");
    }

    @ExceptionHandler
    ProblemDetail invalidVod(InvalidVodException e) {
        return Problems.fieldError(HttpStatus.BAD_REQUEST, e.field(), e.getMessage());
    }

    @ExceptionHandler
    ProblemDetail unsupportedVideo(UnsupportedVideoException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.UNSUPPORTED_MEDIA_TYPE, e.getMessage());
    }

    private static String humanReadable(DataSize size) {
        if (size.toBytes() % DataSize.ofGigabytes(1).toBytes() == 0) return size.toGigabytes() + "GB";
        if (size.toBytes() % DataSize.ofMegabytes(1).toBytes() == 0) return size.toMegabytes() + "MB";
        if (size.toBytes() % DataSize.ofKilobytes(1).toBytes() == 0) return size.toKilobytes() + "KB";
        return size.toBytes() + "B";
    }
}
