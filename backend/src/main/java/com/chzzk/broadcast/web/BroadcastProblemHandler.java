package com.chzzk.broadcast.web;

import com.chzzk.broadcast.domain.BroadcastNotFoundException;
import com.chzzk.broadcast.domain.InvalidBroadcastException;
import com.chzzk.common.web.ApiProblemHandler;
import com.chzzk.common.web.Problems;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Maps 방송's own failures (bad input, unknown 방송) to the shared Problem Details. */
@RestControllerAdvice
@Order(ApiProblemHandler.FEATURE_ORDER)
class BroadcastProblemHandler {

    @ExceptionHandler
    ProblemDetail invalidBroadcast(InvalidBroadcastException e) {
        return Problems.fieldError(HttpStatus.BAD_REQUEST, e.field(), e.getMessage());
    }

    @ExceptionHandler
    ProblemDetail broadcastNotFound(BroadcastNotFoundException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
    }
}
