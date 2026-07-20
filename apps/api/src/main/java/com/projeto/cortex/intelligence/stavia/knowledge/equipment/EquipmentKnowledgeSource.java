package com.projeto.cortex.intelligence.stavia.knowledge.equipment;

import com.projeto.cortex.intelligence.stavia.StaviaEngine;
import com.projeto.cortex.intelligence.stavia.intent.StaviaIntent;
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
public class EquipmentKnowledgeSource
        implements StaviaKnowledgeSource {

    private static final DateTimeFormatter DATE_FORMAT =
            DateTimeFormatter.ofPattern(
                    "dd/MM/yyyy"
            );

    private final EquipmentReader equipmentReader;

    public EquipmentKnowledgeSource(
            EquipmentReader equipmentReader
    ) {
        this.equipmentReader = equipmentReader;
    }

    @Override
    public String sourceName() {
        return "equipamentos-dos-rdos";
    }

    @Override
    public String sourceVersion() {
        return "STAVIA-EQUIPMENT-SOURCE-0.1.0";
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
                == StaviaIntent.CONSULTAR_ATIVO
                || request.intent()
                == StaviaIntent.RESUMIR_OBRA;
    }

    @Override
    public List<StaviaEvidence> retrieve(
            StaviaKnowledgeRequest request
    ) {
        return equipmentReader
                .findByWorksiteId(
                        request.worksiteId()
                )
                .stream()
                .filter(this::hasUsableInformation)
                .map(this::toEvidence)
                .toList();
    }

    private StaviaEvidence toEvidence(
            EquipmentRecord record
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
                "assetId",
                record.assetId()
        );
        putText(
                attributes,
                "prefixoRegistrado",
                record.recordedPrefix()
        );
        putText(
                attributes,
                "descricaoRegistrada",
                record.recordedDescription()
        );
        putText(
                attributes,
                "tipoRegistrado",
                record.recordedType()
        );
        putText(
                attributes,
                "tipoVinculo",
                record.linkType()
        );
        putText(
                attributes,
                "observacoes",
                record.observations()
        );
        putText(
                attributes,
                "codigoAtivoCadastrado",
                record.registeredExternalCode()
        );
        putText(
                attributes,
                "nomeAtivoCadastrado",
                record.registeredName()
        );
        putText(
                attributes,
                "categoriaAtivoCadastrado",
                record.registeredCategory()
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

        if (record.registeredActive() != null) {
            attributes.put(
                    "ativoNoCadastro",
                    record.registeredActive()
            );
        }

        boolean registeredAssetConfirmed =
                hasText(record.assetId())
                        && hasText(
                                record.registeredName()
                        );

        attributes.put(
                "vinculoComCadastroConfirmado",
                registeredAssetConfirmed
        );

        return new StaviaEvidence(
                StaviaEvidenceTypes.EQUIPAMENTO,
                record.equipmentRecordId(),
                buildSummary(
                        record,
                        registeredAssetConfirmed
                ),
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
            EquipmentRecord record,
            boolean registeredAssetConfirmed
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

        summary.append(
                ", registrou "
        );

        if (record.quantity() != null) {
            summary.append("equipamento, quantidade ")
                    .append(
                            formatQuantity(
                                    record.quantity()
                            )
                    )
                    .append(",");
        } else {
            summary.append("equipamento");
        }

        appendText(
                summary,
                " com prefixo ",
                record.recordedPrefix()
        );
        appendText(
                summary,
                ", descrição ",
                record.recordedDescription()
        );
        appendText(
                summary,
                ", tipo ",
                record.recordedType()
        );
        appendText(
                summary,
                " e vínculo ",
                record.linkType()
        );

        if (registeredAssetConfirmed) {
            summary.append(
                    ". O registro está vinculado ao ativo cadastrado "
            ).append(
                    record.registeredName().trim()
            );

            appendText(
                    summary,
                    ", código ",
                    record.registeredExternalCode()
            );
        } else {
            summary.append(
                    ". O registro não está vinculado "
                            + "a um ativo identificado no cadastro"
            );
        }

        summary.append(
                ". O RDO não confirma localização "
                        + "nem operação atual do equipamento."
        );

        return summary.toString();
    }

    private boolean hasUsableInformation(
            EquipmentRecord record
    ) {
        return record != null
                && (
                    hasText(record.assetId())
                    || hasText(record.recordedPrefix())
                    || hasText(record.recordedDescription())
                    || hasText(record.recordedType())
                );
    }

    private String formatQuantity(
            BigDecimal quantity
    ) {
        return quantity
                .stripTrailingZeros()
                .toPlainString();
    }

    private void appendText(
            StringBuilder builder,
            String prefix,
            String value
    ) {
        if (hasText(value)) {
            builder.append(prefix)
                    .append(value.trim());
        }
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
