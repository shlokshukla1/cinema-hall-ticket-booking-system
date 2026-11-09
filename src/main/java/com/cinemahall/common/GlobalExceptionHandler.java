package com.cinemahall.common;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.time.Instant;
import java.util.List;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ErrorResponse> handleApi(ApiException ex, HttpServletRequest request) {
        return respond(ex.getStatus(), ex.getCode(), ex.getMessage(), request);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleBody(MethodArgumentNotValidException ex, HttpServletRequest request) {
        List<ErrorResponse.FieldViolation> fields = ex.getBindingResult().getFieldErrors().stream()
                .map(error -> new ErrorResponse.FieldViolation(error.getField(), error.getDefaultMessage()))
                .toList();
        return ResponseEntity.badRequest().body(new ErrorResponse(Instant.now(), 400, "VALIDATION_FAILED",
                "Please correct the highlighted fields", request.getRequestURI(), fields));
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ErrorResponse> handleConstraint(ConstraintViolationException ex, HttpServletRequest request) {
        return respond(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "The request parameters are invalid", request);
    }

    @ExceptionHandler({
            HttpMessageNotReadableException.class,
            MethodArgumentTypeMismatchException.class,
            MissingServletRequestParameterException.class
    })
    public ResponseEntity<ErrorResponse> handleMalformed(Exception ex, HttpServletRequest request) {
        return respond(HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST", "The request could not be read", request);
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ErrorResponse> handleUploadSize(MaxUploadSizeExceededException ex, HttpServletRequest request) {
        return respond(HttpStatus.PAYLOAD_TOO_LARGE, "POSTER_TOO_LARGE", "This file is too large", request);
    }

    @ExceptionHandler({MissingServletRequestPartException.class, MultipartException.class})
    public ResponseEntity<ErrorResponse> handleUpload(Exception ex, HttpServletRequest request) {
        return respond(HttpStatus.BAD_REQUEST, "INVALID_POSTER", "Choose an image file to upload", request);
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<ErrorResponse> handleHeader(MissingRequestHeaderException ex, HttpServletRequest request) {
        return respond(HttpStatus.BAD_REQUEST, "MISSING_HEADER",
                "The " + ex.getHeaderName() + " header is required", request);
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ErrorResponse> handleNoResource(NoResourceFoundException ex, HttpServletRequest request) {
        return respond(HttpStatus.NOT_FOUND, "NOT_FOUND", "Resource not found", request);
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ErrorResponse> handleMethod(Exception ex, HttpServletRequest request) {
        return respond(HttpStatus.METHOD_NOT_ALLOWED, "METHOD_NOT_ALLOWED", "Method not allowed", request);
    }

    /** A unique, foreign-key or check constraint rejected the write: the request conflicts with stored data. */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ErrorResponse> handleIntegrity(DataIntegrityViolationException ex, HttpServletRequest request) {
        log.debug("Constraint violation on {}", request.getRequestURI(), ex);
        return respond(HttpStatus.CONFLICT, "DATA_CONFLICT",
                "This change conflicts with existing data, for example a duplicate name or a record still in use",
                request);
    }

    /** Lock timeout or deadlock victim: nothing was committed and the client may safely retry. */
    @ExceptionHandler(ConcurrencyFailureException.class)
    public ResponseEntity<ErrorResponse> handleConcurrency(ConcurrencyFailureException ex, HttpServletRequest request) {
        log.warn("Concurrency failure on {}: {}", request.getRequestURI(), ex.getMessage());
        return respond(HttpStatus.CONFLICT, "RESOURCE_BUSY", "This record is busy. Please try again.", request);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Exception ex, HttpServletRequest request) {
        log.error("Unexpected error on {}", request.getRequestURI(), ex);
        return respond(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "Something went wrong on our side", request);
    }

    private ResponseEntity<ErrorResponse> respond(HttpStatus status, String code, String message, HttpServletRequest request) {
        return ResponseEntity.status(status).body(ErrorResponse.of(status.value(), code, message, request.getRequestURI()));
    }
}
