package com.projeto.cortex.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Set;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.stereotype.Component;

/**
 * Protege as rotas /api/** exigindo um JWT válido em "Authorization: Bearer".
 * Exceções (pré-autenticação): login, filtro de CPF e health.
 */
@Component
public class JwtAuthFilter extends OncePerRequestFilter {

    private static final Set<String> LIBERADAS = Set.of(
            "/api/auth/login",
            "/api/auth/cpf-filter",
            "/api/health"
    );

    private final JwtService jwtService;

    public JwtAuthFilter(JwtService jwtService) {
        this.jwtService = jwtService;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        if (path == null || !path.startsWith("/api/")) {
            return true;
        }
        if (LIBERADAS.contains(path)) {
            return true;
        }
        return "OPTIONS".equalsIgnoreCase(request.getMethod());
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        String token = (header != null && header.startsWith("Bearer "))
                ? header.substring(7)
                : null;

        if (!jwtService.tokenValido(token)) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write(
                    "{\"message\":\"Autenticação necessária ou sessão expirada.\"}"
            );
            return;
        }

        filterChain.doFilter(request, response);
    }
}
