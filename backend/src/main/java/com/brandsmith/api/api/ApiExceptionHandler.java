package com.brandsmith.api.api;

import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

import com.brandsmith.api.budget.BudgetExceededException;
import com.brandsmith.api.session.OwnerMismatchException;
import com.brandsmith.api.session.RefusalException;

@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, String>> onValidation(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(err -> err.getField() + " " + err.getDefaultMessage())
                .orElse("Invalid request");
        return message(HttpStatus.BAD_REQUEST, message);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, String>> onUnreadable(HttpMessageNotReadableException e) {
        return message(HttpStatus.BAD_REQUEST, "Malformed request body");
    }

    @ExceptionHandler(RefusalException.class)
    public ResponseEntity<Map<String, String>> onRefusal(RefusalException e) {
        return message(HttpStatus.UNPROCESSABLE_ENTITY, e.getMessage());
    }

    @ExceptionHandler(BudgetExceededException.class)
    public ResponseEntity<Map<String, String>> onBudget(BudgetExceededException e) {
        return message(HttpStatus.FORBIDDEN, e.getMessage());
    }

    @ExceptionHandler(OwnerMismatchException.class)
    public ResponseEntity<Map<String, String>> onOwnerMismatch(OwnerMismatchException e) {
        return message(HttpStatus.UNAUTHORIZED, e.getMessage());
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, String>> onStatus(ResponseStatusException e) {
        String reason = e.getReason() == null ? e.getStatusCode().toString() : e.getReason();
        return message(HttpStatus.valueOf(e.getStatusCode().value()), reason);
    }

    private ResponseEntity<Map<String, String>> message(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(Map.of("message", message));
    }
}
