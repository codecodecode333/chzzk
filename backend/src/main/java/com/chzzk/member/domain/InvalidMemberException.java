package com.chzzk.member.domain;

import com.chzzk.common.web.Problems.FieldError;
import java.util.List;

/** Signup input broke one or more rules, each naming its form field. Maps to {@code 400}. */
public class InvalidMemberException extends RuntimeException {

    private final List<FieldError> errors;

    InvalidMemberException(List<FieldError> errors) {
        super(errors.getFirst().message());
        this.errors = List.copyOf(errors);
    }

    public List<FieldError> errors() {
        return errors;
    }
}
