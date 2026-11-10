package com.healthclaim.review.exception;

import com.healthclaim.common.exception.ApiException;
import org.springframework.http.HttpStatus;

import java.util.UUID;

public class ReviewNotFoundException extends ApiException {

    public ReviewNotFoundException(UUID reviewId) {
        super("Review not found: " + reviewId);
    }

    @Override
    public HttpStatus status() {
        return HttpStatus.NOT_FOUND;
    }

    @Override
    public String errorCode() {
        return "REVIEW_NOT_FOUND";
    }
}
