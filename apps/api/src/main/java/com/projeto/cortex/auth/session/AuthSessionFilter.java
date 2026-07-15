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

    public AuthSessionFilter(
            AuthSessionService sessions,
            AuthCookieService cookies
    ) {
        this.sessions = sessions;
        this.cookies = cookies;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return AuthPublicEndpointPolicy.isOutsideApi(request)
                || AuthPublicEndpointPolicy.isPublicAuthenticationRequest(
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
        Optional<ResolvedAuthSession> resolved = token.flatMap(sessions::resolve);
        if (resolved.isEmpty()) {
            reject(response);
            return;
        }

        ResolvedAuthSession session = resolved.orElseThrow();
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
