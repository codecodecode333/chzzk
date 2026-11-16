package com.chzzk.common.web;

import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;

/** Builders for the shared Problem Details shape (see {@link ApiProblemHandler}). */
public final class Problems {

    private Problems() {
    }

    /** A problem caused by one form field: {@code detail} plus {@code errors: [{field, message}]}. */
    public static ProblemDetail fieldError(HttpStatusCode status, String field, String message) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, message);
        problem.setProperty("errors", List.of(Map.of("field", field, "message", message)));
        return problem;
    }

    /** One form field and what is wrong with it. */
    public record FieldError(String field, String message) {}

    /**
     * A problem caused by several form fields at once: {@code errors} lists each, {@code detail} is the first
     * message. {@code errors} must not be empty.
     */
    public static ProblemDetail fieldErrors(HttpStatusCode status, List<FieldError> errors) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, errors.getFirst().message());
        problem.setProperty("errors", errors.stream()
                .map(e -> Map.of("field", e.field(), "message", e.message()))
                .toList());
        return problem;
    }
}
