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

    private final CpfBloomFilterService cpfBloomFilterService;

    public AuthController(CpfBloomFilterService cpfBloomFilterService) {
        this.cpfBloomFilterService = cpfBloomFilterService;
    }

    /** O login CPF/senha foi encerrado; desafios verificáveis o substituirão. */
    @PostMapping("/api/auth/login")
    public ResponseEntity<Map<String, String>> login(
            @RequestBody LoginRequest request
    ) {
        return ResponseEntity.status(HttpStatus.GONE).body(
                Map.of("message", LOGIN_DISABLED_MESSAGE)
        );
    }

    /** Filtro de Bloom dos CPFs ativos para validação de login offline. */
    @GetMapping("/api/auth/cpf-filter")
    public CpfFilterResponse cpfFilter() {
        return cpfBloomFilterService.gerarFiltro();
    }
}
