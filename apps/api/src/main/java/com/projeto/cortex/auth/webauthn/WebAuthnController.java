package com.projeto.cortex.auth.webauthn;

import com.projeto.cortex.auth.AuthSessionResponse;
import com.projeto.cortex.auth.CurrentUserService;
import com.projeto.cortex.auth.otp.AuthenticatedIdentity;
import com.projeto.cortex.auth.otp.ClientAddressResolver;
import com.projeto.cortex.auth.session.AuthCookieService;
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
    private final ClientAddressResolver clientAddresses;
    private final WebAuthnRateLimiter rateLimiter;

    public WebAuthnController(
            WebAuthnService webAuthn,
            CurrentUserService currentUser,
            AuthSessionService sessions,
            AuthCookieService cookies,
            ClientAddressResolver clientAddresses,
            WebAuthnRateLimiter rateLimiter
    ) {
        this.webAuthn = webAuthn;
        this.currentUser = currentUser;
        this.sessions = sessions;
        this.cookies = cookies;
        this.clientAddresses = clientAddresses;
        this.rateLimiter = rateLimiter;
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
            HttpServletRequest request,
            HttpServletResponse response
    ) {
        noStore(response);
        requireAllowed(
                WebAuthnRateLimitAction.AUTHENTICATION_OPTIONS,
                request
        );
        return webAuthn.startAuthentication();
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
        AuthenticatedIdentity identity = webAuthn.finishAuthentication(
                result == null ? null : result.challengeId(),
                result == null ? null : result.credential()
        );
        IssuedAuthSession issued = sessions.issue(identity);
        cookies.write(response, issued);
        return AuthSessionResponse.from(
                identity,
                issued.expiresAt(),
                currentUser.allowedObraIds(identity.colaboradorId())
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
}
