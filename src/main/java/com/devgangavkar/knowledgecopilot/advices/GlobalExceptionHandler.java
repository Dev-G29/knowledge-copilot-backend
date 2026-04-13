package com.devgangavkar.knowledgecopilot.advices;

import com.devgangavkar.knowledgecopilot.exception.AiServiceException;
import com.devgangavkar.knowledgecopilot.exception.ApiErrorResponse;
import com.devgangavkar.knowledgecopilot.exception.ResourceNotFoundException;
import com.devgangavkar.knowledgecopilot.exception.UnauthorizedException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import java.time.Instant;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ApiErrorResponse> handleNotFound(
            ResourceNotFoundException exception,
            HttpServletRequest request
    ) {
        log.warn("Resource not found for path {}: {}", request.getRequestURI(), exception.getMessage());
        return buildResponse(HttpStatus.NOT_FOUND, exception.getMessage(), request, List.of());
    }

    @ExceptionHandler({UnauthorizedException.class, AccessDeniedException.class})
    public ResponseEntity<ApiErrorResponse> handleUnauthorized(
            Exception exception,
            HttpServletRequest request
    ) {
        HttpStatus status = exception instanceof UnauthorizedException ? HttpStatus.UNAUTHORIZED : HttpStatus.FORBIDDEN;
        log.warn("Authorization failure for path {}: {}", request.getRequestURI(), exception.getMessage());
        return buildResponse(status, exception.getMessage(), request, List.of());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiErrorResponse> handleValidation(
            MethodArgumentNotValidException exception,
            HttpServletRequest request
    ) {
        List<String> details = exception.getBindingResult().getFieldErrors().stream()
                .map(this::formatFieldError)
                .toList();
        log.warn("Validation failed for path {}: {}", request.getRequestURI(), details);
        return buildResponse(HttpStatus.BAD_REQUEST, "Validation failed", request, details);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiErrorResponse> handleConstraintViolation(
            ConstraintViolationException exception,
            HttpServletRequest request
    ) {
        List<String> details = exception.getConstraintViolations().stream()
                .map(violation -> violation.getPropertyPath() + ": " + violation.getMessage())
                .toList();
        log.warn("Constraint violation for path {}: {}", request.getRequestURI(), details);
        return buildResponse(HttpStatus.BAD_REQUEST, "Validation failed", request, details);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiErrorResponse> handleUnreadableRequest(
            HttpMessageNotReadableException exception,
            HttpServletRequest request
    ) {
        log.warn("Malformed request body for path {}: {}", request.getRequestURI(), exception.getMessage());
        return buildResponse(HttpStatus.BAD_REQUEST, "Malformed request body", request, List.of());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiErrorResponse> handleBadRequest(
            IllegalArgumentException exception,
            HttpServletRequest request
    ) {
        log.warn("Bad request for path {}: {}", request.getRequestURI(), exception.getMessage());
        return buildResponse(HttpStatus.BAD_REQUEST, exception.getMessage(), request, List.of());
    }

    @ExceptionHandler(AiServiceException.class)
    public ResponseEntity<ApiErrorResponse> handleAiFailure(
            AiServiceException exception,
            HttpServletRequest request
    ) {
        log.error("AI service failure for path {}: {}", request.getRequestURI(), exception.getMessage(), exception);
        return buildResponse(HttpStatus.BAD_GATEWAY, exception.getMessage(), request, List.of());
    }

    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<ApiErrorResponse> handleDatabaseFailure(
            DataAccessException exception,
            HttpServletRequest request
    ) {
        log.error("Database failure for path {}: {}", request.getRequestURI(), exception.getMessage(), exception);
        return buildResponse(HttpStatus.INTERNAL_SERVER_ERROR, "Database operation failed", request, List.of());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiErrorResponse> handleGeneric(
            Exception exception,
            HttpServletRequest request
    ) {
        log.error("Unhandled exception for path {}: {}", request.getRequestURI(), exception.getMessage(), exception);
        return buildResponse(HttpStatus.INTERNAL_SERVER_ERROR, "An unexpected error occurred", request, List.of());
    }

    private String formatFieldError(FieldError error) {
        return error.getField() + ": " + (error.getDefaultMessage() == null ? "invalid value" : error.getDefaultMessage());
    }

    private ResponseEntity<ApiErrorResponse> buildResponse(
            HttpStatus status,
            String message,
            HttpServletRequest request,
            List<String> details
    ) {
        ApiErrorResponse response = new ApiErrorResponse(
                Instant.now(),
                status.value(),
                status.getReasonPhrase(),
                message,
                request.getRequestURI(),
                details
        );
        return ResponseEntity.status(status).body(response);
    }
}
