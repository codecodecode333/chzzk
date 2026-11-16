package com.chzzk.common.web;

import com.chzzk.common.auth.CurrentMember;
import com.chzzk.common.error.ConflictException;
import com.chzzk.common.error.ForbiddenException;
import com.chzzk.common.error.UnauthenticatedException;
import org.springframework.core.Ordered;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.csrf.CsrfException;
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
 *
 * <p>The shared access errors live here too, for every feature: {@code 401} ({@link UnauthenticatedException}, and
 * Spring Security's own), {@code 403} ({@link ForbiddenException}, a rejected CSRF token) and {@code 409}
 * ({@link ConflictException}). Spring Security's filters reach these handlers through
 * {@code common.auth.SecurityConfig}, so a filter-level 401/403 has the same shape.
 */
@RestControllerAdvice
@Order(Ordered.LOWEST_PRECEDENCE)
public class ApiProblemHandler extends ResponseEntityExceptionHandler {

    /** The {@code @Order} for feature advices, so they are consulted before this catch-all. */
    public static final int FEATURE_ORDER = Ordered.HIGHEST_PRECEDENCE;

    /** Not logged in, from a feature ({@link CurrentMember#require()}) or Spring Security's entry point. */
    @ExceptionHandler({UnauthenticatedException.class, AuthenticationException.class})
    ProblemDetail unauthenticated(Exception e) {
        String message = e instanceof UnauthenticatedException ? e.getMessage() : UnauthenticatedException.LOGIN_REQUIRED;
        return ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, message);
    }

    /** Logged in but not the 소유자/작성자. */
    @ExceptionHandler
    ProblemDetail forbidden(ForbiddenException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, e.getMessage());
    }

    /** A write without a valid CSRF token (missing, stale after logout, or forged by another site). */
    @ExceptionHandler
    ProblemDetail csrfRejected(CsrfException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, CSRF_REJECTED);
    }

    /** Spring Security denied the request (its access rules). */
    @ExceptionHandler
    ProblemDetail accessDenied(AccessDeniedException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, ForbiddenException.NOT_ALLOWED);
    }

    @ExceptionHandler
    ProblemDetail conflict(ConflictException e) {
        ProblemDetail problem = e.field() == null
                ? ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage())
                : Problems.fieldError(HttpStatus.CONFLICT, e.field(), e.getMessage());
        e.properties().forEach(problem::setProperty);
        return problem;
    }

    static final String CSRF_REJECTED = "요청을 확인하지 못했습니다. 페이지를 새로고침한 뒤 다시 시도해 주세요.";

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
