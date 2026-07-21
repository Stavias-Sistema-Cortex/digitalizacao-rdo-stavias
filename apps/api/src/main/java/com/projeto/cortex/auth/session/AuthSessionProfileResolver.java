package com.projeto.cortex.auth.session;

import com.projeto.cortex.auth.AuthSessionResponse;
import com.projeto.cortex.auth.otp.AuthenticatedIdentity;
import java.time.Instant;

/** Builds the minimal public session profile for a deployment-specific scope. */
public interface AuthSessionProfileResolver {

    /** Runs before a session or cookie can be issued. */
    void requireEligibleForSessionIssue(AuthenticatedIdentity identity);

    AuthSessionResponse profileForIssuedSession(
            AuthenticatedIdentity identity,
            Instant expiresAt
    );

    AuthSessionResponse profileForResolvedSession(ResolvedAuthSession session);
}
