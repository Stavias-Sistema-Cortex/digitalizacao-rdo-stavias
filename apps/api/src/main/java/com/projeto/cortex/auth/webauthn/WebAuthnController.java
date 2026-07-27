package com.projeto.cortex.auth.webauthn;

import com.projeto.cortex.auth.AuthSessionResponse;
import com.projeto.cortex.auth.CurrentUserService;
import com.projeto.cortex.auth.identity.AuthChallengeLookupMaterial;
import com.projeto.cortex.auth.identity.CpfLookupDigestService;
import com.projeto.cortex.auth.otp.AuthenticatedIdentity;
import com.projeto.cortex.auth.otp.ClientAddressResolver;
import com.projeto.cortex.auth.session.AuthCookieService;
import com.projeto.cortex.auth.session.AuthSessionProfileResolver;
import com.projeto.cortex.auth.session.AuthSessionService;
import com.projeto.cortex.auth.session.IssuedAuthSession;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** Authenticated enrollment and public discoverable passkey authentication. */
@RestController
public class WebAuthnController {

    private final WebAuthnService webAuthn;
    private final CurrentUserService currentUser;
    private final AuthSessionService sessions;
    private final AuthCookieService cookies;
    private final AuthSessionProfileResolver sessionProfiles;
    private final ClientAddressResolver clientAddresses;
    private final WebAuthnRateLimiter rateLimiter;
    private final CpfLookupDigestService cpfDigests;

    public WebAuthnController(
            WebAuthnService webAuthn,
            CurrentUserService currentUser,
            AuthSessionService sessions,
            AuthCookieService cookies,
            AuthSessionProfileResolver sessionProfiles,
            ClientAddressResolver clientAddresses,
            WebAuthnRateLimiter rateLimiter,
            CpfLookupDigestService cpfDigests
    ) {
        this.webAuthn = webAuthn;
        this.currentUser = currentUser;
        this.sessions = sessions;
        this.cookies = cookies;
        this.sessionProfiles = sessionProfiles;
        this.clientAddresses = clientAddresses;
        this.rateLimiter = rateLimiter;
        this.cpfDigests = cpfDigests;
    }

    @PostMapping("/api/auth/passkeys/registration/options")
    public WebAuthnOptionsResponse startRegistration(
            HttpServletResponse response
    ) {
        noStore(response);
        return webAuthn.startRegistration(currentUser.requireUserId());
    }

    @PostMapping("/api/auth/passkeys/registration/verify")
    public PasskeySummary finishRegistration(
            @RequestBody(required = false) WebAuthnCeremonyResult result,
            HttpServletResponse response
    ) {
        noStore(response);
        return webAuthn.finishRegistration(
                currentUser.requireUserId(),
                result == null ? null : result.challengeId(),
                result == null ? null : result.credential()
        );
    }

    @PostMapping("/api/auth/passkeys/authentication/options")
    public WebAuthnOptionsResponse startAuthentication(
            @RequestBody(required = false)
            CpfPasskeyAuthenticationRequest authenticationRequest,
            HttpServletRequest request,
            HttpServletResponse response
    ) {
        noStore(response);
        requireAllowed(
                WebAuthnRateLimitAction.AUTHENTICATION_OPTIONS,
                request
        );
        String cpf = authenticationRequest == null
                ? null
                : authenticationRequest.cpf();
        requireIdentifierAllowed(cpfDigests.challengeLookup(cpf));
        return webAuthn.startCpfBoundAuthentication(
                cpf
        );
    }

    @PostMapping("/api/auth/passkeys/authentication/verify")
    public AuthSessionResponse finishAuthentication(
            @RequestBody(required = false) WebAuthnCeremonyResult result,
            HttpServletRequest request,
            HttpServletResponse response
    ) {
        noStore(response);
        requireAllowed(
                WebAuthnRateLimitAction.AUTHENTICATION_VERIFY,
                request
        );
        AuthenticatedIdentity identity =
                webAuthn.finishCpfBoundAuthentication(
                        result == null ? null : result.challengeId(),
                        result == null ? null : result.credential()
                );
        sessionProfiles.requireEligibleForSessionIssue(identity);
        IssuedAuthSession issued = sessions.issue(identity);
        cookies.write(response, issued);
        return sessionProfiles.profileForIssuedSession(
                identity,
                issued.expiresAt()
        );
    }

    private void noStore(HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
    }

    private void requireAllowed(
            WebAuthnRateLimitAction action,
            HttpServletRequest request
    ) {
        if (Boolean.TRUE.equals(request.getAttribute(
                WebAuthnPreMvcFilter.RATE_LIMIT_APPLIED_ATTRIBUTE
        ))) {
            return;
        }
        if (!rateLimiter.allow(action, clientAddresses.resolve(request))) {
            throw new ResponseStatusException(
                    HttpStatus.TOO_MANY_REQUESTS,
                    "Muitas tentativas de autenticação. Tente novamente mais tarde."
            );
        }
    }

    private void requireIdentifierAllowed(
            AuthChallengeLookupMaterial material
    ) {
        if (!rateLimiter.allowIdentifier(material)) {
            throw new ResponseStatusException(
                    HttpStatus.TOO_MANY_REQUESTS,
                    "Muitas tentativas de autenticação. Tente novamente mais tarde."
            );
        }
    }
}
