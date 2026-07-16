package com.projeto.cortex.ontology;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OperationalMemoryQueryServiceTest {

    private final OperationalMemoryQueryService service =
            new OperationalMemoryQueryService(
                    mock(JdbcTemplate.class),
                    new ObjectMapper()
            );

    @Test
    void betaQueryRestrictsAuthorizedWorksitesAndSelfGlobalEvents() {
        OperationalMemoryScope scope = OperationalMemoryScope.beta(
                "beta-1",
                Set.of("obra-2", "obra-1")
        );

        OperationalMemoryQueryService.QuerySpec query = service.buildQuery(
                scope,
                OperationalMemoryFilter.empty(),
                50,
                null
        );

        assertThat(query.sql()).contains("e.obra_id IN (?, ?)");
        assertThat(query.sql()).contains(
                "e.obra_id IS NULL AND e.usuario_id = ?"
        );
        assertThat(query.parameters())
                .containsSequence("obra-1", "obra-2", "beta-1")
                .endsWith(51);
    }

    @Test
    void cursorIsExclusiveAndFiltersAreBoundBeforeLimit() {
        OperationalMemoryFilter filter = new OperationalMemoryFilter(
                "rdo",
                "rdo-1",
                "obra-1",
                null,
                "ator-1",
                "rdo_editado",
                "online",
                "sucesso",
                LocalDateTime.of(2026, 7, 1, 0, 0),
                LocalDateTime.of(2026, 7, 16, 23, 59)
        );

        OperationalMemoryQueryService.QuerySpec query = service.buildQuery(
                OperationalMemoryScope.alfa("alfa"),
                filter,
                2,
                99L
        );

        assertThat(query.sql()).contains("e.commit_seq < ?");
        assertThat(query.sql()).contains(
                "ORDER BY e.commit_seq DESC LIMIT ?"
        );
        assertThat(query.parameters()).contains(
                "RDO",
                "rdo-1",
                "obra-1",
                "ator-1",
                "RDO_EDITADO",
                "ONLINE",
                "SUCESSO",
                99L
        ).endsWith(3);
    }

    @Test
    void mapEventPreservesActorTraceAndStateWithoutInference() throws Exception {
        ResultSet resultSet = mock(ResultSet.class);
        LocalDateTime occurredAt = LocalDateTime.of(2026, 7, 16, 14, 30);

        when(resultSet.getString("id")).thenReturn("event-1");
        when(resultSet.getObject("commit_seq", Long.class)).thenReturn(42L);
        when(resultSet.getString("tipo_evento")).thenReturn("RDO_EDITADO");
        when(resultSet.getString("fonte")).thenReturn("RdoService");
        when(resultSet.getString("tipo_entidade")).thenReturn("RDO");
        when(resultSet.getString("entidade_id")).thenReturn("rdo-1");
        when(resultSet.getString("entidades_relacionadas_json"))
                .thenReturn("[{\"tipo\":\"OBRA\",\"id\":\"obra-1\"}]");
        when(resultSet.getString("obra_id")).thenReturn("obra-1");
        when(resultSet.getString("rdo_id")).thenReturn("rdo-1");
        when(resultSet.getString("colaborador_id")).thenReturn("colab-2");
        when(resultSet.getTimestamp("ocorrido_em"))
                .thenReturn(Timestamp.valueOf(occurredAt));
        when(resultSet.getTimestamp("sincronizado_em")).thenReturn(null);
        when(resultSet.getString("origem")).thenReturn("ONLINE");
        when(resultSet.getString("sync_status")).thenReturn("SYNCED");
        when(resultSet.getObject("schema_version", Integer.class)).thenReturn(2);
        when(resultSet.getString("usuario_id")).thenReturn("actor-1");
        when(resultSet.getString("actor_name")).thenReturn("Ana Lima");
        when(resultSet.getString("dispositivo_id")).thenReturn("device-1");
        when(resultSet.getString("correlacao_id")).thenReturn("corr-1");
        when(resultSet.getString("causacao_id")).thenReturn("cause-1");
        when(resultSet.getString("estado_anterior_json"))
                .thenReturn("{\"status\":\"RASCUNHO\"}");
        when(resultSet.getString("estado_novo_json"))
                .thenReturn("{\"status\":\"ENVIADO\"}");
        when(resultSet.getString("resultado")).thenReturn("SUCESSO");
        when(resultSet.getString("erro_categoria")).thenReturn(null);
        when(resultSet.getString("payload_json"))
                .thenReturn("{\"numeroRdo\":\"RDO-1\"}");

        OperationalMemoryEventResponse event = service.mapEvent(resultSet, 0);

        assertThat(event.actorId()).isEqualTo("actor-1");
        assertThat(event.actorName()).isEqualTo("Ana Lima");
        assertThat(event.source()).isEqualTo("RdoService");
        assertThat(event.correlationId()).isEqualTo("corr-1");
        assertThat(event.previousState().get("status").asText())
                .isEqualTo("RASCUNHO");
        assertThat(event.newState().get("status").asText())
                .isEqualTo("ENVIADO");
        assertThat(event.payload().get("numeroRdo").asText())
                .isEqualTo("RDO-1");
        assertThat(event.occurredAt()).isEqualTo(occurredAt);
    }

    @Test
    void pageUsesExtraRowOnlyAsHasMoreEvidence() {
        OperationalMemoryEventResponse first = event(12L, "e-12");
        OperationalMemoryEventResponse second = event(11L, "e-11");
        OperationalMemoryEventResponse extra = event(10L, "e-10");

        OperationalMemoryPageResponse page = service.page(
                List.of(first, second, extra),
                2,
                "GLOBAL"
        );

        assertThat(page.events()).containsExactly(first, second);
        assertThat(page.hasMore()).isTrue();
        assertThat(page.nextBeforeCommitSeq()).isEqualTo(11L);
        assertThat(page.scope()).isEqualTo("GLOBAL");
    }

    private OperationalMemoryEventResponse event(Long commitSeq, String id) {
        return new OperationalMemoryEventResponse(
                id,
                commitSeq,
                "RDO_EDITADO",
                "test",
                "RDO",
                "rdo-1",
                new ObjectMapper().createArrayNode(),
                "obra-1",
                "rdo-1",
                null,
                LocalDateTime.of(2026, 7, 16, 10, 0),
                null,
                "ONLINE",
                "SYNCED",
                1,
                null,
                null,
                null,
                null,
                null,
                new ObjectMapper().createObjectNode(),
                new ObjectMapper().createObjectNode(),
                "SUCESSO",
                null,
                new ObjectMapper().createObjectNode()
        );
    }
}
