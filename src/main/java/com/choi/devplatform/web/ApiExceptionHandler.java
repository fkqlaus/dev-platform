package com.choi.devplatform.web;

import org.springframework.http.*;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.*;

@RestControllerAdvice
public class ApiExceptionHandler {
    @ExceptionHandler(ProvisioningException.class)
    ResponseEntity<ProblemDetail> provisioning(ProvisioningException e) {
        return ResponseEntity.status(e.status()).body(ProblemDetail.forStatusAndDetail(e.status(), e.getMessage()));
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class})
    ResponseEntity<ProblemDetail> invalid(Exception e) {
        return ResponseEntity.badRequest().body(ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST,
                "요청 항목의 형식과 필수값을 확인하세요."));
    }
}
