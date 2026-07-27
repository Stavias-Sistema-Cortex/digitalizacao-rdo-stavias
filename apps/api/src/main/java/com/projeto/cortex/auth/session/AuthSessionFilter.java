package com.projeto.cortex.auth.session;

import com.projeto.cortex.auth.CurrentUserService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import org.springframework.web.filter.OncePerRequestFilter;

/** Authenticates API requests exclusively through a revocable opaque cookie. */
public class AuthSessionFilter extends OncePerRequestFilter {

    public static final String REQUEST_ATTRIBUTE_SESSION =
            "cortex.resolvedAuthSession";

    private final AuthSessionService sessions;
    private final AuthCookieService cookies;
    private final AuthPublicEndpointPolicy publicEndpoints;

    public AuthSessionFilter(
            AuthSessionService sessions,
            AuthCookieService cookies
    ) {
        this(sessions, cookies, AuthPublicEndpointPolicy.legacy());
    }

    public AuthSessionFilter(
            AuthSessionService sessions,
            AuthCookieService cookies,
            AuthPublicEndpointPolicy publicEndpoints
    ) {
        this.sessions = sessions;
        this.cookies = cookies;
        this.publicEndpoints = publicEndpoints;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return publicEndpoints.isOutsideApi(request)
                || publicEndpoints.isPublicAuthenticationRequest(
                        request
                );
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        request.removeAttribute(CurrentUserService.REQUEST_ATTRIBUTE_USER_ID);
        request.removeAttribute(REQUEST_ATTRIBUTE_SESSION);

        Optional<String> token = cookies.readSessionToken(request);
        Optional<ClientInstanceProof> instance = ClientInstanceProof.from(
                request
        );
        if (instance.isEmpty()) {
            reject(response);
            return;
        }
        Optional<ResolvedAuthSession> resolved = token.flatMap(raw ->
                sessions.resolve(raw, instance.orElseThrow())
        );
        if (resolved.isEmpty()) {
            reject(response);
            return;
        }

        ResolvedAuthSession session = resolved.orElseThrow();
        if (!sessions.matchesClientInstance(
                session,
                instance.orElseThrow()
        )) {
            reject(response);
            return;
        }
        request.setAttribute(
                CurrentUserService.REQUEST_ATTRIBUTE_USER_ID,
                session.collaboratorId()
        );
        request.setAttribute(REQUEST_ATTRIBUTE_SESSION, session);
        filterChain.doFilter(request, response);
    }

    private void reject(HttpServletResponse response) throws IOException {
        byte[] body = (
                "{\"message\":\"Autenticação necessária ou sessão expirada.\"}"
        ).getBytes(StandardCharsets.UTF_8);
        response.resetBuffer();
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType("application/json");
        response.setHeader("Cache-Control", "no-store");
        response.setContentLength(body.length);
        response.getOutputStream().write(body);
    }
}
