package com.projeto.cortex.intelligence.stavia;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.projeto.cortex.intelligence.stavia.intent.StaviaIntent;
import com.projeto.cortex.intelligence.stavia.knowledge.StaviaKnowledgeRequest;
import com.projeto.cortex.intelligence.stavia.knowledge.pdor.PdorKnowledgeSource;
import com.projeto.cortex.intelligence.stavia.model.StaviaEvidence;
import com.projeto.cortex.intelligence.stavia.model.StaviaEvidenceTypes;
import com.projeto.cortex.intelligence.stavia.model.StaviaQuestion;
import com.projeto.cortex.obras.Obra;
import com.projeto.cortex.obras.ObraRepository;
import com.projeto.cortex.pdor.PdorExecutionStatus;
import com.projeto.cortex.pdor.PdorSnapshot;
import com.projeto.cortex.pdor.PdorSnapshotRepository;
import com.projeto.cortex.pdor.PdorTriggerType;

class PdorKnowledgeSourceTest {

    private final ObjectMapper objectMapper =
            new ObjectMapper();

    @Test
    void shouldConvertRealInsufficientDataSnapshotIntoEvidence() {
        Obra obra = obra();
        PdorSnapshotRepository snapshotRepository =
                mock(PdorSnapshotRepository.class);
        ObraRepository obraRepository =
                mock(ObraRepository.class);

        when(obraRepository.findAtivasByIdentificador("obra-1"))
                .thenReturn(List.of(obra));
        when(snapshotRepository.findLatestByObraId(obra.getId()))
                .thenReturn(
                        Optional.of(
                                snapshot(
                                        obra,
                                        PdorExecutionStatus.INSUFFICIENT_DATA
                                )
                        )
                );

        PdorKnowledgeSource source =
                new PdorKnowledgeSource(
                        snapshotRepository,
                        obraRepository
                );

        List<StaviaEvidence> evidences =
                source.retrieve(request());

        assertThat(evidences).hasSize(1);

        StaviaEvidence evidence =
                evidences.getFirst();

        assertThat(evidence.type())
                .isEqualTo(StaviaEvidenceTypes.PDOR);
        assertThat(evidence.summary())
                .contains("dados insuficientes");
        assertThat(evidence.validated()).isTrue();
        assertThat(evidence.attributes())
                .containsEntry("statusExecucao", "INSUFFICIENT_DATA")
                .containsEntry("codigoCw", "CW38386")
                .containsEntry("sourceMode", "OPERATIONAL");
        @SuppressWarnings("unchecked")
        List<String> missing =
                (List<String>) evidence.attributes()
                        .get("missingRequiredFields");

        assertThat(missing).contains(
                "approvedBudget",
                "actualCost",
                "committedCost",
                "actualExecutedQuantity"
        );
    }

    @Test
    void shouldExposeCalculatedSnapshotMetrics() {
        Obra obra = obra();
        PdorSnapshotRepository snapshotRepository =
                mock(PdorSnapshotRepository.class);
        ObraRepository obraRepository =
                mock(ObraRepository.class);

        when(obraRepository.findAtivasByIdentificador("obra-1"))
                .thenReturn(List.of(obra));
        when(snapshotRepository.findLatestByObraId(obra.getId()))
                .thenReturn(
                        Optional.of(
                                snapshot(
                                        obra,
                                        PdorExecutionStatus.SUCCESS
                                )
                        )
                );

        PdorKnowledgeSource source =
                new PdorKnowledgeSource(
                        snapshotRepository,
                        obraRepository
                );

        StaviaEvidence evidence =
                source.retrieve(request()).getFirst();

        assertThat(evidence.attributes())
                .containsEntry("statusExecucao", "SUCCESS")
                .containsEntry(
                        "costP50",
                        new BigDecimal("110.00")
                )
                .containsEntry(
                        "probabilityOverFivePercent",
                        new BigDecimal("0.250000")
                );
    }

    @Test
    void shouldReturnOperationalNoSnapshotEvidence() {
        Obra obra = obra();
        PdorSnapshotRepository snapshotRepository =
                mock(PdorSnapshotRepository.class);
        ObraRepository obraRepository =
                mock(ObraRepository.class);

        when(obraRepository.findAtivasByIdentificador("obra-1"))
                .thenReturn(List.of(obra));
        when(snapshotRepository.findLatestByObraId(obra.getId()))
                .thenReturn(Optional.empty());

        PdorKnowledgeSource source =
                new PdorKnowledgeSource(
                        snapshotRepository,
                        obraRepository
                );

        StaviaEvidence evidence =
                source.retrieve(request()).getFirst();

        assertThat(evidence.type())
                .isEqualTo(StaviaEvidenceTypes.PDOR);
        assertThat(evidence.validated()).isFalse();
        assertThat(evidence.attributes())
                .containsEntry("statusExecucao", "NO_SNAPSHOT");
        assertThat(evidence.summary())
                .contains("Nenhum snapshot PDOR");
    }

    @Test
    void shouldOnlySupportAuthorizedRelevantIntents() {
        PdorKnowledgeSource source =
                new PdorKnowledgeSource(
                        mock(PdorSnapshotRepository.class),
                        mock(ObraRepository.class)
                );

        assertThat(source.supports(request())).isTrue();

        StaviaKnowledgeRequest unauthorized =
                new StaviaKnowledgeRequest(
                        new StaviaQuestion(
                                "Qual é o risco de custo?",
                                "usuario-1",
                                "obra-1"
                        ),
                        StaviaIntent.CONSULTAR_PDOR,
                        "obra-1",
                        Set.of()
                );

        assertThat(source.supports(unauthorized)).isFalse();

        StaviaKnowledgeRequest summaryRequest =
                new StaviaKnowledgeRequest(
                        new StaviaQuestion(
                                "Resuma esta obra.",
                                "usuario-1",
                                "obra-1"
                        ),
                        StaviaIntent.RESUMIR_OBRA,
                        "obra-1",
                        Set.of(StaviaEngine.REQUIRED_PERMISSION)
                );

        assertThat(source.supports(summaryRequest)).isFalse();
    }

    private StaviaKnowledgeRequest request() {
        return new StaviaKnowledgeRequest(
                new StaviaQuestion(
                        "Qual é o risco de custo da obra?",
                        "usuario-1",
                        "obra-1"
                ),
                StaviaIntent.CONSULTAR_PDOR,
                "obra-1",
                Set.of(StaviaEngine.REQUIRED_PERMISSION)
        );
    }

    private Obra obra() {
        return Obra.criar(
                "CW38386",
                "CW38386",
                null,
                "4ª Intervenção",
                "Cliente",
                null,
                "São Paulo",
                "SP",
                "SP-000",
                "ATIVA",
                "TESTE",
                null,
                null
        );
    }

    private PdorSnapshot snapshot(
            Obra obra,
            PdorExecutionStatus status
    ) {
        boolean success = status == PdorExecutionStatus.SUCCESS;

        return new PdorSnapshot(
                "snapshot-1",
                obra.getId(),
                "CW38386",
                LocalDateTime.of(2026, 6, 25, 10, 0),
                LocalDate.of(2026, 6, 8),
                "PDOR-0.2.0",
                "PDOR-ASSUMPTIONS-0.2.0",
                status,
                PdorTriggerType.API,
                null,
                "idem-1",
                objectMapper.createObjectNode()
                        .putNull("approvedBudget")
                        .putNull("actualCost")
                        .putNull("committedCost")
                        .putNull("actualExecutedQuantity"),
                originsNode(),
                objectMapper.createArrayNode()
                        .add("Orçamento total aprovado ausente."),
                success ? "DETERMINISTIC" : null,
                success ? "CALIBRATED" : null,
                success ? "PRODUCTION" : null,
                success ? "MODERATE" : null,
                success ? new BigDecimal("100.00") : null,
                success ? new BigDecimal("110.00") : null,
                success ? new BigDecimal("120.00") : null,
                success ? new BigDecimal("130.00") : null,
                null,
                null,
                null,
                null,
                null,
                null,
                success ? new BigDecimal("0.300000") : null,
                success ? new BigDecimal("0.250000") : null,
                success ? new BigDecimal("0.150000") : null,
                success ? new BigDecimal("0.420000") : null,
                success ? new BigDecimal("0.800000") : null,
                success,
                success ? 10000 : null,
                objectMapper.createArrayNode(),
                success ? null : "Dados insuficientes.",
                LocalDateTime.of(2026, 6, 25, 10, 0)
        );
    }

    private ObjectNode originsNode() {
        ObjectNode origins = objectMapper.createObjectNode();
        origins.set("approvedBudget", missingOrigin());
        origins.set("actualCost", missingOrigin());
        origins.set("committedCost", missingOrigin());
        origins.set("actualExecutedQuantity", missingOrigin());
        return origins;
    }

    private ObjectNode missingOrigin() {
        return objectMapper.createObjectNode()
                .put("availability", "ABSENT")
                .put("required", true);
    }
}
