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
 * <p>A obra deixou de ser compartimento — qualquer colaborador reconhecido
 * alcança qualquer obra. O que estes testes guardam agora é a fronteira que
 * ficou: papel nulo não entra em lugar nenhum, e o escopo de um Beta é
 * enumerado em vez de vazio, porque vazio significa "sem restrição" e o perfil
 * de sessão lê isso como papel administrativo.
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

    private void obrasExistentes(String... obraIds) {
        when(jdbc.queryForList(
                contains("FROM obra"),
                eq(String.class)
        )).thenReturn(List.of(obraIds));
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

    /**
     * Beta alcança obra com que nunca teve vínculo. Era o caso que faltava: a
     * pessoa existia, a obra existia, e mesmo assim uma não achava a outra.
     */
    @Test
    void betaAcessaQualquerObraSemDependerDeVinculo() {
        papel("beta", PapelAcesso.BETA);

        assertThat(service.isAlfa("beta")).isFalse();
        assertThat(service.podeAcessarObra("beta", "obra-de-outra-frente"))
                .isTrue();
        assertThat(service.podeAcessarObra("beta", "obra-nunca-vista"))
                .isTrue();
    }

    /**
     * A parte que não pode ser "simplificada" para {@code Optional.empty()}.
     *
     * <p>Vazio quer dizer "sem restrição", e {@code AuthSessionResponse} deriva
     * o papel efetivo justamente da ausência de restrição. Devolver vazio aqui
     * faria todo colaborador se apresentar como Alfa na sessão, e com ele
     * entrariam Financeiro e PDOR — que ficaram de fora de propósito. O escopo
     * de obra abriu; o papel, não.
     */
    @Test
    void allowedObraIdsDeBetaEnumeraTodasAsObrasSemVirarEscopoGlobal() {
        papel("beta", PapelAcesso.BETA);
        obrasExistentes("obra-1", "obra-2", "obra-3");

        Optional<Set<String>> escopo = service.allowedObraIds("beta");

        assertThat(escopo).contains(Set.of("obra-1", "obra-2", "obra-3"));
        assertThat(escopo).isNotEmpty();
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

    /**
     * A fronteira que sobrou. Não é mais entre obras: é entre quem o cadastro
     * reconhece e quem não reconhece.
     */
    @Test
    void requireWorksiteAccessBloqueiaQuemNaoTemPapel() {
        papel("fantasma", null);
        autenticarComo("fantasma");

        assertThatThrownBy(() -> service.requireWorksiteAccess("obra-1"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("permissão para acessar esta obra");
    }

    @Test
    void requireWorksiteAccessLiberaBetaEmObraSemVinculo() {
        papel("beta", PapelAcesso.BETA);
        autenticarComo("beta");

        service.requireWorksiteAccess("obra-de-outra-frente");
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

    @Test
    void requireRdoAccessLiberaBetaNoRdoDeObraSemVinculo() {
        papel("beta", PapelAcesso.BETA);
        rdoNaObra("rdo-2", "obra-de-outra-frente");
        autenticarComo("beta");

        service.requireRdoAccess("rdo-2");
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
        obrasExistentes("obra-1", "obra-2");
        autenticarComo("beta");

        service.allowedObraIds("beta");
        service.allowedObraIds("beta");
        service.allowedObraIds("beta");

        verify(jdbc, times(1)).queryForList(
                contains("FROM obra"),
                eq(String.class)
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
