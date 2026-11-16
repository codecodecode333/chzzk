package com.chzzk.member.web;

import com.chzzk.common.web.ApiProblemHandler;
import com.chzzk.common.web.Problems;
import com.chzzk.member.domain.InvalidMemberException;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Maps the member module's own failures to the shared Problem Details (its 401 and 409 are the shared ones). */
@RestControllerAdvice
@Order(ApiProblemHandler.FEATURE_ORDER)
class MemberProblemHandler {

    @ExceptionHandler
    ProblemDetail invalidMember(InvalidMemberException e) {
        return Problems.fieldErrors(HttpStatus.BAD_REQUEST, e.errors());
    }
}
