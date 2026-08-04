package com.projeto.cortex.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * O teto que faltava no envelope.
 *
 * <p>{@code /api/sync/push} limitava a quantidade de mutações e não o tamanho
 * de cada uma; as demais rotas de escrita não limitavam nem uma coisa nem
 * outra. Quem tivesse sessão — e conseguir uma era barato antes do disjuntor de
 * login — mandava gigabytes de JSON e a instância inteira parava, levando junto
 * a sincronização de todo mundo que trabalha na mesma obra.
 *
 * <p>São dois caminhos e os dois precisam fechar: o {@code Content-Length}, que
 * é o caso comum, e a contagem do que foi realmente lido, que é o único jeito
 * de medir uma requisição {@code chunked} — ela não declara tamanho nenhum, e
 * confiar no cabeçalho seria construir a porta e deixar a janela aberta.
 */
class ApiRequestBodyLimitFilterTest {

    private static final long TETO = 4_096L;

    private final ApiRequestBodyLimitFilter filter =
            new ApiRequestBodyLimitFilter(TETO);

    private MockHttpServletRequest post(int bytes) {
        MockHttpServletRequest request = new MockHttpServletRequest(
                "POST",
                "/api/sync/push"
        );
        request.setContentType(MediaType.APPLICATION_JSON_VALUE);
        request.setContent(new byte[bytes]);
        return request;
    }

    @Test
    void recusaAntesDeLerQuandoOTamanhoDeclaradoJaPassaDoTeto()
            throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(post((int) TETO + 1), response, chain);

        assertThat(response.getStatus())
                .isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE.value());
        assertThat(response.getContentAsString())
                .contains("Requisição grande demais");
        // Nada seguiu adiante: recusar cedo é o ponto do filtro.
        assertThat(chain.getRequest()).isNull();
    }

    @Test
    void deixaPassarOCorpoQueCabe() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(post((int) TETO), response, chain);

        assertThat(response.getStatus()).isEqualTo(HttpStatus.OK.value());
        assertThat(chain.getRequest()).isNotNull();
    }

    /**
     * O caso que o cabeçalho não cobre: sem {@code Content-Length}, só contar
     * o que foi lido revela o tamanho — e a contagem tem de parar no teto, não
     * depois de ter engolido tudo.
     */
    @Test
    void paraDeLerNoTetoQuandoOTamanhoNaoFoiDeclarado() throws Exception {
        MockHttpServletRequest request = post(0);
        request.setContent(new byte[(int) TETO * 4]);
        // MockHttpServletRequest deriva o Content-Length do conteúdo; zerar o
        // cabeçalho é o que reproduz uma requisição chunked de verdade.
        request.removeHeader("Content-Length");

        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain() {

            @Override
            public void doFilter(
                    jakarta.servlet.ServletRequest servletRequest,
                    jakarta.servlet.ServletResponse servletResponse
            ) throws IOException, jakarta.servlet.ServletException {
                // É aqui que o conversor de mensagem leria o corpo.
                servletRequest.getInputStream().readAllBytes();
                super.doFilter(servletRequest, servletResponse);
            }
        };

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus())
                .isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE.value());
    }

    /** O anexo tem teto próprio e maior: foto de obra é grande e legítima. */
    @Test
    void naoInterfereNoEnvioDeAnexo() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest(
                "POST",
                "/api/objetos"
        );
        request.setContentType(MediaType.MULTIPART_FORM_DATA_VALUE);
        request.setContent(new byte[(int) TETO * 4]);
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(HttpStatus.OK.value());
        assertThat(chain.getRequest()).isNotNull();
    }

    @Test
    void naoSeMeteComLeituraNemComRotaDeFora() throws Exception {
        for (HttpServletRequest request : new HttpServletRequest[] {
                new MockHttpServletRequest("GET", "/api/rdos"),
                new MockHttpServletRequest("POST", "/assets/app.js"),
        }) {
            MockHttpServletResponse response = new MockHttpServletResponse();
            MockFilterChain chain = new MockFilterChain();

            filter.doFilter(request, response, chain);

            assertThat(response.getStatus()).isEqualTo(HttpStatus.OK.value());
            assertThat(chain.getRequest()).isNotNull();
        }
    }

    /** Um erro de verdade não pode virar 413 e sumir da vista de quem depura. */
    @Test
    void deixaPassarOErroQueNaoEDeTamanho() {
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain() {

            @Override
            public void doFilter(
                    jakarta.servlet.ServletRequest servletRequest,
                    jakarta.servlet.ServletResponse servletResponse
            ) {
                throw new IllegalStateException("Falha real do serviço.");
            }
        };

        assertThatThrownBy(() -> filter.doFilter(post(10), response, chain))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Falha real do serviço.");
    }

    @Test
    void recusaTetoPequenoDemaisParaSerReal() {
        assertThatThrownBy(() -> new ApiRequestBodyLimitFilter(512))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aRecusaSaiEmJsonComRotuloDeNaoGuardar() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(
                post((int) TETO + 1),
                response,
                new MockFilterChain()
        );

        assertThat(response.getContentType()).startsWith("application/json");
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
        assertThat(response.getContentAsByteArray())
                .isEqualTo(("{\"message\":\"Requisição grande demais.\"}")
                        .getBytes(StandardCharsets.UTF_8));
    }
}
