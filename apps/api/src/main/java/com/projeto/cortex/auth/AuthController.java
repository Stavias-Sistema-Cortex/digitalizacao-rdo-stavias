package com.projeto.cortex.auth;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import jakarta.servlet.http.HttpServletRequest;

@RestController
public class AuthController {

    private final AuthService authService;
    private final CpfBloomFilterService cpfBloomFilterService;
    private final JwtService jwtService;
    private final AuthLoginRateLimiter loginRateLimiter;

    public AuthController(
            AuthService authService,
            CpfBloomFilterService cpfBloomFilterService,
            JwtService jwtService,
            AuthLoginRateLimiter loginRateLimiter
    ) {
        this.authService = authService;
        this.cpfBloomFilterService = cpfBloomFilterService;
        this.jwtService = jwtService;
        this.loginRateLimiter = loginRateLimiter;
    }

    /** Validação exata do CPF contra os colaboradores ativos da Academy. */
    @PostMapping("/api/auth/login")
    public LoginResponse login(
            @RequestBody LoginRequest request,
            HttpServletRequest servletRequest
    ) {
        loginRateLimiter.check(servletRequest.getRemoteAddr());
        String cpf = request == null ? null : request.cpf();
        String senha = request == null ? null : request.senha();

        return authService.autenticarPorCpf(cpf, senha)
                .map(profile -> profile.withToken(
                        jwtService.gerarToken(profile.colaboradorId())
                ))
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.UNAUTHORIZED,
                        "CPF não encontrado no Academy ou colaborador inativo."
                ));
    }

    /** Filtro de Bloom dos CPFs ativos para validação de login offline. */
    @GetMapping("/api/auth/cpf-filter")
    public CpfFilterResponse cpfFilter() {
        return cpfBloomFilterService.gerarFiltro();
    }
}
