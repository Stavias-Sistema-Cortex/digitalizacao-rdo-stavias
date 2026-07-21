package com.projeto.cortex.auth.activation;

import com.projeto.cortex.auth.AuthSessionResponse;
import com.projeto.cortex.auth.PapelAcesso;
import com.projeto.cortex.auth.otp.AuthenticatedIdentity;
import com.projeto.cortex.auth.session.AuthSessionProfileResolver;
import com.projeto.cortex.auth.session.ResolvedAuthSession;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/** Activation permits only the initial ALFA and never queries worksite scope. */
@Component
@Profile("postgresql-activation")
public final class PostgresqlActivationSessionProfileResolver
        implements AuthSessionProfileResolver {

    private static final Optional<Set<String>> GLOBAL_SCOPE = Optional.empty();

    @Override
    public void requireEligibleForSessionIssue(AuthenticatedIdentity identity) {
        if (identity == null || identity.papelAcesso() != PapelAcesso.ALFA) {
            throw activationDenied();
        }
    }

    @Override
    public AuthSessionResponse profileForIssuedSession(
            AuthenticatedIdentity identity,
            Instant expiresAt
    ) {
        requireEligibleForSessionIssue(identity);
        return AuthSessionResponse.from(identity, expiresAt, GLOBAL_SCOPE);
    }

    @Override
    public AuthSessionResponse profileForResolvedSession(
            ResolvedAuthSession session
    ) {
        if (session == null || session.role() != PapelAcesso.ALFA) {
            throw activationDenied();
        }
        return AuthSessionResponse.from(session, GLOBAL_SCOPE);
    }

    private ResponseStatusException activationDenied() {
        return new ResponseStatusException(
                HttpStatus.UNAUTHORIZED,
                "Código inválido ou expirado."
        );
    }
}
