package com.projeto.cortex.auth.offline;

import java.util.Map;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

@RestControllerAdvice(assignableTypes = OfflineGrantController.class)
class OfflineGrantExceptionHandler {

    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<Map<String, String>> responseStatus(
            ResponseStatusException exception
    ) {
        String reason = exception.getReason();
        String message = reason == null || reason.isBlank()
                ? "Não foi possível emitir o acesso offline."
                : reason;
        return ResponseEntity.status(exception.getStatusCode())
                .cacheControl(CacheControl.noStore())
                .body(Map.of("message", message));
    }
}
