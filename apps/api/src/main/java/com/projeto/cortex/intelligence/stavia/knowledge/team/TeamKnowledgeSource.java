package com.projeto.cortex.intelligence.stavia.knowledge.team;

import com.projeto.cortex.intelligence.stavia.StaviaEngine;
import com.projeto.cortex.intelligence.stavia.intent.StaviaIntent;
import com.projeto.cortex.intelligence.stavia.interpret.StaviaEntityFilters;
import com.projeto.cortex.intelligence.stavia.knowledge.StaviaKnowledgeRequest;
import com.projeto.cortex.intelligence.stavia.knowledge.StaviaKnowledgeSource;
import com.projeto.cortex.intelligence.stavia.model.StaviaEvidence;
import com.projeto.cortex.intelligence.stavia.model.StaviaEvidenceTypes;
import com.projeto.cortex.intelligence.stavia.text.StaviaText;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class TeamKnowledgeSource
        implements StaviaKnowledgeSource {

    private static final DateTimeFormatter DATE_FORMAT =
            DateTimeFormatter.ofPattern(
                    "dd/MM/yyyy"
            );

    private final TeamReader teamReader;

    public TeamKnowledgeSource(
            TeamReader teamReader
    ) {
        this.teamReader = teamReader;
    }

    @Override
    public String sourceName() {
        return "mao-de-obra-dos-rdos";
    }

    @Override
    public String sourceVersion() {
        return "STAVIA-TEAM-SOURCE-0.1.0";
    }

    @Override
    public boolean supports(
            StaviaKnowledgeRequest request
    ) {
        if (request == null) {
            return false;
        }

        if (
                !request.permissions().contains(
                        StaviaEngine.REQUIRED_PERMISSION
                )
        ) {
            return false;
        }

        return request.intent()
                == StaviaIntent.CONSULTAR_EQUIPE
                || request.intent()
                == StaviaIntent.RESUMIR_OBRA;
    }

    @Override
    public List<StaviaEvidence> retrieve(
            StaviaKnowledgeRequest request
    ) {
        StaviaEntityFilters filters =
                StaviaEntityFilters.from(
                        request.plan().entities()
                );

        return teamReader
                .findByWorksiteId(
                        request.worksiteId()
                )
                .stream()
                .filter(this::hasUsableInformation)
                .filter(rec ->
                        filters.matchesRole(rec.role())
                        && filters.matchesCollaborator(
                                StaviaText.fallback(
                                        rec.registeredCollaboratorName(),
                                        rec.recordedName()
                                )
                        )
                )
                .map(this::toEvidence)
                .toList();
    }

    private StaviaEvidence toEvidence(
            TeamRecord record
    ) {
        Map<String, Object> attributes =
                new LinkedHashMap<>();

        putText(
                attributes,
                "obraId",
                record.worksiteId()
        );
        putText(
                attributes,
                "rdoId",
                record.rdoId()
        );
        putText(
                attributes,
                "numeroRdo",
                record.rdoNumber()
        );
        putText(
                attributes,
                "statusRdo",
                record.rdoStatus()
        );
        putText(
                attributes,
                "colaboradorId",
                record.collaboratorId()
        );
        putText(
                attributes,
                "nomeRegistrado",
                record.recordedName()
        );
        putText(
                attributes,
                "nomeColaboradorCadastrado",
                record.registeredCollaboratorName()
        );
        putText(
                attributes,
                "cargo",
                record.role()
        );
        putText(
                attributes,
                "tipoVinculo",
                record.linkType()
        );

        if (record.rdoDate() != null) {
            attributes.put(
                    "dataRdo",
                    record.rdoDate().toString()
            );
        }

        if (record.quantity() != null) {
            attributes.put(
                    "quantidade",
                    record.quantity()
            );
        }

        if (record.startTime() != null) {
            attributes.put(
                    "horaInicio",
                    record.startTime().toString()
            );
        }

        if (record.endTime() != null) {
            attributes.put(
                    "horaFim",
                    record.endTime().toString()
            );
        }

        attributes.put(
                "identificacaoIndividualConfirmada",
                hasText(record.collaboratorId())
                        && hasText(
                                record.registeredCollaboratorName()
                        )
        );

        return new StaviaEvidence(
                StaviaEvidenceTypes.EQUIPE,
                record.laborRecordId(),
                buildSummary(record),
                record.updatedAt() == null
                        ? null
                        : record.updatedAt()
                                .toInstant(
                                        ZoneOffset.UTC
                                ),
                true,
                attributes
        );
    }

    private String buildSummary(
            TeamRecord record
    ) {
        StringBuilder summary =
                new StringBuilder();

        summary.append("O RDO ")
                .append(StaviaText.fallback(
                        record.rdoNumber(),
                        record.rdoId()
                ));

        if (record.rdoDate() != null) {
            summary.append(
                    String.format(
                            ", de %s",
                            DATE_FORMAT.format(
                                    record.rdoDate()
                            )
                    )
            );
        }

        summary.append(", registrou ");

        String registeredCollaborator =
                record.registeredCollaboratorName();

        if (
                hasText(record.collaboratorId())
                && hasText(registeredCollaborator)
        ) {
            summary.append("o colaborador ")
                    .append(
                            registeredCollaborator.trim()
                    );
        } else {
            summary.append("o grupo de mão de obra ")
                    .append(StaviaText.fallback(
                            record.recordedName(),
                            "sem identificação nominal"
                    ));
        }

        if (hasText(record.role())) {
            summary.append(", com cargo ")
                    .append(record.role().trim());
        }

        if (record.quantity() != null) {
            summary.append(
                    String.format(
                            ", quantidade %s",
                            formatQuantity(
                                    record.quantity()
                            )
                    )
            );
        }

        if (hasText(record.linkType())) {
            summary.append(
                    String.format(
                            " e vínculo %s",
                            record.linkType().trim()
                    )
            );
        }

        if (!hasText(record.collaboratorId())) {
            summary.append(
                    ". O registro não identifica "
                            + "individualmente os trabalhadores"
            );
        }

        return summary.append(".").toString();
    }

    private boolean hasUsableInformation(
            TeamRecord record
    ) {
        return record != null
                && (
                    hasText(record.recordedName())
                    || hasText(
                            record.registeredCollaboratorName()
                    )
                    || record.quantity() != null
                );
    }

    private String formatQuantity(
            BigDecimal quantity
    ) {
        return quantity
                .stripTrailingZeros()
                .toPlainString();
    }

    private void putText(
            Map<String, Object> attributes,
            String key,
            String value
    ) {
        if (hasText(value)) {
            attributes.put(
                    key,
                    value.trim()
            );
        }
    }

    private boolean hasText(
            String value
    ) {
        return value != null
                && !value.isBlank();
    }
}
