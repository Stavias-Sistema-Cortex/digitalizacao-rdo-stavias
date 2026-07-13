package com.projeto.cortex.auth;

import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AuthController {

    static final String LOGIN_DISABLED_MESSAGE =
            "Login por CPF desativado. Use a verificação por e-mail.";
    static final String CPF_FILTER_DISABLED_MESSAGE =
            "Filtro de CPF desativado.";

    /** O login CPF/senha foi encerrado; desafios verificáveis o substituirão. */
    @PostMapping("/api/auth/login")
    public ResponseEntity<Map<String, String>> login(
            @RequestBody LoginRequest request
    ) {
        return gone(LOGIN_DISABLED_MESSAGE);
    }

    /** O filtro legado não é mais distribuído como prova de autenticação. */
    @GetMapping("/api/auth/cpf-filter")
    public ResponseEntity<Map<String, String>> cpfFilter() {
        return gone(CPF_FILTER_DISABLED_MESSAGE);
    }

    private ResponseEntity<Map<String, String>> gone(String message) {
        return ResponseEntity.status(HttpStatus.GONE).body(
                Map.of("message", message)
        );
    }
}
