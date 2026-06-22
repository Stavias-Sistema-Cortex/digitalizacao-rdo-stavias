package com.projeto.cortex.pdoc;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;

@RestControllerAdvice(assignableTypes = PdocController.class)
public class PdocExceptionHandler {

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<PdocErrorResponse> handleResponseStatus(
            ResponseStatusException exception,
            HttpServletRequest request
    ) {
        HttpStatus status = HttpStatus.valueOf(exception.getStatusCode().value());

        return ResponseEntity
                .status(status)
                .body(new PdocErrorResponse(
                        LocalDateTime.now(),
                        status.value(),
                        status.getReasonPhrase(),
                        exception.getReason(),
                        request.getRequestURI()
                ));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<PdocErrorResponse> handleIllegalArgument(
            IllegalArgumentException exception,
            HttpServletRequest request
    ) {
        return ResponseEntity
                .badRequest()
                .body(new PdocErrorResponse(
                        LocalDateTime.now(),
                        HttpStatus.BAD_REQUEST.value(),
                        HttpStatus.BAD_REQUEST.getReasonPhrase(),
                        mensagemArgumento(exception),
                        request.getRequestURI()
                ));
    }

    private String mensagemArgumento(IllegalArgumentException exception) {
        String message = exception.getMessage();
        if (message == null || message.isBlank()) {
            return "Parâmetro inválido.";
        }
        if (message.startsWith("No enum constant")) {
            return "tipoDisparo inválido. Use MANUAL, EVENT, SCHEDULED ou API.";
        }
        return message;
    }
}
