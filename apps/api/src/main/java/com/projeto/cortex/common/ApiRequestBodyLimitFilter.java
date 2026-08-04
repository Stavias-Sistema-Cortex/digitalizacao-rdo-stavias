package com.projeto.cortex.common;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Teto de corpo para tudo que a API recebe em JSON.
 *
 * <p>Cada porta cara já tinha o seu: o anexo é cortado pelo multipart do
 * contêiner, o desafio por e-mail em 4 KB, o WebAuthn em 64 KB. O resto da API
 * não tinha nenhum, e o buraco não era teórico: {@code /api/sync/push} limita a
 * <em>quantidade</em> de mutações a 100, mas nada limitava o tamanho de cada
 * uma. Cem mutações gordas eram gigabytes lidos para dentro da memória de uma
 * instância que tem centenas de megabytes — e quem para não é só quem mandou, é
 * a obra inteira que sincroniza pela mesma instância.
 *
 * <p>O teto vale antes da autenticação de propósito. Recusar cedo é o ponto:
 * validar quem mandou exigiria ler o que mandou, e ler é exatamente o que não
 * pode acontecer sem limite.
 *
 * <p>O {@code Content-Length} resolve o caso comum, e o envoltório resolve o
 * resto: uma requisição {@code chunked} não declara tamanho nenhum, e sem contar
 * os bytes lidos ela passaria pela porta da frente sem ser medida.
 */
public final class ApiRequestBodyLimitFilter extends OncePerRequestFilter {

    private static final Logger log =
            LoggerFactory.getLogger(ApiRequestBodyLimitFilter.class);

    private static final Set<String> BOUNDED_METHODS =
            Set.of("POST", "PUT", "PATCH");

    private final long maxBodyBytes;

    public ApiRequestBodyLimitFilter(long maxBodyBytes) {
        if (maxBodyBytes < 1_024) {
            throw new IllegalArgumentException(
                    "Teto de corpo da API pequeno demais para ser real."
            );
        }
        this.maxBodyBytes = maxBodyBytes;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        if (path == null || !path.startsWith("/api/")) {
            return true;
        }
        if (!BOUNDED_METHODS.contains(request.getMethod())) {
            return true;
        }
        String contentType = request.getContentType();
        // O anexo tem teto próprio, em spring.servlet.multipart, e é maior que
        // este de propósito: foto de obra é grande e legítima.
        return contentType != null
                && contentType.toLowerCase(Locale.ROOT).startsWith(
                        MediaType.MULTIPART_FORM_DATA_VALUE
                );
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        if (request.getContentLengthLong() > maxBodyBytes) {
            reject(request, response, "declarado");
            return;
        }
        try {
            filterChain.doFilter(
                    new BoundedBodyRequest(request, maxBodyBytes),
                    response
            );
        } catch (RuntimeException | IOException | ServletException exception) {
            // Quem lê o corpo é um conversor de mensagem, e ele embrulha o que
            // vier de baixo. Sem desembrulhar, um corpo grande demais viraria
            // um 400 genérico e a causa se perderia.
            if (!estouro(exception)) {
                throw exception;
            }
            if (response.isCommitted()) {
                throw exception;
            }
            reject(request, response, "medido");
        }
    }

    private boolean estouro(Throwable exception) {
        for (Throwable cause = exception;
                cause != null;
                cause = cause.getCause()) {
            if (cause instanceof BodyTooLargeException) {
                return true;
            }
            if (cause.getCause() == cause) {
                return false;
            }
        }
        return false;
    }

    private void reject(
            HttpServletRequest request,
            HttpServletResponse response,
            String origem
    ) throws IOException {
        log.warn(
                "body_too_large origem={} method={} path={} remote={}",
                origem,
                request.getMethod(),
                request.getRequestURI(),
                request.getRemoteAddr()
        );
        byte[] body = "{\"message\":\"Requisição grande demais.\"}"
                .getBytes(StandardCharsets.UTF_8);
        response.resetBuffer();
        response.setStatus(HttpStatus.PAYLOAD_TOO_LARGE.value());
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType("application/json");
        response.setHeader("Cache-Control", "no-store");
        response.setContentLength(body.length);
        response.getOutputStream().write(body);
    }

    /** Sinal interno: nunca escapa do filtro, sempre vira 413. */
    static final class BodyTooLargeException extends IOException {

        private static final long serialVersionUID = 1L;

        BodyTooLargeException(long maxBodyBytes) {
            super("Corpo excede " + maxBodyBytes + " bytes.");
        }
    }

    /**
     * Conta o que foi lido e para no teto.
     *
     * <p>Contar é o que cobre o {@code chunked}, que não declara tamanho. E o
     * envoltório não guarda os bytes: bufferizar para conferir depois gastaria
     * exatamente a memória que este filtro existe para poupar.
     */
    private static final class BoundedBodyRequest
            extends HttpServletRequestWrapper {

        private final long maxBodyBytes;
        private long lidos;

        private BoundedBodyRequest(
                HttpServletRequest request,
                long maxBodyBytes
        ) {
            super(request);
            this.maxBodyBytes = maxBodyBytes;
        }

        private void contar(int quantidade) throws IOException {
            lidos += quantidade;
            if (lidos > maxBodyBytes) {
                throw new BodyTooLargeException(maxBodyBytes);
            }
        }

        @Override
        public ServletInputStream getInputStream() throws IOException {
            ServletInputStream origem = super.getInputStream();
            return new ServletInputStream() {

                @Override
                public boolean isFinished() {
                    return origem.isFinished();
                }

                @Override
                public boolean isReady() {
                    return origem.isReady();
                }

                @Override
                public void setReadListener(ReadListener listener) {
                    throw new UnsupportedOperationException(
                            "Leitura assíncrona não suportada."
                    );
                }

                @Override
                public int read() throws IOException {
                    int value = origem.read();
                    if (value >= 0) {
                        contar(1);
                    }
                    return value;
                }

                @Override
                public int read(byte[] buffer, int offset, int length)
                        throws IOException {
                    int read = origem.read(buffer, offset, length);
                    if (read > 0) {
                        contar(read);
                    }
                    return read;
                }

                @Override
                public void close() throws IOException {
                    origem.close();
                }
            };
        }

        @Override
        public BufferedReader getReader() throws IOException {
            String encoding = getCharacterEncoding();
            return new BufferedReader(new InputStreamReader(
                    getInputStream(),
                    encoding == null
                            ? StandardCharsets.UTF_8
                            : Charset.forName(encoding)
            ));
        }
    }
}
