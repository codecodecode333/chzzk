package com.chzzk.donation.web;

import com.chzzk.common.web.ApiProblemHandler;
import com.chzzk.common.web.Problems;
import com.chzzk.donation.domain.DonationNotFoundException;
import com.chzzk.donation.domain.InvalidDonationException;
import com.chzzk.donation.domain.PaymentNotConfirmedException;
import com.chzzk.donation.payment.PaymentUnavailableException;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Maps 후원's own failures to the shared Problem Details. */
@RestControllerAdvice
@Order(ApiProblemHandler.FEATURE_ORDER)
class DonationProblemHandler {

    @ExceptionHandler
    ProblemDetail invalid(InvalidDonationException e) {
        return Problems.fieldError(HttpStatus.BAD_REQUEST, e.field(), e.getMessage());
    }

    @ExceptionHandler
    ProblemDetail notFound(DonationNotFoundException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
    }

    /** {@code code}: the provider's error code, or ours ({@code AMOUNT_MISMATCH}, ...). */
    @ExceptionHandler
    ProblemDetail notConfirmed(PaymentNotConfirmedException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
        problem.setProperty("code", e.code());
        return problem;
    }

    @ExceptionHandler
    ProblemDetail unavailable(PaymentUnavailableException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, e.getMessage());
    }
}
