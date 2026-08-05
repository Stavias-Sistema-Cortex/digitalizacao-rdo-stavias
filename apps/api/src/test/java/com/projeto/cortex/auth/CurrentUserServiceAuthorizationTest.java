package com.projeto.cortex.auth;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.server.ResponseStatusException;

import java.sql.ResultSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Cobre o núcleo de autorização Alfa/Beta: resolução de papel, acesso à obra e
 * a forma do escopo devolvido.
 *
 * <p>A obra voltou a ser compartimento para quem é Beta: sem vínculo
 * {@code ATIVO} não há acesso. Duas fronteiras convivem, e confundi-las é o
 * erro fácil — papel nulo não entra em obra nenhuma nem com vínculo gravado, e
 * Alfa entra em todas sem vínculo nenhum. O vínculo só decide o caso do meio.
 *
 * <p>O escopo de um Beta continua enumerado em vez de vazio, mesmo quando o
 * conjunto é vazio de verdade: {@code Optional.empty()} significa "sem
 * restrição", e o perfil de sessão lê isso como papel administrativo.
 */
class CurrentUserServiceAuthorizationTest {

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final CurrentUserService service =
            new CurrentUserService(jdbc, mock(Environment.class), false);

    @AfterEach
    void limparContexto() {
        RequestContextHolder.resetRequestAttributes();
    }

    private void papel(String userId, PapelAcesso papel) {
        when(jdbc.query(
                contains("FROM colaborador"),
                any(ResultSetExtractor.class),
                eq(userId)
        )).thenReturn(papel);
    }

    /**
     * As obras que o colaborador alcança, por vínculo direto ou por equipe.
     *
     * <p>A consulta é uma união das duas portas, então o identificador entra
     * duas vezes — uma para cada lado. O teste não distingue por qual delas o
     * acesso veio, e não deve: quem decide isso é
     * {@link com.projeto.cortex.auth.AutorizacaoDeObra}, e a cerca só precisa
     * saber se existe caminho.
     */
    private void obrasVinculadas(String userId, String... obraIds) {
        when(jdbc.queryForList(
                contains("FROM vinculo_colaborador_obra"),
                eq(String.class),
                eq(userId),
                eq(userId)
        )).thenReturn(List.of(obraIds));
    }

    /**
     * A consulta pontual, que pergunta por uma obra só. É separada da listagem
     * de propósito: é a forma que o índice da V66 atende.
     *
     * <p>Os quatro argumentos alternam obra e colaborador porque a expressão
     * carrega os dois caminhos, cada um com o seu par.
     */
    private void vinculoAtivoNaObra(
            String userId,
            String obraId,
            boolean vinculado
    ) {
        when(jdbc.queryForObject(
                contains("FROM vinculo_colaborador_obra"),
                eq(Boolean.class),
                eq(obraId),
                eq(userId),
                eq(obraId),
                eq(userId)
        )).thenReturn(vinculado);
    }

    private void rdoNaObra(String rdoId, String obraId) {
        when(jdbc.query(
                contains("FROM rdo"),
                any(ResultSetExtractor.class),
                eq(rdoId)
        )).thenReturn(obraId);
    }

    @Test
    void alfaAcessaQualquerObraSemVinculo() {
        papel("alfa", PapelAcesso.ALFA);

        assertThat(service.isAlfa("alfa")).isTrue();
        assertThat(service.isAdmin("alfa")).isTrue();
        assertThat(service.podeAcessarObra("alfa", "qualquer-obra")).isTrue();
        assertThat(service.allowedObraIds("alfa")).isEqualTo(Optional.empty());
    }

    @Test
    void betaEntraOndeTemVinculoAtivoENaoEntraOndeNaoTem() {
        papel("beta", PapelAcesso.BETA);
        vinculoAtivoNaObra("beta", "obra-da-frente", true);
        vinculoAtivoNaObra("beta", "obra-de-outra-frente", false);

        assertThat(service.isAlfa("beta")).isFalse();
        assertThat(service.podeAcessarObra("beta", "obra-da-frente")).isTrue();
        assertThat(service.podeAcessarObra("beta", "obra-de-outra-frente"))
                .isFalse();
    }

    /**
     * Alfa não consulta vínculo: o papel já responde. Se consultasse, um Alfa
     * sem linha na tabela perderia acesso a tudo — e ninguém vincula Alfa.
     */
    @Test
    void alfaNaoConsultaVinculoParaDecidir() {
        papel("alfa", PapelAcesso.ALFA);

        assertThat(service.podeAcessarObra("alfa", "obra-1")).isTrue();

        verify(jdbc, times(0)).queryForObject(
                contains("FROM vinculo_colaborador_obra"),
                eq(Boolean.class),
                eq("obra-1"),
                eq("alfa"),
                eq("obra-1"),
                eq("alfa")
        );
    }

    /**
     * A parte que não pode ser "simplificada" para {@code Optional.empty()}.
     *
     * <p>Vazio quer dizer "sem restrição", e {@code AuthSessionResponse} deriva
     * o papel efetivo justamente da ausência de restrição. Devolver vazio aqui
     * faria todo colaborador se apresentar como Alfa na sessão, e com ele
     * entrariam Financeiro e PDOR — que ficaram de fora de propósito.
     */
    @Test
    void allowedObraIdsDeBetaEnumeraAsObrasVinculadas() {
        papel("beta", PapelAcesso.BETA);
        obrasVinculadas("beta", "obra-1", "obra-2");

        Optional<Set<String>> escopo = service.allowedObraIds("beta");

        assertThat(escopo).contains(Set.of("obra-1", "obra-2"));
    }

    /**
     * O desfecho mais fácil de confundir com defeito: Beta sem vínculo nenhum
     * entra no sistema e não vê obra alguma. Conjunto vazio, e não ausência de
     * restrição — a diferença entre não ver nada e ver tudo.
     */
    @Test
    void betaSemVinculoRecebeConjuntoVazioENaoEscopoGlobal() {
        papel("beta", PapelAcesso.BETA);
        obrasVinculadas("beta");

        Optional<Set<String>> escopo = service.allowedObraIds("beta");

        assertThat(escopo).contains(Set.of());
        assertThat(escopo).isNotEqualTo(Optional.empty());
    }

    @Test
    void usuarioSemPapelValidoNaoAcessaNada() {
        papel("fantasma", null);

        assertThat(service.isAlfa("fantasma")).isFalse();
        assertThat(service.podeAcessarObra("fantasma", "obra-1")).isFalse();
        assertThat(service.allowedObraIds("fantasma"))
                .isEqualTo(Optional.of(Set.of()));
    }

    @Test
    void papelAusenteNegaAcessoMesmoComPerfilLegado() throws Exception {
        when(jdbc.query(
                contains("FROM colaborador"),
                any(ResultSetExtractor.class),
                eq("admin-legado")
        )).thenAnswer(invocation -> {
            ResultSetExtractor<PapelAcesso> extractor = invocation.getArgument(1);
            ResultSet resultSet = mock(ResultSet.class);
            when(resultSet.next()).thenReturn(true);
            when(resultSet.getString("papel_acesso")).thenReturn(null);
            when(resultSet.getString("nome_perfil")).thenReturn("ADMINISTRADOR");
            when(resultSet.getString("nome_grupo")).thenReturn("ADMIN");
            return extractor.extractData(resultSet);
        });

        assertThat(service.papelAcesso("admin-legado")).isNull();
    }

    @Test
    void papelPersistidoForaDoContratoCanonicoNegaAcesso() throws Exception {
        when(jdbc.query(
                contains("FROM colaborador"),
                any(ResultSetExtractor.class),
                eq("papel-corrompido")
        )).thenAnswer(invocation -> {
            ResultSetExtractor<PapelAcesso> extractor = invocation.getArgument(1);
            ResultSet resultSet = mock(ResultSet.class);
            when(resultSet.next()).thenReturn(true);
            when(resultSet.getString("papel_acesso")).thenReturn("alfa");
            return extractor.extractData(resultSet);
        });

        assertThat(service.papelAcesso("papel-corrompido")).isNull();
        assertThat(service.podeAcessarObra(
                "papel-corrompido",
                "obra-1"
        )).isFalse();
    }

    @Test
    void usuarioOuObraEmBrancoNegaAcesso() {
        assertThat(service.podeAcessarObra(null, "obra-1")).isFalse();
        assertThat(service.podeAcessarObra("beta", " ")).isFalse();
    }

    @Test
    void requireWorksiteAccessBloqueiaQuemNaoTemPapel() {
        papel("fantasma", null);
        autenticarComo("fantasma");

        assertThatThrownBy(() -> service.requireWorksiteAccess("obra-1"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("permissão para acessar esta obra");
    }

    @Test
    void requireWorksiteAccessBloqueiaBetaEmObraSemVinculo() {
        papel("beta", PapelAcesso.BETA);
        vinculoAtivoNaObra("beta", "obra-de-outra-frente", false);
        autenticarComo("beta");

        assertThatThrownBy(() ->
                service.requireWorksiteAccess("obra-de-outra-frente"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("permissão para acessar esta obra");
    }

    @Test
    void requireWorksiteAccessLiberaBetaNaObraVinculada() {
        papel("beta", PapelAcesso.BETA);
        vinculoAtivoNaObra("beta", "obra-da-frente", true);
        autenticarComo("beta");

        service.requireWorksiteAccess("obra-da-frente");
    }

    @Test
    void requireAdminBloqueiaBeta() {
        papel("beta", PapelAcesso.BETA);
        autenticarComo("beta");

        assertThatThrownBy(service::requireAdmin)
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Alfa");
    }

    @Test
    void requireRdoAccessBloqueiaQuemNaoTemPapel() {
        papel("fantasma", null);
        rdoNaObra("rdo-1", "obra-1");
        autenticarComo("fantasma");

        assertThatThrownBy(() -> service.requireRdoAccess("rdo-1"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("permissão para acessar esta obra");
    }

    /**
     * O RDO não é porta dos fundos para a obra: a autorização resolve a obra do
     * RDO e cobra o mesmo vínculo. Sem isso, bastaria um id de RDO para ler o
     * diário de uma frente à qual a pessoa não pertence.
     */
    @Test
    void requireRdoAccessBloqueiaBetaNoRdoDeObraSemVinculo() {
        papel("beta", PapelAcesso.BETA);
        rdoNaObra("rdo-2", "obra-de-outra-frente");
        vinculoAtivoNaObra("beta", "obra-de-outra-frente", false);
        autenticarComo("beta");

        assertThatThrownBy(() -> service.requireRdoAccess("rdo-2"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("permissão para acessar esta obra");
    }

    @Test
    void requireRdoAccessLiberaBetaNoRdoDaObraVinculada() {
        papel("beta", PapelAcesso.BETA);
        rdoNaObra("rdo-3", "obra-da-frente");
        vinculoAtivoNaObra("beta", "obra-da-frente", true);
        autenticarComo("beta");

        service.requireRdoAccess("rdo-3");
    }

    @Test
    void requireRdoAccessRetorna404QuandoRdoNaoExiste() {
        rdoNaObra("rdo-inexistente", null);

        assertThatThrownBy(() -> service.requireRdoAccess("rdo-inexistente"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("RDO não encontrado");
    }

    @Test
    void papelEConsultadoUmaVezPorRequisicaoAindaQuePerguntadoVarias() {
        papel("alfa", PapelAcesso.ALFA);
        autenticarComo("alfa");

        service.isAlfa("alfa");
        service.isAdmin("alfa");
        service.requireAdmin();
        service.podeAcessarObra("alfa", "obra-1");
        service.allowedObraIds("alfa");

        verify(jdbc, times(1)).query(
                contains("FROM colaborador"),
                any(ResultSetExtractor.class),
                eq("alfa")
        );
    }

    /**
     * O alcance da memória é a requisição, e precisa ser exatamente esse: um
     * papel revogado tem de valer já na requisição seguinte.
     */
    @Test
    void cadaNovaRequisicaoVoltaAConsultarOPapel() {
        papel("alfa", PapelAcesso.ALFA);

        autenticarComo("alfa");
        service.isAlfa("alfa");
        autenticarComo("alfa");
        service.isAlfa("alfa");

        verify(jdbc, times(2)).query(
                contains("FROM colaborador"),
                any(ResultSetExtractor.class),
                eq("alfa")
        );
    }

    @Test
    void foraDeUmaRequisicaoNadaEGuardado() {
        papel("alfa", PapelAcesso.ALFA);

        service.isAlfa("alfa");
        service.isAlfa("alfa");

        verify(jdbc, times(2)).query(
                contains("FROM colaborador"),
                any(ResultSetExtractor.class),
                eq("alfa")
        );
    }

    @Test
    void colaboradoresDiferentesNaoCompartilhamPapelNaMesmaRequisicao() {
        papel("alfa", PapelAcesso.ALFA);
        papel("beta", PapelAcesso.BETA);
        autenticarComo("alfa");

        assertThat(service.isAlfa("alfa")).isTrue();
        assertThat(service.isAlfa("beta")).isFalse();
        assertThat(service.isAlfa("alfa")).isTrue();

        verify(jdbc, times(1)).query(
                contains("FROM colaborador"),
                any(ResultSetExtractor.class),
                eq("alfa")
        );
        verify(jdbc, times(1)).query(
                contains("FROM colaborador"),
                any(ResultSetExtractor.class),
                eq("beta")
        );
    }

    /**
     * A lista de obras é a consulta cara que restou no caminho de autorização,
     * e ela não muda no meio de uma requisição. Relê-la a cada pergunta seria
     * ir ao banco buscar a resposta que já se tem.
     */
    @Test
    void listaDeObrasEConsultadaUmaVezPorRequisicao() {
        papel("beta", PapelAcesso.BETA);
        obrasVinculadas("beta", "obra-1", "obra-2");
        autenticarComo("beta");

        service.allowedObraIds("beta");
        service.allowedObraIds("beta");
        service.allowedObraIds("beta");

        verify(jdbc, times(1)).queryForList(
                contains("FROM vinculo_colaborador_obra"),
                eq(String.class),
                eq("beta"),
                eq("beta")
        );
    }

    /**
     * A verificação pontual também é lembrada, e por obra. Serviços que
     * percorrem uma lista perguntam pela mesma obra muitas vezes na mesma
     * requisição.
     */
    @Test
    void oVinculoDeUmaObraEConsultadoUmaVezPorRequisicao() {
        papel("beta", PapelAcesso.BETA);
        vinculoAtivoNaObra("beta", "obra-1", true);
        autenticarComo("beta");

        service.podeAcessarObra("beta", "obra-1");
        service.podeAcessarObra("beta", "obra-1");
        service.podeAcessarObra("beta", "obra-1");

        verify(jdbc, times(1)).queryForObject(
                contains("FROM vinculo_colaborador_obra"),
                eq(Boolean.class),
                eq("obra-1"),
                eq("beta"),
                eq("obra-1"),
                eq("beta")
        );
    }

    /**
     * A negativa é resposta, não ausência de resposta: se ela não fosse
     * lembrada, o colaborador inexistente seria o caso que mais consultaria o
     * banco — uma ida por pergunta, exatamente no caminho que nega acesso.
     */
    @Test
    void aNegativaTambemELembradaDentroDaRequisicao() {
        papel("fantasma", null);
        autenticarComo("fantasma");

        assertThat(service.isAlfa("fantasma")).isFalse();
        assertThat(service.podeAcessarObra("fantasma", "obra-1")).isFalse();
        assertThat(service.allowedObraIds("fantasma"))
                .isEqualTo(Optional.of(Set.of()));

        verify(jdbc, times(1)).query(
                contains("FROM colaborador"),
                any(ResultSetExtractor.class),
                eq("fantasma")
        );
    }

    private void autenticarComo(String userId) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(
                CurrentUserService.REQUEST_ATTRIBUTE_USER_ID,
                userId
        );
        RequestContextHolder.setRequestAttributes(
                new ServletRequestAttributes(request)
        );
    }
}
