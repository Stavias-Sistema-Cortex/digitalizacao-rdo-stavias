package com.projeto.cortex.sync;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.projeto.cortex.auth.CurrentUserService;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * §13: o pull escopa o carregamento offline por obra. Alfa recebe tudo; Beta
 * recebe apenas as obras vinculadas mais catálogos globais de referência
 * (nunca eventos confidenciais de outras obras nem dados pessoais).
 */
class SyncPullScopeTest {

    private final SyncService service = new SyncService(
            mock(JdbcTemplate.class),
            new ObjectMapper(),
            mock(TransactionTemplate.class),
            mock(CurrentUserService.class),
            List.of()
    );

    @Test
    void alfaRecebeTudoSemFiltro() {
        SyncService.FiltroPull filtro = service.filtroPorEscopo(Optional.empty());

        assertThat(filtro.condicaoSql()).isEmpty();
        assertThat(filtro.parametros()).isEmpty();
    }

    @Test
    void betaFiltraPorObrasVinculadasMaisReferencia() {
        SyncService.FiltroPull filtro =
                service.filtroPorEscopo(Optional.of(Set.of("obra-1")));

        assertThat(filtro.condicaoSql())
                .contains("obra_id IN (?)")
                .contains("obra_id IS NULL AND tipo_entidade IN (?,?,?,?)");
        assertThat(filtro.parametros())
                .containsExactly(
                        "obra-1",
                        "ATIVO",
                        "EQUIPAMENTO",
                        "SERVICO",
                        "FUNCAO_OPERACIONAL"
                );
    }

    @Test
    void betaSemVinculoRecebeSomenteReferenciaGlobal() {
        SyncService.FiltroPull filtro =
                service.filtroPorEscopo(Optional.of(Set.of()));

        assertThat(filtro.condicaoSql())
                .doesNotContain("obra_id IN (")
                .contains("obra_id IS NULL AND tipo_entidade IN (?,?,?,?)");
        assertThat(filtro.parametros())
                .containsExactly(
                        "ATIVO",
                        "EQUIPAMENTO",
                        "SERVICO",
                        "FUNCAO_OPERACIONAL"
                );
    }
}
