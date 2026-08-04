package com.projeto.cortex.sync;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.projeto.cortex.auth.CurrentUserService;
import com.projeto.cortex.financeiro.access.FinancialAccessService;
import com.projeto.cortex.rdos.RdoDraftUpdateService;
import com.projeto.cortex.rdos.RdoQueryService;
import com.projeto.cortex.rdos.RdoService;
import com.projeto.cortex.rdos.RdoWorkflowService;
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
            new SyncOperationRegistry(java.util.List.of()),
            mock(CurrentUserService.class),
            mock(FinancialAccessService.class)
    );

    @Test
    void alfaRecebeTudoSemFiltro() {
        SyncService.FiltroPull filtro = service.filtroPorEscopo(
                Optional.empty(),
                Set.of()
        );

        assertThat(filtro.condicaoSql()).isEmpty();
        assertThat(filtro.parametros()).isEmpty();
    }

    @Test
    void betaFiltraPorObrasVinculadasMaisReferencia() {
        SyncService.FiltroPull filtro =
                service.filtroPorEscopo(
                        Optional.of(Set.of("obra-1")),
                        Set.of("obra-1")
                );

        assertThat(filtro.condicaoSql())
                .contains("tipo_entidade NOT IN")
                .contains("cortex_evento_operacional.obra_id IN (?)")
                .contains("tipo_entidade IN")
                .contains("cortex_evento_operacional.obra_id IS NULL AND "
                        + "cortex_evento_operacional.tipo_entidade IN (?,?,?)");
        assertThat(filtro.parametros())
                .contains("obra-1", "ATIVO", "EQUIPAMENTO", "SERVICO")
                .contains("ITEM_CONTRATUAL", "PREVISAO_FINANCEIRA", "PDOR")
                .contains("SOLICITACAO_COMPRA", "COMPRA")
                .contains(
                        "UNIDADE_FINANCEIRA",
                        "RATEIO_FINANCEIRO",
                        "COMPRA_ITEM",
                        "DOCUMENTO_FISCAL"
                )
                .contains("PERMISSAO_FINANCEIRA");
    }

    @Test
    void betaRecebeEventosRelacionadosAUnidadeDeAtivoConcedida() {
        SyncService.FiltroPull filtro = service.filtroPorEscopo(
                Optional.of(Set.of()),
                Set.of(),
                Set.of("unidade-ativo-1"),
                "colaborador-1"
        );

        assertThat(filtro.condicaoSql())
                .contains("cortex_relacao")
                .contains("destino_tipo = 'UNIDADE_FINANCEIRA'")
                .contains("tipo_entidade = 'UNIDADE_FINANCEIRA'")
                .contains("entidade_id IN (?)");
        assertThat(filtro.parametros()).contains("unidade-ativo-1");
        assertThat(filtro.condicaoSql().chars().filter(value -> value == '?').count())
                .isEqualTo(filtro.parametros().size());
    }

    @Test
    void betaSemVinculoRecebeSomenteReferenciaGlobal() {
        SyncService.FiltroPull filtro =
                service.filtroPorEscopo(Optional.of(Set.of()), Set.of());

        assertThat(filtro.condicaoSql())
                .doesNotContain("cortex_evento_operacional.obra_id IN (")
                .doesNotContain(" OR (cortex_evento_operacional.tipo_entidade IN")
                .contains("cortex_evento_operacional.obra_id IS NULL AND "
                        + "cortex_evento_operacional.tipo_entidade IN (?,?,?)");
        assertThat(filtro.parametros())
                .contains("ATIVO", "EQUIPAMENTO", "SERVICO")
                .contains("PERMISSAO_FINANCEIRA");
    }

    @Test
    void betaRecebeMensagemSomenteComParticipacaoEVinculoAtuais() {
        SyncService.FiltroPull filtro = service.filtroPorEscopo(
                Optional.of(Set.of("obra-1")),
                Set.of(),
                "colaborador-1"
        );

        assertThat(filtro.condicaoSql())
                .contains("cortex_evento_visibilidade")
                .contains("CONVERSATION_PARTICIPANT")
                .contains("conversa_participante")
                .contains("vinculo_colaborador_obra")
                .contains("equipe_membro");
        assertThat(filtro.parametros())
                .contains(
                        "CONVERSA",
                        "MENSAGEM",
                        "MENSAGEM_ANEXO",
                        "colaborador-1"
                );
    }
}
