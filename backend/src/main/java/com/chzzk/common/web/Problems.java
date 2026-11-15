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
}
