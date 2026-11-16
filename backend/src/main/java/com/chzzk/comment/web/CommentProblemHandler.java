package com.chzzk.comment.web;

import com.chzzk.comment.domain.CommentNotFoundException;
import com.chzzk.comment.domain.InvalidCommentException;
import com.chzzk.common.web.ApiProblemHandler;
import com.chzzk.common.web.Problems;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Maps 댓글's own failures to the shared Problem Details. */
@RestControllerAdvice
@Order(ApiProblemHandler.FEATURE_ORDER)
class CommentProblemHandler {

    @ExceptionHandler
    ProblemDetail invalidComment(InvalidCommentException e) {
        return Problems.fieldError(HttpStatus.BAD_REQUEST, "content", e.getMessage());
    }

    @ExceptionHandler
    ProblemDetail notFound(CommentNotFoundException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
    }
}
