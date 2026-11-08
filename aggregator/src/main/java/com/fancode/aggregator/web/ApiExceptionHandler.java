package com.fancode.aggregator.web;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.stream.Collectors;

/**
 * Error contract. For the webhook this matters: 4xx tells the provider not to retry,
 * 5xx tells it to retry with the same deliveryId.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    public record ApiError(String error, String message) {
    }

    @ExceptionHandler(MatchNotFoundException.class)
    ResponseEntity<ApiError> notFound(MatchNotFoundException e) {
        return error(HttpStatus.NOT_FOUND, "match_not_found", e.getMessage());
    }

    @ExceptionHandler(NoResourceFoundException.class)
    ResponseEntity<ApiError> noRoute(NoResourceFoundException e) {
        return error(HttpStatus.NOT_FOUND, "not_found", e.getMessage());
    }

    @ExceptionHandler(UnknownProviderMatchException.class)
    ResponseEntity<ApiError> unknownProviderMatch(UnknownProviderMatchException e) {
        return error(HttpStatus.UNPROCESSABLE_ENTITY, "unknown_provider_match", e.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiError> invalid(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getAllErrors().stream()
                .map(err -> err instanceof org.springframework.validation.FieldError fe
                        ? fe.getField() + " " + fe.getDefaultMessage()
                        : err.getDefaultMessage())
                .sorted()
                .collect(Collectors.joining("; "));
        return error(HttpStatus.BAD_REQUEST, "invalid_payload", message);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ApiError> unreadable(HttpMessageNotReadableException e) {
        return error(HttpStatus.BAD_REQUEST, "malformed_json", "Request body is not valid JSON for this endpoint");
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<ApiError> badRequest(IllegalArgumentException e) {
        return error(HttpStatus.BAD_REQUEST, "invalid_payload", e.getMessage());
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiError> unexpected(Exception e) {
        log.error("Unhandled error", e);
        return error(HttpStatus.INTERNAL_SERVER_ERROR, "internal_error", "Temporary failure; retry later");
    }

    private static ResponseEntity<ApiError> error(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status).body(new ApiError(code, message));
    }
}
