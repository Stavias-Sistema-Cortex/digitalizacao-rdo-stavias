package com.projeto.cortex.intelligence.stavia.knowledge.worksite;

import com.projeto.cortex.intelligence.stavia.StaviaEngine;
import com.projeto.cortex.intelligence.stavia.intent.StaviaIntent;
import com.projeto.cortex.intelligence.stavia.knowledge.StaviaKnowledgeRequest;
import com.projeto.cortex.intelligence.stavia.knowledge.StaviaKnowledgeSource;
import com.projeto.cortex.intelligence.stavia.knowledge.registry.StaviaSourceDescriptor;
import com.projeto.cortex.intelligence.stavia.model.StaviaEvidence;
import com.projeto.cortex.intelligence.stavia.model.StaviaEvidenceTypes;
import com.projeto.cortex.intelligence.stavia.planning.QueryDomain;
import com.projeto.cortex.intelligence.stavia.planning.QueryOperation;
import com.projeto.cortex.intelligence.stavia.text.StaviaText;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
public class WorksiteKnowledgeSource
        implements StaviaKnowledgeSource {

    private static final DateTimeFormatter DATE_FORMAT =
            DateTimeFormatter.ofPattern(
                    "dd/MM/yyyy 'às' HH:mm"
            );

    private final WorksiteReader worksiteReader;

    public WorksiteKnowledgeSource(
            WorksiteReader worksiteReader
    ) {
        this.worksiteReader = worksiteReader;
    }

    @Override
    public String sourceName() {
        return "cadastro-de-obras";
    }

    @Override
    public String sourceVersion() {
        return "STAVIA-WORKSITE-SOURCE-0.2.0";
    }

    @Override
    public StaviaSourceDescriptor descriptor() {
        return new StaviaSourceDescriptor(
                sourceName(),
                sourceVersion(),
                Set.of(QueryDomain.OBRA),
                Set.of(StaviaEvidenceTypes.OBRA),
                Set.of(
                        "cidade",
                        "uf",
                        "rodovia",
                        "cliente",
                        "nome",
                        "status",
                        "codigo",
                        "codigoCw",
                        "codigoContrato",
                        "codigoInterno"
                ),
                Set.of(),
                Set.of(
                        QueryOperation.READ_ATTRIBUTE,
                        QueryOperation.SUMMARIZE
                ),
                Set.of(StaviaEngine.REQUIRED_PERMISSION),
                "Cadastro local de obras no Córtex",
                5,
                1
        );
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

        boolean plannedWorksiteAttribute =
                request.plan() != null
                        && request.plan().planned()
                        && request.plan().domain() == QueryDomain.OBRA
                        && request.plan().operation()
                                == QueryOperation.READ_ATTRIBUTE;

        return plannedWorksiteAttribute
                || request.intent()
                == StaviaIntent.CONSULTAR_OBRA
                || request.intent()
                == StaviaIntent.CONSULTAR_ESTADO_ATUAL
                || request.intent()
                == StaviaIntent.RESUMIR_OBRA;
    }

    @Override
    public List<StaviaEvidence> retrieve(
            StaviaKnowledgeRequest request
    ) {
        return worksiteReader
                .findById(request.worksiteId())
                .map(this::toEvidence)
                .map(List::of)
                .orElseGet(List::of);
    }

    private StaviaEvidence toEvidence(
            WorksiteSnapshot obra
    ) {
        Map<String, Object> attributes =
                new LinkedHashMap<>();

        putText(attributes, "obraId", obra.id());
        putText(
                attributes,
                "codigoContrato",
                obra.codigoContrato()
        );
        putText(
                attributes,
                "codigoCw",
                obra.codigoCw()
        );
        putText(
                attributes,
                "codigoInterno",
                obra.codigoInterno()
        );
        putText(attributes, "nome", obra.nome());
        putText(
                attributes,
                "cliente",
                obra.cliente()
        );
        putText(
                attributes,
                "descricao",
                obra.descricao()
        );
        putText(
                attributes,
                "cidade",
                obra.cidade()
        );
        putText(attributes, "uf", obra.uf());
        putText(
                attributes,
                "rodovia",
                obra.rodovia()
        );
        putText(
                attributes,
                "status",
                obra.status()
        );
        putText(
                attributes,
                "fonteCriacao",
                obra.fonteCriacao()
        );

        putDate(
                attributes,
                "criadoEm",
                obra.criadoEm()
        );
        putDate(
                attributes,
                "atualizadoEm",
                obra.atualizadoEm()
        );
        putDate(
                attributes,
                "arquivadoEm",
                obra.arquivadoEm()
        );

        attributes.put(
                "versaoLinha",
                obra.versaoLinha()
        );

        LocalDateTime evidenceDate =
                obra.atualizadoEm() != null
                        ? obra.atualizadoEm()
                        : obra.criadoEm();

        return new StaviaEvidence(
                StaviaEvidenceTypes.OBRA,
                obra.id(),
                buildSummary(obra),
                evidenceDate == null
                        ? null
                        : evidenceDate.toInstant(
                                ZoneOffset.UTC
                        ),
                true,
                attributes
        );
    }

    private String buildSummary(
            WorksiteSnapshot obra
    ) {
        StringBuilder summary =
                new StringBuilder();

        summary.append("A obra ")
                .append(StaviaText.fallback(
                        obra.nome(),
                        obra.id()
                ));

        appendText(
                summary,
                " possui código CW ",
                obra.codigoCw()
        );

        appendText(
                summary,
                ", contrato ",
                obra.codigoContrato()
        );

        appendText(
                summary,
                ", código interno ",
                obra.codigoInterno()
        );

        appendText(
                summary,
                ", status ",
                obra.status()
        );

        appendText(
                summary,
                ", cliente ",
                obra.cliente()
        );

        String location =
                buildLocation(obra);

        if (!location.isBlank()) {
            summary.append(", localização ")
                    .append(location);
        }

        appendText(
                summary,
                ", rodovia ",
                obra.rodovia()
        );

        if (obra.criadoEm() != null) {
            summary.append(
                    ", foi cadastrada no Córtex em "
            ).append(
                    DATE_FORMAT.format(
                            obra.criadoEm()
                    )
            );
        }

        if (obra.atualizadoEm() != null) {
            summary.append(
                    " e teve sua última atualização em "
            ).append(
                    DATE_FORMAT.format(
                            obra.atualizadoEm()
                    )
            );
        }

        summary.append(
                ". As datas de início e término "
                        + "operacional não estão registradas "
                        + "no cadastro atual da obra."
        );

        return summary.toString();
    }

    private String buildLocation(
            WorksiteSnapshot obra
    ) {
        boolean hasCity = hasText(obra.cidade());
        boolean hasState = hasText(obra.uf());

        if (hasCity && hasState) {
            return obra.cidade().trim()
                    + "/"
                    + obra.uf().trim();
        }

        if (hasCity) {
            return obra.cidade().trim();
        }

        if (hasState) {
            return obra.uf().trim();
        }

        return "";
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

    private void putDate(
            Map<String, Object> attributes,
            String key,
            LocalDateTime value
    ) {
        if (value != null) {
            attributes.put(
                    key,
                    value.toString()
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
