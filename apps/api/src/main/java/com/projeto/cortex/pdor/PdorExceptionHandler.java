package com.projeto.cortex.pdor;

import com.projeto.cortex.financeiro.PrevisaoFinanceiraController;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;

@RestControllerAdvice(assignableTypes = {
        PdorController.class,
        PrevisaoFinanceiraController.class
})
public class PdorExceptionHandler {

    @ExceptionHandler(PdorCalculationException.class)
    public ResponseEntity<PdorErrorResponse> handleCalculationFailure(
            PdorCalculationException exception,
            HttpServletRequest request
    ) {
        return ResponseEntity
                .status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new PdorErrorResponse(
                        LocalDateTime.now(),
                        HttpStatus.INTERNAL_SERVER_ERROR.value(),
                        HttpStatus.INTERNAL_SERVER_ERROR.getReasonPhrase(),
                        "Não foi possível calcular o PDOR. Use o identificador de correlação para suporte.",
                        request.getRequestURI(),
                        PdorCalculationException.CODE,
                        exception.correlationId()
                ));
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<PdorErrorResponse> handleResponseStatus(
            ResponseStatusException exception,
            HttpServletRequest request
    ) {
        HttpStatus status = HttpStatus.valueOf(exception.getStatusCode().value());

        return ResponseEntity
                .status(status)
                .body(new PdorErrorResponse(
                        LocalDateTime.now(),
                        status.value(),
                        status.getReasonPhrase(),
                        exception.getReason(),
                        request.getRequestURI()
                ));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<PdorErrorResponse> handleIllegalArgument(
            IllegalArgumentException exception,
            HttpServletRequest request
    ) {
        return ResponseEntity
                .badRequest()
                .body(new PdorErrorResponse(
                        LocalDateTime.now(),
                        HttpStatus.BAD_REQUEST.value(),
                        HttpStatus.BAD_REQUEST.getReasonPhrase(),
                        mensagemArgumento(exception),
                        request.getRequestURI(),
                        "PDOR_INVALID_REQUEST",
                        null
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
        if (message.equals("page não pode ser negativo.")
                || message.equals("size deve estar entre 1 e 100.")) {
            return message;
        }
        return "Parâmetro inválido.";
    }
}
