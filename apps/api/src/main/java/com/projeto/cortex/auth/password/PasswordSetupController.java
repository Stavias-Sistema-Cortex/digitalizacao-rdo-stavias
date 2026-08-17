package com.projeto.cortex.auth.password;

import com.projeto.cortex.auth.AuthLoginRateLimiter;
import com.projeto.cortex.auth.CurrentUserService;
import com.projeto.cortex.auth.otp.ClientAddressResolver;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@Profile("postgresql-common")
public final class PasswordSetupController {

    static final String INVALID_CODE_MESSAGE =
            "Código inválido ou expirado.";
    static final String THROTTLED_MESSAGE =
            "Muitas tentativas de autenticação. Tente novamente mais tarde.";

    private final PasswordSetupService service;
    private final CurrentUserService currentUser;
    private final ClientAddressResolver addresses;
    private final AuthLoginRateLimiter rateLimiter;

    public PasswordSetupController(
            PasswordSetupService service,
            CurrentUserService currentUser,
            ClientAddressResolver addresses,
            AuthLoginRateLimiter rateLimiter
    ) {
        this.service = service;
        this.currentUser = currentUser;
        this.addresses = addresses;
        this.rateLimiter = rateLimiter;
    }

    @PostMapping("/api/auth/password/setup")
    public ResponseEntity<Void> complete(
            @RequestBody(required = false) CompletePasswordSetupRequest request,
            HttpServletRequest servletRequest,
            HttpServletResponse response
    ) {
        response.setHeader("Cache-Control", "no-store");
        if (!rateLimiter.allow(addresses.resolve(servletRequest))) {
            throw new ResponseStatusException(
                    HttpStatus.TOO_MANY_REQUESTS,
                    THROTTLED_MESSAGE
            );
        }
        try {
            boolean completed = service.complete(
                    request == null ? null : request.cpf(),
                    request == null ? null : request.code(),
                    request == null ? null : request.password()
            );
            if (!completed) {
                throw new ResponseStatusException(
                        HttpStatus.UNAUTHORIZED,
                        INVALID_CODE_MESSAGE
                );
            }
            return ResponseEntity.noContent().build();
        } catch (PasswordPolicyViolationException exception) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    exception.getMessage()
            );
        }
    }

    @PostMapping("/api/admin/colaboradores/{colaboradorId}/password-code")
    public IssuedPasswordSetupCode issue(
            @PathVariable String colaboradorId,
            HttpServletResponse response
    ) {
        response.setHeader("Cache-Control", "no-store");
        return service.issue(currentUser.requireUserId(), colaboradorId);
    }
}
