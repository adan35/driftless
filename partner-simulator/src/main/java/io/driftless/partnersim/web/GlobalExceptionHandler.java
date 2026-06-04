package io.driftless.partnersim.web;

import io.driftless.partnersim.partner.InjectedFaultException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Centralized error handling for the simulator. Distinguishes deliberate injected faults (surfaced as
 * 500 by design) from genuine client errors (400 on bad input / malformed JSON).
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /** Injected server-side faults ({@code error-rate} / {@code fail-before-response}) become 500s. */
    @ExceptionHandler(InjectedFaultException.class)
    public ResponseEntity<ApiError> handleInjectedFault(InjectedFaultException ex) {
        log.warn("returning injected fault as 500 mode={} message={}", ex.faultMode(), ex.getMessage());
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ApiError(
                        HttpStatus.INTERNAL_SERVER_ERROR.value(), "INJECTED_" + ex.faultMode(), ex.getMessage()));
    }

    /** Bean-validation failures (including invalid fault profiles) become 400s. */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException ex) {
        String detail = ex.getBindingResult().getAllErrors().stream()
                .findFirst()
                .map(error -> error.getDefaultMessage())
                .orElse("validation failed");
        log.info("rejecting invalid request: {}", detail);
        return ResponseEntity.badRequest()
                .body(new ApiError(HttpStatus.BAD_REQUEST.value(), "VALIDATION_FAILED", detail));
    }

    /** Malformed / unparseable JSON bodies become 400s. */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiError> handleUnreadable(HttpMessageNotReadableException ex) {
        log.info(
                "rejecting unreadable request body: {}",
                ex.getMostSpecificCause().getMessage());
        return ResponseEntity.badRequest()
                .body(new ApiError(
                        HttpStatus.BAD_REQUEST.value(), "MALFORMED_REQUEST", "request body is not readable"));
    }
}
