package com.healthclaim.demo;

import com.healthclaim.common.exception.ApiException;
import org.springframework.http.HttpStatus;

/** A public demo account tried to change data that every visitor shares. */
public class DemoReadOnlyException extends ApiException {

    public DemoReadOnlyException() {
        super("This is a shared public demo, so policies, users, claims and facts are read-only. "
                + "You can still analyze, explain and review claims.");
    }

    @Override
    public HttpStatus status() {
        return HttpStatus.FORBIDDEN;
    }

    @Override
    public String errorCode() {
        return "DEMO_READ_ONLY";
    }
}
