package com.chzzk.common.web;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;
import org.springframework.web.util.DisconnectedClientHelper;

/**
 * The one Problem Details (RFC 7807) shape for every API error, shared by all features.
 *
 * <p>Built on Spring's {@link ProblemDetail}: {@code type}, {@code title}, {@code status}, {@code detail},
 * {@code instance} (the request path), plus {@code errors: [{field, message}]} for field validation
 * (see {@link Problems#fieldError}). Extending {@link ResponseEntityExceptionHandler} keeps Spring's own exceptions
 * ({@code ResponseStatusException}, {@code ErrorResponseException}, bad request params, unknown routes, ...) in this
 * same shape, so controllers can simply throw {@code new ResponseStatusException(NOT_FOUND, "...")}.
 *
 * <p>Feature modules map their own domain exceptions in their own {@code @RestControllerAdvice}, which must be
 * ordered before this one ({@link #FEATURE_ORDER}): Spring picks the first advice with any matching handler, and
 * this one catches every {@link Exception}.
 */
@RestControllerAdvice
@Order(Ordered.LOWEST_PRECEDENCE)
public class ApiProblemHandler extends ResponseEntityExceptionHandler {

    /** The {@code @Order} for feature advices, so they are consulted before this catch-all. */
    public static final int FEATURE_ORDER = Ordered.HIGHEST_PRECEDENCE;

    /**
     * Anything else is a server fault, except a client that went away mid-request (a {@code <video>} seeking or a
     * closed tab during upload): that is routine, gets no ERROR log, and no body since nobody is listening.
     */
    @ExceptionHandler
    ProblemDetail unexpected(Exception e) {
        if (isClientDisconnect(e)) {
            logger.debug("Client disconnected: " + e);
            return null;
        }
        logger.error("Unhandled API error", e);
        return ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR,
                "요청을 처리하지 못했습니다. 잠시 후 다시 시도해 주세요.");
    }

    private static boolean isClientDisconnect(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (DisconnectedClientHelper.isClientDisconnectedException(t)) return true;
        }
        return false;
    }
}
