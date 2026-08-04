package com.projeto.cortex.auth.session;

import com.projeto.cortex.auth.CurrentUserService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.filter.OncePerRequestFilter;

/** Authenticates API requests exclusively through a revocable opaque cookie. */
public class AuthSessionFilter extends OncePerRequestFilter {

    /**
     * Uma recusa em silêncio é um ataque em silêncio.
     *
     * <p>O 401 já era devolvido corretamente; o que faltava era ele deixar
     * rastro. Sem isso, uma varredura de dias inteiros não aparece em lugar
     * nenhum e a descoberta acontece pelo estrago, não pelo sinal.
     *
     * <p>A linha carrega método, caminho e origem — e nada mais. Nem token, nem
     * cookie, nem identificador de pessoa: quem investiga precisa saber de onde
     * veio e quando, e log é justamente onde dado pessoal não deveria repousar.
     */
    private static final Logger log =
            LoggerFactory.getLogger(AuthSessionFilter.class);

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
            reject(request, response, "instancia_ausente");
            return;
        }
        Optional<ResolvedAuthSession> resolved = token.flatMap(raw ->
                sessions.resolve(raw, instance.orElseThrow())
        );
        if (resolved.isEmpty()) {
            reject(request, response, "sessao_invalida");
            return;
        }

        ResolvedAuthSession session = resolved.orElseThrow();
        if (!sessions.matchesClientInstance(
                session,
                instance.orElseThrow()
        )) {
            reject(request, response, "instancia_divergente");
            return;
        }
        request.setAttribute(
                CurrentUserService.REQUEST_ATTRIBUTE_USER_ID,
                session.collaboratorId()
        );
        request.setAttribute(REQUEST_ATTRIBUTE_SESSION, session);
        filterChain.doFilter(request, response);
    }

    private void reject(
            HttpServletRequest request,
            HttpServletResponse response,
            String motivo
    ) throws IOException {
        log.warn(
                "auth_denied motivo={} method={} path={} remote={}",
                motivo,
                request.getMethod(),
                request.getRequestURI(),
                request.getRemoteAddr()
        );
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
