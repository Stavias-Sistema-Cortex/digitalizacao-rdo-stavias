package com.projeto.cortex.auth;

import com.projeto.cortex.auth.otp.AuthenticatedIdentity;
import com.projeto.cortex.auth.otp.ClientAddressResolver;
import com.projeto.cortex.auth.otp.EmailOtpChallengeService;
import com.projeto.cortex.auth.otp.OtpChallengeRequest;
import com.projeto.cortex.auth.otp.OtpChallengeResponse;
import com.projeto.cortex.auth.otp.OtpVerifyRequest;
import com.projeto.cortex.auth.session.AuthCookieService;
import com.projeto.cortex.auth.session.AuthSessionFilter;
import com.projeto.cortex.auth.session.AuthSessionService;
import com.projeto.cortex.auth.session.IssuedAuthSession;
import com.projeto.cortex.auth.session.ResolvedAuthSession;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** Public OTP entrypoints and the authenticated opaque-session lifecycle. */
@RestController
public class AuthController {

    static final String LOGIN_DISABLED_MESSAGE =
            "Login por CPF desativado. Use a verificação por e-mail.";
    static final String CPF_FILTER_DISABLED_MESSAGE =
            "Filtro de CPF desativado.";

    private final EmailOtpChallengeService otpChallenges;
    private final ClientAddressResolver clientAddresses;
    private final AuthSessionService sessions;
    private final AuthCookieService cookies;

    public AuthController(
            EmailOtpChallengeService otpChallenges,
            ClientAddressResolver clientAddresses,
            AuthSessionService sessions,
            AuthCookieService cookies
    ) {
        this.otpChallenges = otpChallenges;
        this.clientAddresses = clientAddresses;
        this.sessions = sessions;
        this.cookies = cookies;
    }

    @PostMapping("/api/auth/email/challenges")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public OtpChallengeResponse requestChallenge(
            @RequestBody(required = false) OtpChallengeRequest request,
            HttpServletRequest servletRequest,
            HttpServletResponse servletResponse
    ) {
        servletResponse.setHeader("Cache-Control", "no-store");
        return otpChallenges.request(
                request == null ? null : request.identifier(),
                clientAddresses.resolve(servletRequest)
        );
    }

    @PostMapping("/api/auth/email/challenges/{challengeId}/verify")
    public AuthSessionResponse verifyChallenge(
            @PathVariable String challengeId,
            @RequestBody(required = false) OtpVerifyRequest request,
            HttpServletResponse response
    ) {
        AuthenticatedIdentity identity = otpChallenges.verify(
                challengeId,
                request == null ? null : request.code()
        ).orElseThrow(() -> new ResponseStatusException(
                HttpStatus.UNAUTHORIZED,
                "Código inválido ou expirado."
        ));
        IssuedAuthSession issued = sessions.issue(identity);
        cookies.write(response, issued);
        response.setHeader("Cache-Control", "no-store");
        return AuthSessionResponse.from(identity, issued.expiresAt());
    }

    @GetMapping("/api/auth/session")
    public AuthSessionResponse currentSession(
            HttpServletRequest request,
            HttpServletResponse response
    ) {
        response.setHeader("Cache-Control", "no-store");
        Object value = request.getAttribute(
                AuthSessionFilter.REQUEST_ATTRIBUTE_SESSION
        );
        if (value instanceof ResolvedAuthSession session) {
            return AuthSessionResponse.from(session);
        }
        throw new ResponseStatusException(
                HttpStatus.UNAUTHORIZED,
                "Sessão inválida ou expirada."
        );
    }

    @PostMapping("/api/auth/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(
            HttpServletRequest request,
            HttpServletResponse response
    ) {
        cookies.readSessionToken(request).ifPresent(
                token -> sessions.revoke(token, "LOGOUT")
        );
        cookies.clear(response);
        response.setHeader("Cache-Control", "no-store");
    }

    /** Tombstone for clients that still attempt CPF-as-password login. */
    @PostMapping("/api/auth/login")
    public ResponseEntity<Map<String, String>> legacyLogin(
            @RequestBody(required = false) LoginRequest request
    ) {
        return gone(LOGIN_DISABLED_MESSAGE);
    }

    /** Tombstone for clients that still attempt to download the Bloom filter. */
    @GetMapping("/api/auth/cpf-filter")
    public ResponseEntity<Map<String, String>> legacyCpfFilter() {
        return gone(CPF_FILTER_DISABLED_MESSAGE);
    }

    private ResponseEntity<Map<String, String>> gone(String message) {
        return ResponseEntity.status(HttpStatus.GONE).body(
                Map.of("message", message)
        );
    }
}
