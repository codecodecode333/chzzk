package com.chzzk.vod.web;

import com.chzzk.vod.vod.InvalidVodException;
import com.chzzk.vod.vod.UnsupportedVideoException;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.util.unit.DataSize;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * The one Problem Details (RFC 7807) shape for every API error.
 *
 * <p>Built on Spring's {@link ProblemDetail}: {@code type}, {@code title}, {@code status}, {@code detail},
 * {@code instance} (the request path), plus {@code errors: [{field, message}]} for field validation.
 * Extending {@link ResponseEntityExceptionHandler} keeps Spring's own exceptions ({@code ResponseStatusException},
 * {@code ErrorResponseException}, bad request params, unknown routes, oversize multipart, ...) in this same shape,
 * so controllers can simply throw {@code new ResponseStatusException(NOT_FOUND, "...")}.
 */
@RestControllerAdvice
class ApiProblemHandler extends ResponseEntityExceptionHandler {

    private final DataSize maxFileSize;

    ApiProblemHandler(@Value("${vod.upload.max-file-size}") DataSize maxFileSize) {
        this.maxFileSize = maxFileSize;
    }

    @Override
    protected ResponseEntity<Object> handleMaxUploadSizeExceededException(
            MaxUploadSizeExceededException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status,
                "영상 파일은 " + humanReadable(maxFileSize) + " 이하만 등록할 수 있습니다.");
        return handleExceptionInternal(ex, problem, headers, status, request);
    }

    @ExceptionHandler
    ProblemDetail invalidVod(InvalidVodException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
        problem.setProperty("errors", List.of(Map.of("field", e.field(), "message", e.getMessage())));
        return problem;
    }

    @ExceptionHandler
    ProblemDetail unsupportedVideo(UnsupportedVideoException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.UNSUPPORTED_MEDIA_TYPE, e.getMessage());
    }

    @ExceptionHandler
    ProblemDetail unexpected(Exception e) {
        logger.error("Unhandled API error", e);
        return ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR,
                "요청을 처리하지 못했습니다. 잠시 후 다시 시도해 주세요.");
    }

    private static String humanReadable(DataSize size) {
        if (size.toBytes() % DataSize.ofGigabytes(1).toBytes() == 0) return size.toGigabytes() + "GB";
        if (size.toBytes() % DataSize.ofMegabytes(1).toBytes() == 0) return size.toMegabytes() + "MB";
        if (size.toBytes() % DataSize.ofKilobytes(1).toBytes() == 0) return size.toKilobytes() + "KB";
        return size.toBytes() + "B";
    }
}
