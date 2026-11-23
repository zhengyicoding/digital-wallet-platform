package com.acme.bank;

import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import java.util.UUID;

@RestControllerAdvice
class BankProblems {
    static class Failure extends RuntimeException {
        final int status; final String code;
        Failure(int status,String code) { super(code); this.status=status; this.code=code; }
    }
    static ProblemDetail problem(int status,String code) {
        var body=ProblemDetail.forStatusAndDetail(HttpStatusCode.valueOf(status),code);
        body.setProperty("code",code);
        body.setProperty("traceId",UUID.randomUUID().toString().replace("-",""));
        return body;
    }
    @ExceptionHandler(Failure.class) ProblemDetail failure(Failure failure) { return problem(failure.status,failure.code); }
    @ExceptionHandler({MethodArgumentNotValidException.class,HttpMessageNotReadableException.class})
    ProblemDetail validation(Exception ignored) { return problem(400,"VALIDATION_FAILED"); }
}
