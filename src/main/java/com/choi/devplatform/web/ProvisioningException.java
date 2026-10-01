package com.choi.devplatform.web;

import org.springframework.http.HttpStatus;

public class ProvisioningException extends RuntimeException {
    private final HttpStatus status;

    public ProvisioningException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }

    public HttpStatus status() { return status; }
}
