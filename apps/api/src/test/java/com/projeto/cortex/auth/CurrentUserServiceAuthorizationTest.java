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
import static org.mockito.Mockito.when;

/**
 * Cobre o núcleo de autorização Alfa/Beta: resolução de papel, acesso à obra
 * por vínculo explícito (sem inferência), obras autorizadas e a proteção contra
 * IDOR (Beta não acessa obra de outrem alterando o id).
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

    private void vinculoAtivo(String userId, String obraId, boolean ativo) {
        when(jdbc.queryForObject(
                contains("vinculo_colaborador_obra"),
                eq(Integer.class),
                eq(userId),
                eq(obraId)
        )).thenReturn(ativo ? 1 : 0);
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
    void betaAcessaApenasObraComVinculoAtivo() {
        papel("beta", PapelAcesso.BETA);
        vinculoAtivo("beta", "obra-autorizada", true);
        vinculoAtivo("beta", "obra-proibida", false);

        assertThat(service.isAlfa("beta")).isFalse();
        assertThat(service.podeAcessarObra("beta", "obra-autorizada")).isTrue();
        assertThat(service.podeAcessarObra("beta", "obra-proibida")).isFalse();
    }

    @Test
    void allowedObraIdsDeBetaListaSomenteVinculosAtivos() {
        papel("beta", PapelAcesso.BETA);
        when(jdbc.queryForList(
                contains("vinculo_colaborador_obra"),
                eq(String.class),
                eq("beta")
        )).thenReturn(List.of("obra-1", "obra-2"));

        assertThat(service.allowedObraIds("beta"))
                .isEqualTo(Optional.of(Set.of("obra-1", "obra-2")));
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
    void papelAusenteNuncaEhElevadoPorPerfilLegado() throws Exception {
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

        assertThat(service.papelAcesso("admin-legado"))
                .isEqualTo(PapelAcesso.BETA);
    }

    @Test
    void usuarioOuObraEmBrancoNegaAcesso() {
        assertThat(service.podeAcessarObra(null, "obra-1")).isFalse();
        assertThat(service.podeAcessarObra("beta", " ")).isFalse();
    }

    @Test
    void requireWorksiteAccessBloqueiaBetaEmObraDeOutrem() {
        papel("beta", PapelAcesso.BETA);
        vinculoAtivo("beta", "obra-de-outrem", false);
        autenticarComo("beta");

        assertThatThrownBy(() -> service.requireWorksiteAccess("obra-de-outrem"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("permissão para acessar esta obra");
    }

    @Test
    void requireWorksiteAccessLiberaBetaNaObraVinculada() {
        papel("beta", PapelAcesso.BETA);
        vinculoAtivo("beta", "obra-vinculada", true);
        autenticarComo("beta");

        service.requireWorksiteAccess("obra-vinculada");
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
    void requireRdoAccessBloqueiaBetaEmRdoDeOutraObra() {
        papel("beta", PapelAcesso.BETA);
        rdoNaObra("rdo-1", "obra-de-outrem");
        vinculoAtivo("beta", "obra-de-outrem", false);
        autenticarComo("beta");

        assertThatThrownBy(() -> service.requireRdoAccess("rdo-1"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("permissão para acessar esta obra");
    }

    @Test
    void requireRdoAccessLiberaBetaNoRdoDaObraVinculada() {
        papel("beta", PapelAcesso.BETA);
        rdoNaObra("rdo-2", "obra-vinculada");
        vinculoAtivo("beta", "obra-vinculada", true);
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
