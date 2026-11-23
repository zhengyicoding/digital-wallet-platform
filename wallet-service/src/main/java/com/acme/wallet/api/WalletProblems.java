package com.acme.wallet.api;

import java.util.List;
import java.util.UUID;

import jakarta.servlet.http.HttpServletResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class WalletProblems {

    private final ObjectMapper mapper;

    WalletProblems(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public enum Kind {
        VALIDATION_FAILED(HttpStatus.BAD_REQUEST, "Check the request fields"),
        WALLET_NOT_FOUND(HttpStatus.NOT_FOUND, "Wallet was not found"),
        RECIPIENT_NOT_FOUND(HttpStatus.UNPROCESSABLE_ENTITY, "Recipient wallet was not found"),
        SAME_WALLET(HttpStatus.UNPROCESSABLE_ENTITY, "Choose a different recipient"),
        INSUFFICIENT_FUNDS(HttpStatus.UNPROCESSABLE_ENTITY, "Insufficient balance"),
        DAILY_LIMIT_EXCEEDED(HttpStatus.UNPROCESSABLE_ENTITY, "Daily limit exceeded"),
        IDEMPOTENCY_KEY_REQUIRED(HttpStatus.BAD_REQUEST, "Supply an Idempotency-Key of 1 to 64 characters"),
        UNAUTHENTICATED(HttpStatus.UNAUTHORIZED, "A valid access token is required"),
        FORBIDDEN(HttpStatus.FORBIDDEN, "The required role is missing"),
        NOT_FOUND(HttpStatus.NOT_FOUND, "Resource was not found"),
        INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "An unexpected error occurred");

        private final HttpStatus status;
        private final String detail;

        Kind(HttpStatus status, String detail) {
            this.status = status;
            this.detail = detail;
        }
    }

    public static Failure invalid(String field, String message) {
        return new Failure(Kind.VALIDATION_FAILED, List.of(new FieldError(field, message)));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ResponseEntity<ProblemDetail> invalidParameter(MethodArgumentTypeMismatchException ex) {
        return failure(invalid(ex.getName(), "Invalid parameter"));
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    ResponseEntity<ProblemDetail> missingParameter(MissingServletRequestParameterException ex) {
        return failure(invalid(ex.getParameterName(), "Required parameter"));
    }

    @ExceptionHandler(Failure.class)
    ResponseEntity<ProblemDetail> failure(Failure failure) {
        var body = problem(failure.kind);
        if (failure.kind == Kind.VALIDATION_FAILED) {
            body.setProperty("errors", failure.errors);
        }
        return response(body);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ProblemDetail> malformedBody() {
        return failure(invalid("body", "Use a valid JSON object"));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ProblemDetail> unexpected(Exception exception) {
        if (exception instanceof ErrorResponse error && error.getStatusCode().is4xxClientError()) {
            if (error.getStatusCode().value() == 404) {
                return response(problem(Kind.NOT_FOUND));
            }
            var body = ProblemDetail.forStatus(error.getStatusCode());
            body.setDetail(body.getTitle());
            body.setProperty("code", "HTTP_" + body.getStatus());
            body.setProperty("traceId", traceId());
            return ResponseEntity.status(error.getStatusCode()).headers(error.getHeaders())
                    .contentType(MediaType.APPLICATION_PROBLEM_JSON).body(body);
        }
        return response(problem(Kind.INTERNAL_ERROR));
    }

    public void write(HttpServletResponse response, Kind kind) throws java.io.IOException {
        response.setStatus(kind.status.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        mapper.writeValue(response.getOutputStream(), problem(kind));
    }

    private static ResponseEntity<ProblemDetail> response(ProblemDetail body) {
        return ResponseEntity.status(body.getStatus()).contentType(MediaType.APPLICATION_PROBLEM_JSON).body(body);
    }

    private static ProblemDetail problem(Kind kind) {
        var body = ProblemDetail.forStatusAndDetail(kind.status, kind.detail);
        body.setProperty("code", kind.name());
        body.setProperty("traceId", traceId());
        return body;
    }

    private static String traceId() {
        String traceId = MDC.get("traceId");
        return traceId == null ? UUID.randomUUID().toString() : traceId;
    }

    record FieldError(String field, String message) {}

    public static class Failure extends RuntimeException {
        private final Kind kind;
        private final List<FieldError> errors;

        public Failure(Kind kind) {
            this(kind, List.of());
        }

        Failure(Kind kind, List<FieldError> errors) {
            this.kind = kind;
            this.errors = errors;
        }
    }
}
