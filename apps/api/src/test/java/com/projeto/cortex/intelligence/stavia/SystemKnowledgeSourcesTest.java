package com.projeto.cortex.intelligence.stavia;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.projeto.cortex.intelligence.stavia.intent.StaviaIntent;
import com.projeto.cortex.intelligence.stavia.knowledge.StaviaKnowledgeRequest;
import com.projeto.cortex.intelligence.stavia.knowledge.geospatial.GeospatialKnowledgeRecord;
import com.projeto.cortex.intelligence.stavia.knowledge.geospatial.GeospatialKnowledgeSource;
import com.projeto.cortex.intelligence.stavia.knowledge.messaging.MessageKnowledgeRecord;
import com.projeto.cortex.intelligence.stavia.knowledge.messaging.MessageKnowledgeSource;
import com.projeto.cortex.intelligence.stavia.knowledge.team.ConfiguredTeamKnowledgeSource;
import com.projeto.cortex.intelligence.stavia.knowledge.team.ConfiguredTeamRecord;
import com.projeto.cortex.intelligence.stavia.model.StaviaEvidence;
import com.projeto.cortex.intelligence.stavia.model.StaviaEvidenceTypes;
import com.projeto.cortex.intelligence.stavia.model.StaviaQuestion;
import com.projeto.cortex.intelligence.stavia.planning.QueryDomain;
import com.projeto.cortex.intelligence.stavia.planning.QueryOperation;
import com.projeto.cortex.intelligence.stavia.planning.ResolvedEntity;
import com.projeto.cortex.intelligence.stavia.planning.StaviaQueryPlan;
import com.projeto.cortex.intelligence.stavia.planning.TemporalFilter;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class SystemKnowledgeSourcesTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void shouldExposePersistedGeometryWithoutInventingMapData() {
        GeospatialKnowledgeSource source = new GeospatialKnowledgeSource(
                worksiteId -> List.of(new GeospatialKnowledgeRecord(
                        "geo-1", worksiteId, "FRENTE_TRABALHO", "POINT",
                        "RDO", "rdo-1", "{\"label\":\"Frente norte\"}",
                        "OPERACAO", LocalDateTime.parse("2026-07-01T08:00:00"),
                        LocalDateTime.parse("2026-07-01T09:00:00")
                )),
                objectMapper
        );

        StaviaEvidence evidence = source.retrieve(mapRequest()).getFirst();

        assertThat(evidence.type()).isEqualTo(StaviaEvidenceTypes.GEOMETRIA);
        assertThat(evidence.attributes())
                .containsEntry("geometriaId", "geo-1")
                .containsEntry("objetoId", "rdo-1")
                .containsEntry("permissaoAplicada", StaviaEngine.REQUIRED_PERMISSION);
    }

    @Test
    void shouldRetrieveMessagesOnlyForTheRequestUserPassedToAuthorizedReader() {
        AtomicReference<String> requestedUser = new AtomicReference<>();
        MessageKnowledgeSource source = new MessageKnowledgeSource(
                (worksiteId, userId) -> {
                    requestedUser.set(userId);
                    return List.of(new MessageKnowledgeRecord(
                            "msg-1", "conv-1", "OBRA", "Operação", worksiteId,
                            "sender-1", "Maria", "Liberar frente após inspeção.",
                            LocalDateTime.parse("2026-07-02T10:00:00"),
                            "[{\"tipoObjeto\":\"RDO\",\"objetoId\":\"rdo-1\"}]",
                            "[{\"anexoId\":\"anexo-1\",\"nome\":\"foto.jpg\"}]"
                    ));
                },
                objectMapper
        );

        StaviaEvidence evidence = source.retrieve(messageRequest()).getFirst();

        assertThat(requestedUser).hasValue("usuario-1");
        assertThat(evidence.type()).isEqualTo(StaviaEvidenceTypes.MENSAGEM);
        assertThat(evidence.attributes())
                .containsEntry("mensagemId", "msg-1")
                .containsEntry("permissaoAplicada", "PARTICIPANTE_ATIVO_E_ACESSO_A_OBRA");
    }

    @Test
    void shouldExposeConfiguredTeamMembershipAndAssignmentProvenance() {
        ConfiguredTeamKnowledgeSource source = new ConfiguredTeamKnowledgeSource(
                worksiteId -> List.of(new ConfiguredTeamRecord(
                        "team-1", "Equipe Norte", worksiteId, "membership-1",
                        "beta-1", "Pessoa Beta", "function-1", "Apontador",
                        true, "alfa-1", "Pessoa Alfa",
                        LocalDateTime.parse("2026-07-01T08:00:00"),
                        LocalDateTime.parse("2026-07-01T08:00:00")
                ))
        );

        StaviaEvidence evidence = source.retrieve(teamRequest()).getFirst();

        assertThat(evidence.type()).isEqualTo(StaviaEvidenceTypes.EQUIPE);
        assertThat(evidence.summary()).contains("Pessoa Beta", "Apontador", "responsável");
        assertThat(evidence.attributes())
                .containsEntry("atribuidoPorId", "alfa-1")
                .containsEntry("atribuidoEm", "2026-07-01T08:00");
    }

    @Test
    void shouldFilterConfiguredMembershipsByResolvedRole() {
        ConfiguredTeamKnowledgeSource source = new ConfiguredTeamKnowledgeSource(
                worksiteId -> List.of(
                        configuredMember(worksiteId, "member-1", "Pessoa Beta", "Apontador"),
                        configuredMember(worksiteId, "member-2", "Pessoa Gama", "Encarregado")
                )
        );
        StaviaQueryPlan rolePlan = new StaviaQueryPlan(
                QueryDomain.EQUIPE,
                QueryOperation.LIST_OBJECTS,
                List.of(ResolvedEntity.roleByLabel("Apontador")),
                TemporalFilter.none(),
                List.of(), List.of(), List.of(), List.of(),
                true, false, false
        );
        StaviaKnowledgeRequest request = new StaviaKnowledgeRequest(
                new StaviaQuestion("Quem e apontador?", "usuario-1", "obra-1"),
                StaviaIntent.CONSULTAR_EQUIPE,
                "obra-1",
                Set.of(StaviaEngine.REQUIRED_PERMISSION),
                rolePlan
        );

        List<StaviaEvidence> evidences = source.retrieve(request);

        assertThat(evidences).singleElement()
                .satisfies(evidence -> assertThat(evidence.attributes())
                        .containsEntry("colaboradorNome", "Pessoa Beta")
                        .containsEntry("funcaoNome", "Apontador"));
    }

    @Test
    void shouldRejectSourcesWithoutStaviaPermission() {
        StaviaKnowledgeRequest unauthorized = new StaviaKnowledgeRequest(
                new StaviaQuestion("Mostre o mapa.", "usuario-1", "obra-1"),
                StaviaIntent.CONSULTAR_OBRA,
                "obra-1",
                Set.of()
        );
        GeospatialKnowledgeSource source = new GeospatialKnowledgeSource(
                worksiteId -> List.of(), objectMapper
        );

        assertThat(source.supports(unauthorized)).isFalse();
    }

    private StaviaKnowledgeRequest mapRequest() {
        return request("Mostre o mapa da obra.", StaviaIntent.CONSULTAR_OBRA);
    }

    private StaviaKnowledgeRequest messageRequest() {
        return request("Resuma as mensagens da conversa.", StaviaIntent.CONSULTAR_HISTORICO);
    }

    private StaviaKnowledgeRequest teamRequest() {
        return request("Quem está na equipe?", StaviaIntent.CONSULTAR_EQUIPE);
    }

    private ConfiguredTeamRecord configuredMember(
            String worksiteId,
            String membershipId,
            String collaboratorName,
            String functionName
    ) {
        return new ConfiguredTeamRecord(
                "team-1", "Equipe Norte", worksiteId, membershipId,
                "collaborator-" + membershipId, collaboratorName,
                "function-" + functionName, functionName,
                false, "alfa-1", "Pessoa Alfa",
                LocalDateTime.parse("2026-07-01T08:00:00"),
                LocalDateTime.parse("2026-07-01T08:00:00")
        );
    }

    private StaviaKnowledgeRequest request(String text, StaviaIntent intent) {
        return new StaviaKnowledgeRequest(
                new StaviaQuestion(text, "usuario-1", "obra-1"),
                intent,
                "obra-1",
                Set.of(StaviaEngine.REQUIRED_PERMISSION)
        );
    }
}
