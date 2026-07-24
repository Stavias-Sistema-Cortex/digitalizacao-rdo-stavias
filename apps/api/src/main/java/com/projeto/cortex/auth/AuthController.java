package com.projeto.cortex.auth;

import com.projeto.cortex.auth.otp.AuthenticatedIdentity;
import com.projeto.cortex.auth.otp.ClientAddressResolver;
import com.projeto.cortex.auth.otp.EmailOtpChallengeService;
import com.projeto.cortex.auth.otp.OtpChallengeRequest;
import com.projeto.cortex.auth.otp.OtpChallengeResponse;
import com.projeto.cortex.auth.otp.OtpVerifyRequest;
import com.projeto.cortex.auth.session.AuthCookieService;
import com.projeto.cortex.auth.session.AuthSessionFilter;
import com.projeto.cortex.auth.session.AuthSessionProfileResolver;
import com.projeto.cortex.auth.session.AuthSessionService;
import com.projeto.cortex.auth.session.IssuedAuthSession;
import com.projeto.cortex.auth.session.ResolvedAuthSession;
import com.projeto.cortex.auth.identity.CpfNormalizer;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Map;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
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

    static final String LOGIN_REJECTED_MESSAGE = "CPF ou acesso inválido.";
    static final String CPF_FILTER_DISABLED_MESSAGE =
            "Filtro de CPF desativado.";

    private final EmailOtpChallengeService otpChallenges;
    private final Optional<AuthService> authService;
    private final ClientAddressResolver clientAddresses;
    private final AuthSessionService sessions;
    private final AuthCookieService cookies;
    private final AuthSessionProfileResolver sessionProfiles;
    private final DirectCpfLoginPolicy directCpfLoginPolicy;
    private final EmailOtpAuthenticationPolicy emailOtpAuthenticationPolicy;

    @Autowired
    public AuthController(
            EmailOtpChallengeService otpChallenges,
            Optional<AuthService> authService,
            ClientAddressResolver clientAddresses,
            AuthSessionService sessions,
            AuthCookieService cookies,
            AuthSessionProfileResolver sessionProfiles,
            DirectCpfLoginPolicy directCpfLoginPolicy,
            EmailOtpAuthenticationPolicy emailOtpAuthenticationPolicy
    ) {
        this.otpChallenges = otpChallenges;
        this.authService = authService;
        this.clientAddresses = clientAddresses;
        this.sessions = sessions;
        this.cookies = cookies;
        this.sessionProfiles = sessionProfiles;
        this.directCpfLoginPolicy = directCpfLoginPolicy;
        this.emailOtpAuthenticationPolicy = emailOtpAuthenticationPolicy;
    }

    @PostMapping("/api/auth/email/challenges")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public OtpChallengeResponse requestChallenge(
            @RequestBody(required = false) OtpChallengeRequest request,
            HttpServletRequest servletRequest,
            HttpServletResponse servletResponse
    ) {
        emailOtpAuthenticationPolicy.requireEnabled();
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
        emailOtpAuthenticationPolicy.requireEnabled();
        AuthenticatedIdentity identity = otpChallenges.verify(
                challengeId,
                request == null ? null : request.code()
        ).orElseThrow(() -> new ResponseStatusException(
                HttpStatus.UNAUTHORIZED,
                "Código inválido ou expirado."
        ));
        sessionProfiles.requireEligibleForSessionIssue(identity);
        IssuedAuthSession issued = sessions.issue(identity);
        cookies.write(response, issued);
        response.setHeader("Cache-Control", "no-store");
        return sessionProfiles.profileForIssuedSession(
                identity,
                issued.expiresAt()
        );
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
            return sessionProfiles.profileForResolvedSession(session);
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

    @PostMapping("/api/auth/login")
    public AuthSessionResponse login(
            @RequestBody(required = false) LoginRequest request,
            HttpServletResponse response
    ) {
        response.setHeader("Cache-Control", "no-store");
        directCpfLoginPolicy.requireEnabled();
        String cpf = canonicalCpf(request);
        AuthenticatedIdentity identity = authService.orElseThrow(
                () -> new ResponseStatusException(
                        HttpStatus.SERVICE_UNAVAILABLE,
                        "Autenticação indisponível."
                )
        ).autenticarPorCpf(cpf)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.UNAUTHORIZED,
                        LOGIN_REJECTED_MESSAGE
                ));
        sessionProfiles.requireEligibleForSessionIssue(identity);
        IssuedAuthSession issued = sessions.issue(identity);
        cookies.write(response, issued);
        return sessionProfiles.profileForIssuedSession(
                identity,
                issued.expiresAt()
        );
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

    private String canonicalCpf(LoginRequest request) {
        try {
            return CpfNormalizer.requireValid(
                    request == null ? null : request.cpf()
            );
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "CPF inválido."
            );
        }
    }
}
