package com.projeto.cortex.intelligence.stavia.generation;

import com.projeto.cortex.intelligence.stavia.intent.StaviaIntent;
import com.projeto.cortex.intelligence.stavia.model.StaviaAnswerType;
import com.projeto.cortex.intelligence.stavia.model.StaviaEvidence;
import com.projeto.cortex.intelligence.stavia.model.StaviaEvidenceKeys;
import com.projeto.cortex.intelligence.stavia.model.StaviaEvidenceTypes;
import com.projeto.cortex.intelligence.stavia.model.StaviaQuestion;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.text.Normalizer;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

@Component
@ConditionalOnProperty(
        prefix = "cortex.stavia",
        name = "generator-mode",
        havingValue = "deterministic"
)
public class DeterministicStaviaResponseGenerator
        implements StaviaResponseGenerator {

    private static final ZoneId BUSINESS_ZONE =
            ZoneId.of("America/Sao_Paulo");

    private static final DateTimeFormatter DATE_FORMAT =
            DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private static final DateTimeFormatter DATE_TIME_FORMAT =
            DateTimeFormatter.ofPattern("dd/MM/yyyy 'às' HH:mm");

    @Override
    public StaviaGeneratedResponse generate(
            StaviaQuestion question,
            StaviaIntent intent,
            List<StaviaEvidence> evidences
    ) {
        if (question == null) {
            throw new IllegalArgumentException(
                    "A pergunta deve ser informada ao gerador."
            );
        }

        if (intent == null) {
            throw new IllegalArgumentException(
                    "A intenção deve ser informada ao gerador."
            );
        }

        if (evidences == null || evidences.isEmpty()) {
            throw new IllegalArgumentException(
                    "O gerador precisa de pelo menos uma evidência."
            );
        }

        List<StaviaEvidence> focusedEvidence =
                focusEvidence(
                        question,
                        intent,
                        evidences
                );

        if (intent == StaviaIntent.CONSULTAR_RDO) {
            if (hasEvidenceType(
                    focusedEvidence,
                    StaviaEvidenceTypes.TRECHO_OPERACIONAL
            )) {
                return buildResponse(
                        buildSegmentAnswer(question, focusedEvidence),
                        StaviaAnswerType.FATO,
                        focusedEvidence
                );
            }

            if (hasEvidenceType(
                    focusedEvidence,
                    StaviaEvidenceTypes.RDO_ATTRIBUTE
            )) {
                return buildResponse(
                        buildRdoAttributeAnswer(question, focusedEvidence),
                        StaviaAnswerType.FATO,
                        focusedEvidence
                );
            }

            return buildResponse(
                    buildRdoAnswer(focusedEvidence),
                    StaviaAnswerType.FATO,
                    focusedEvidence
            );
        }

        if (intent == StaviaIntent.CONSULTAR_HISTORICO) {
            return buildResponse(
                    buildHistoryAnswer(focusedEvidence),
                    StaviaAnswerType.FATO,
                    focusedEvidence
            );
        }

        if (intent == StaviaIntent.CONSULTAR_PROGRAMACAO) {
            return buildResponse(
                    buildProgrammingAnswer(focusedEvidence),
                    StaviaAnswerType.FATO,
                    focusedEvidence
            );
        }

        if (intent == StaviaIntent.CONSULTAR_PDOC) {
            return buildResponse(
                    buildPdocAnswer(focusedEvidence),
                    StaviaAnswerType.FATO,
                    focusedEvidence
            );
        }

        if (
                intent == StaviaIntent.CONSULTAR_RECEITA
                        || intent == StaviaIntent.CONSULTAR_MARGEM
                        || intent == StaviaIntent.CONSULTAR_PREVISAO_FINANCEIRA
                        || intent == StaviaIntent.CONSULTAR_PRODUCAO
                        || intent == StaviaIntent.CONSULTAR_RECEITA_EM_RISCO
        ) {
            return buildResponse(
                    buildFinancialAnswer(focusedEvidence, intent),
                    StaviaAnswerType.FATO,
                    focusedEvidence
            );
        }

        if (
                intent == StaviaIntent.CONSULTAR_ALOCACAO_COLABORADOR
                        || intent == StaviaIntent.CONSULTAR_FREQUENCIA
                        || intent == StaviaIntent.CONSULTAR_BANCO_HORAS
        ) {
            return buildResponse(
                    buildAllocationAnswer(question, focusedEvidence, intent),
                    StaviaAnswerType.FATO,
                    focusedEvidence
            );
        }

        if (intent == StaviaIntent.CONSULTAR_EQUIPE) {
            return buildResponse(
                    buildTeamAnswer(question, focusedEvidence),
                    StaviaAnswerType.FATO,
                    focusedEvidence
            );
        }

        if (intent == StaviaIntent.CONSULTAR_ATIVO) {
            return buildResponse(
                    buildAssetAnswer(focusedEvidence),
                    StaviaAnswerType.FATO,
                    focusedEvidence
            );
        }

        if (intent == StaviaIntent.CONSULTAR_OBRA
                && hasEvidenceType(
                        focusedEvidence,
                        StaviaEvidenceTypes.OBRA
                )
                && asksForAtomicWorksiteAttribute(question.text())) {
            return buildResponse(
                    buildWorksiteSummary(question, focusedEvidence),
                    StaviaAnswerType.FATO,
                    focusedEvidence
            );
        }

        String prefix = switch (intent) {
            case CONSULTAR_OBRA ->
                    "Informações da obra identificadas";

            case CONSULTAR_ESTADO_ATUAL ->
                    "Estado atual identificado";

            case CONSULTAR_HISTORICO ->
                    "Histórico operacional identificado";

            case CONSULTAR_RDO ->
                    "Informações de RDO identificadas";

            case CONSULTAR_PROGRAMACAO ->
                    "Informações de programação identificadas";

            case CONSULTAR_EQUIPE ->
                    "Informações da equipe identificadas";

            case CONSULTAR_ATIVO ->
                    "Informações de ativos identificadas";

            case CONSULTAR_OCORRENCIA ->
                    "Ocorrências identificadas";

            case CONSULTAR_PDOC ->
                    "Análise preditiva de custos e riscos identificada";

            case CONSULTAR_RECEITA ->
                    "Receita operacional identificada";

            case CONSULTAR_MARGEM ->
                    "Margem operacional identificada";

            case CONSULTAR_PREVISAO_FINANCEIRA ->
                    "Previsão financeira identificada";

            case CONSULTAR_PRODUCAO ->
                    "Produção operacional identificada";

            case CONSULTAR_RECEITA_EM_RISCO ->
                    "Receita em risco identificada";

            case CONSULTAR_ALOCACAO_COLABORADOR ->
                    "Alocação de colaboradores identificada";

            case CONSULTAR_FREQUENCIA ->
                    "Frequência identificada";

            case CONSULTAR_BANCO_HORAS ->
                    "Banco de horas identificado";

            case RESUMIR_OBRA ->
                    "Resumo da obra";

            case DESCONHECIDA ->
                    "Informações encontradas";
        };

        String summaries =
                intent == StaviaIntent.CONSULTAR_OBRA
                        ? buildWorksiteSummary(
                                question,
                                focusedEvidence
                        )
                        : focusedEvidence
                                .stream()
                                .map(StaviaEvidence::summary)
                                .map(this::removeTerminalPeriod)
                                .distinct()
                                .collect(
                                        Collectors.joining("; ")
                                );

        String finalAnswer =
                normalizeGeneratedAnswer(
                        prefix + ": " + summaries + "."
                );

        return buildResponse(
                finalAnswer,
                StaviaAnswerType.FATO,
                focusedEvidence
        );
    }

    private StaviaGeneratedResponse buildResponse(
            String text,
            StaviaAnswerType answerType,
            List<StaviaEvidence> evidences
    ) {
        List<String> sourceKeys = evidences
                .stream()
                .map(this::evidenceKey)
                .toList();

        return new StaviaGeneratedResponse(
                normalizeGeneratedAnswer(text),
                answerType,
                sourceKeys
        );
    }

    private String buildTeamAnswer(
            StaviaQuestion question,
            List<StaviaEvidence> evidences
    ) {
        Map<String, TeamMember> members =
                new LinkedHashMap<>();

        for (StaviaEvidence evidence : evidences) {
            if (!StaviaEvidenceTypes.EQUIPE.equals(evidence.type())) {
                continue;
            }

            Map<String, Object> attributes = evidence.attributes();
            String name = fallback(
                    attributeText(
                            attributes,
                            "nomeColaboradorCadastrado"
                    ),
                    fallback(
                            attributeText(attributes, "nomeRegistrado"),
                            attributeText(attributes, "colaboradorNome")
                    )
            );
            String role = fallback(
                    attributeText(attributes, "cargo"),
                    "função não informada"
            );
            String displayName = hasText(name)
                    ? name
                    : fallback(
                            attributeText(attributes, "nomeGrupo"),
                            "Grupo de mão de obra sem identificação nominal"
                    );
            String key = (displayName + "|" + role)
                    .toLowerCase(Locale.ROOT);

            TeamMember member = members.computeIfAbsent(
                    key,
                    ignored -> new TeamMember(displayName, role)
            );
            member.register(evidence);
        }

        if (members.isEmpty()) {
            return "Nenhum registro de mão de obra foi encontrado para esta obra.";
        }

        boolean wantsRoleDetails =
                asksForTeamRoleDetails(question.text());
        StringBuilder answer = new StringBuilder();
        answer.append(wantsRoleDetails
                ? members.size() == 1
                        ? "Foi identificado 1 registro de mão de obra:"
                        : "Foram identificados "
                        + members.size()
                        + " registros de mão de obra:"
                : "Colaboradores encontrados:");

        for (TeamMember member : members.values()) {
            answer.append("\n\n- ")
                    .append(member.name());

            if (wantsRoleDetails) {
                answer.append(" — ")
                        .append(member.role());
            }

            if (wantsRoleDetails && member.rdoCount() > 1) {
                answer.append(" (registrado em ")
                        .append(member.rdoCount())
                        .append(" RDOs)");
            }
        }

        return answer.toString();
    }

    private boolean asksForTeamRoleDetails(String question) {
        String normalizedQuestion = normalizeText(question);
        return containsAny(
                normalizedQuestion,
                "cargo",
                "funcao",
                "função",
                "papel",
                "apontador",
                "operador",
                "encarregado",
                "motorista",
                "detalhe",
                "detalhes",
                "fonte",
                "fontes",
                "evidencia",
                "evidências"
        );
    }

    private final class TeamMember {

        private final String name;
        private final String role;
        private final java.util.Set<String> rdoIds =
                new java.util.LinkedHashSet<>();

        private TeamMember(String name, String role) {
            this.name = name;
            this.role = role;
        }

        private void register(StaviaEvidence evidence) {
            String rdoId = attributeText(
                    evidence.attributes(),
                    "rdoId"
            );
            rdoIds.add(hasText(rdoId) ? rdoId : evidence.id());
        }

        private String name() {
            return name;
        }

        private String role() {
            return role;
        }

        private int rdoCount() {
            return rdoIds.size();
        }
    }

    private String buildRdoAnswer(
            List<StaviaEvidence> evidences
    ) {
        List<StaviaEvidence> rdos = evidences.stream()
                .filter(evidence ->
                        StaviaEvidenceTypes.RDO.equals(
                                evidence.type()
                        )
                )
                .sorted(
                        Comparator.comparing(
                                evidence -> attributeText(
                                        evidence.attributes(),
                                        "dataRdo"
                                ),
                                Comparator.reverseOrder()
                        )
                )
                .toList();

        if (rdos.isEmpty()) {
            return "Nenhum RDO cadastral foi encontrado para esta obra.";
        }

        String worksiteCode =
                attributeText(
                        rdos.getFirst().attributes(),
                        "codigoObra"
                );

        StringBuilder answer =
                new StringBuilder();

        answer.append("A obra ");

        if (hasText(worksiteCode)) {
            answer.append(worksiteCode).append(" ");
        }

        answer.append(rdos.size() == 1
                ? "possui 1 RDO registrado:"
                : "possui " + rdos.size()
                        + " RDOs registrados:");

        for (StaviaEvidence rdo : rdos) {
            Map<String, Object> attributes =
                    rdo.attributes();

            String numeroRdo =
                    attributeText(attributes, "numeroRdo");
            String dataRdo =
                    attributeText(attributes, "dataRdo");
            String status =
                    attributeText(attributes, "status");

            if (
                    !hasText(numeroRdo)
            ) {
                answer.append("\n\n- ")
                        .append(removeTerminalPeriod(rdo.summary()));
                continue;
            }

            answer.append("\n\n- ")
                    .append(
                            displayRdoNumber(
                                    fallback(
                                            numeroRdo,
                                            "RDO sem número"
                                    )
                            )
                    );

            appendInline(
                    answer,
                    formatDate(dataRdo)
            );
            appendInline(
                    answer,
                    readableStatus(status)
            );
        }

        return answer.toString();
    }

    private String buildSegmentAnswer(
            StaviaQuestion question,
            List<StaviaEvidence> evidences
    ) {
        List<StaviaEvidence> segments = evidences.stream()
                .filter(evidence ->
                        StaviaEvidenceTypes.TRECHO_OPERACIONAL.equals(
                                evidence.type()
                        )
                )
                .sorted(
                        Comparator.comparing(
                                evidence -> attributeText(
                                        evidence.attributes(),
                                        "dataRdo"
                                ),
                                Comparator.reverseOrder()
                        )
                )
                .toList();

        if (segments.isEmpty()) {
            return "Nenhum controle geométrico foi encontrado no trecho informado.";
        }

        if (segments.stream().allMatch(evidence ->
                "SEM_DADOS_CONTROLE_GEOMETRICO".equals(
                        attributeText(evidence.attributes(), "classe")
                ))) {
            return "Não há controles geométricos com área ou volume cadastrados para a obra informada.";
        }

        if (segments.stream().allMatch(evidence ->
                "SEM_DADOS_TRECHO".equals(
                        attributeText(evidence.attributes(), "classe")
                ))) {
            Map<String, Object> attributes = segments.getFirst().attributes();
            String start = attributeText(attributes, "kmConsultaInicial");
            String end = attributeText(attributes, "kmConsultaFinal");

            return hasText(start) && hasText(end)
                    ? "Não há controles geométricos cadastrados entre os km "
                            + start + " e " + end + " para a obra informada."
                    : "Não há controles geométricos cadastrados para o trecho informado.";
        }

        String normalizedQuestion =
                normalizeText(question.text());

        if (asksForSegmentMeasurements(normalizedQuestion)) {
            return buildSegmentMeasurementAnswer(
                    normalizedQuestion,
                    segments
            );
        }

        StringBuilder answer = new StringBuilder();
        answer.append(segments.size() == 1
                ? "Foi encontrado 1 registro operacional no trecho solicitado:"
                : "Foram encontrados " + segments.size()
                        + " registros operacionais no trecho solicitado:");

        for (StaviaEvidence segment : segments) {
            Map<String, Object> attributes = segment.attributes();
            String rdo = fallback(
                    attributeText(attributes, "numeroRdo"),
                    attributeText(attributes, "rdoId")
            );
            String range = segmentRange(attributes);

            answer.append("\n\n- ")
                    .append(hasText(rdo) ? "RDO " + rdo : "RDO sem número");
            appendInline(answer, attributeText(attributes, "rodovia"));
            appendInline(answer, range);
            appendInline(answer, attributeText(attributes, "subtrecho"));
            appendInline(answer, formatDate(attributeText(attributes, "dataRdo")));
            appendInline(
                    answer,
                    readableStatus(attributeText(attributes, "status"))
            );
            appendInline(
                    answer,
                    measurementText(attributes, "comprimentoM", "m")
            );
            appendInline(answer, measurementText(attributes, "areaM2", "m²"));
            appendInline(answer, measurementText(attributes, "volumeM3", "m³"));
        }

        return answer.toString();
    }

    private String buildSegmentMeasurementAnswer(
            String normalizedQuestion,
            List<StaviaEvidence> segments
    ) {
        BigDecimal area =
                sumMeasurement(segments, "areaM2");
        BigDecimal volume =
                sumMeasurement(segments, "volumeM3");
        BigDecimal length =
                sumMeasurement(segments, "comprimentoM");

        boolean asksArea =
                containsAny(
                        normalizedQuestion,
                        "area",
                        "m2",
                        "m²",
                        "metro quadrado",
                        "metros quadrados"
                );
        boolean asksVolume =
                containsAny(
                        normalizedQuestion,
                        "volume",
                        "m3",
                        "m³",
                        "metro cubico",
                        "metro cúbico",
                        "metros cubicos",
                        "metros cúbicos"
                );

        StringBuilder answer = new StringBuilder();
        answer.append("Com base em ")
                .append(segments.size())
                .append(
                        segments.size() == 1
                                ? " controle geométrico"
                                : " controles geométricos"
                )
                .append(", ");

        List<String> parts = new ArrayList<>();

        if (asksArea || (!asksArea && !asksVolume)) {
            parts.add(hasValue(area)
                    ? "a área total registrada é "
                            + area.toPlainString()
                            + " m²"
                    : "não há área registrada");
        }

        if (asksVolume || (!asksArea && !asksVolume)) {
            parts.add(hasValue(volume)
                    ? "o volume total registrado é "
                            + volume.toPlainString()
                            + " m³"
                    : "não há volume registrado");
        }

        if (!parts.isEmpty()) {
            answer.append(String.join(" e ", parts));
        }

        if (hasValue(length)) {
            answer.append(". Extensão registrada: ")
                    .append(length.toPlainString())
                    .append(" m");
        }

        return answer.append(".").toString();
    }

    private boolean asksForSegmentMeasurements(String normalizedQuestion) {
        return containsAny(
                normalizedQuestion,
                "area",
                "m2",
                "m²",
                "metro quadrado",
                "metros quadrados",
                "volume",
                "m3",
                "m³",
                "metro cubico",
                "metro cúbico",
                "metros cubicos",
                "metros cúbicos"
        );
    }

    private BigDecimal sumMeasurement(
            List<StaviaEvidence> evidences,
            String key
    ) {
        BigDecimal total = BigDecimal.ZERO;
        boolean found = false;

        for (StaviaEvidence evidence : evidences) {
            BigDecimal value =
                    decimalValue(evidence.attributes().get(key));

            if (value == null) {
                continue;
            }

            total = total.add(value);
            found = true;
        }

        return found ? total.stripTrailingZeros() : null;
    }

    private boolean hasValue(BigDecimal value) {
        return value != null;
    }

    private String segmentRange(Map<String, Object> attributes) {
        String start = attributeText(attributes, "kmInicial");
        String end = attributeText(attributes, "kmFinal");

        if (hasText(start) && hasText(end)) {
            return "km " + start + " a " + end;
        }

        if (hasText(start)) {
            return "km " + start;
        }

        return hasText(end) ? "até km " + end : "";
    }

    private String measurementText(
            Map<String, Object> attributes,
            String key,
            String unit
    ) {
        BigDecimal value = decimalValue(attributes.get(key));
        return value == null ? "" : value.toPlainString() + " " + unit;
    }

    private String buildRdoAttributeAnswer(
            StaviaQuestion question,
            List<StaviaEvidence> evidences
    ) {
        List<StaviaEvidence> attributes =
                evidences.stream()
                        .filter(evidence ->
                                StaviaEvidenceTypes.RDO_ATTRIBUTE.equals(
                                        evidence.type()
                                )
                        )
                        .toList();

        if (attributes.isEmpty()) {
            return "Nenhum atributo estruturado de RDO foi encontrado para esta obra.";
        }

        String directAnswer =
                directRdoAttributeAnswer(question, attributes);

        if (hasText(directAnswer)) {
            return directAnswer;
        }

        Map<String, Object> first =
                attributes.getFirst().attributes();

        String code =
                fallback(
                        attributeText(first, "codigoObra"),
                        attributeText(first, "obraId")
                );
        String rdoNumber =
                fallback(
                        attributeText(first, "rdoNumero"),
                        attributeText(first, "rdoId")
                );
        String rdoDate =
                formatDate(
                        attributeText(first, "dataRdo")
                );
        String status =
                attributeText(first, "statusRdo");

        boolean climate =
                attributes.stream()
                        .map(evidence ->
                                attributeText(
                                        evidence.attributes(),
                                        "campo"
                                )
                        )
                        .anyMatch(field ->
                                field.startsWith("condicao")
                                        || "pluviometriaMm".equals(field)
                        );

        StringBuilder answer =
                new StringBuilder();

        answer.append(
                climate
                        ? "A condição climática mais recente registrada"
                        : "O atributo de RDO mais recente registrado"
        );

        if (hasText(code)) {
            answer.append(" na obra ")
                    .append(code);
        }

        answer.append(" foi:");

        appendRdoAttributeLine(answer, attributes, "condicaoManha", "Manhã", null);
        appendRdoAttributeLine(answer, attributes, "condicaoTarde", "Tarde", null);
        appendRdoAttributeLine(answer, attributes, "condicaoNoite", "Noite", null);
        appendRdoAttributeLine(answer, attributes, "pluviometriaMm", "Pluviometria", "mm");

        answer.append("\n\nO dado foi informado no ")
                .append(displayRdoNumber(rdoNumber));

        if (hasText(rdoDate)) {
            answer.append(", de ")
                    .append(rdoDate);
        }

        answer.append(".");

        String readableStatus =
                readableStatus(status);

        if (hasText(readableStatus)) {
            answer.append(" Status do RDO: ")
                    .append(readableStatus)
                    .append(".");
        }

        if (!isValidatedRdoStatus(status)) {
            answer.append(" Esse RDO ainda não consta como validado pela sala técnica; use a informação como operacional, não como validação final.");
        }

        return answer.toString();
    }

    private String directRdoAttributeAnswer(
            StaviaQuestion question,
            List<StaviaEvidence> attributes
    ) {
        String normalizedQuestion =
                normalizeText(question.text());

        if (containsAny(normalizedQuestion, "turno")) {
            return attributes.stream()
                    .filter(evidence ->
                            "turno".equals(
                                    attributeText(
                                            evidence.attributes(),
                                            "campo"
                                    )
                            )
                    )
                    .findFirst()
                    .map(evidence ->
                            attributeText(evidence.attributes(), "valor")
                    )
                    .filter(this::hasText)
                    .map(this::readableStatus)
                    .orElse("");
        }

        List<StaviaEvidence> directAttributes =
                attributes.stream()
                        .filter(evidence ->
                                hasText(
                                        attributeText(
                                                evidence.attributes(),
                                                "valor"
                                        )
                                )
                        )
                        .filter(evidence ->
                                !attributeText(
                                        evidence.attributes(),
                                        "campo"
                                ).startsWith("condicao")
                                        && !"pluviometriaMm".equals(
                                                attributeText(
                                                        evidence.attributes(),
                                                        "campo"
                                                )
                                        )
                        )
                        .toList();

        if (!directAttributes.isEmpty()) {
            Map<String, Object> evidenceAttributes =
                    directAttributes.getFirst().attributes();
            String label =
                    fallback(
                            attributeText(evidenceAttributes, "rotulo"),
                            attributeText(evidenceAttributes, "campo")
                    );
            String value =
                    attributeText(evidenceAttributes, "valor");
            String unit =
                    attributeText(evidenceAttributes, "unidade");

            return label
                    + ": "
                    + value
                    + (hasText(unit) ? " " + unit : "")
                    + ".";
        }

        return "";
    }

    private void appendRdoAttributeLine(
            StringBuilder answer,
            List<StaviaEvidence> attributes,
            String field,
            String label,
            String unit
    ) {
        String value =
                attributes.stream()
                        .filter(evidence ->
                                field.equals(
                                        attributeText(
                                                evidence.attributes(),
                                                "campo"
                                        )
                                )
                        )
                        .findFirst()
                        .map(evidence ->
                                attributeText(
                                        evidence.attributes(),
                                        "valor"
                                )
                        )
                        .filter(this::hasText)
                        .orElse("Não informado");

        answer.append("\n- ")
                .append(label)
                .append(": ")
                .append(value);

        if (hasText(unit) && !"Não informado".equals(value)) {
            answer.append(" ")
                    .append(unit);
        }
    }

    private boolean isValidatedRdoStatus(String status) {
        if (!hasText(status)) {
            return false;
        }

        return List.of(
                "VALIDADO",
                "VALIDADA",
                "APROVADO",
                "APROVADA"
        ).contains(status.trim().toUpperCase(Locale.ROOT));
    }

    private String buildHistoryAnswer(
            List<StaviaEvidence> evidences
    ) {
        List<StaviaEvidence> events = evidences.stream()
                .filter(evidence ->
                        StaviaEvidenceTypes.EVENTO_OPERACIONAL.equals(
                                evidence.type()
                        )
                )
                .sorted(
                        Comparator.comparingLong(
                                this::commitSequence
                        )
                )
                .toList();

        if (events.isEmpty()) {
            return "Nenhum evento operacional de RDO foi encontrado para esta obra.";
        }

        Map<String, List<StaviaEvidence>> byRdo =
                new LinkedHashMap<>();

        for (StaviaEvidence event : events) {
            String rdoKey = fallback(
                    payloadText(event, "numeroRdo"),
                    attributeText(event.attributes(), "entityId")
            );

            byRdo.computeIfAbsent(
                    rdoKey,
                    ignored -> new ArrayList<>()
            ).add(event);
        }

        StringBuilder answer =
                new StringBuilder();

        answer.append("Foi encontrado ")
                .append(byRdo.size())
                .append(byRdo.size() == 1 ? " RDO" : " RDOs")
                .append(" com ")
                .append(events.size())
                .append(events.size() == 1
                        ? " evento registrado."
                        : " eventos registrados.");

        byRdo.forEach((rdo, rdoEvents) -> {
            answer.append("\n\nRDO ")
                    .append(rdo);

            for (StaviaEvidence event : rdoEvents) {
                answer.append("\n\n")
                        .append(formatInstant(event.updatedAt()));

                List<String> lines = historyLines(event);

                for (String line : lines) {
                    answer.append("\n- ")
                            .append(line);
                }
            }
        });

        return answer.toString();
    }

    private List<String> historyLines(
            StaviaEvidence event
    ) {
        String eventType =
                attributeText(event.attributes(), "eventType");

        if ("RDO_CRIADO".equals(eventType)) {
            String status =
                    readableStatus(payloadText(event, "status"));

            return List.of(
                    hasText(status)
                            ? "RDO criado como "
                                    + status.toLowerCase(
                                            Locale.ROOT
                                    )
                                    + "."
                            : "RDO criado."
            );
        }

        if ("RDO_ENVIADO".equals(eventType)) {
            return List.of("RDO enviado.");
        }

        if ("RDO_EDITADO".equals(eventType)) {
            Object alterations =
                    payload(event).get("alteracoes");

            if (alterations instanceof List<?> list
                    && !list.isEmpty()) {
                return list.stream()
                        .filter(Map.class::isInstance)
                        .map(Map.class::cast)
                        .map(this::alterationLine)
                        .filter(Objects::nonNull)
                        .toList();
            }

            return List.of(
                    "RDO editado. O evento antigo não contém detalhamento dos campos alterados."
            );
        }

        return List.of(
                removeTerminalPeriod(event.summary()) + "."
        );
    }

    private String alterationLine(Map<?, ?> alteration) {
        String label =
                stringValue(alteration.get("rotulo"));
        String field =
                stringValue(alteration.get("campo"));
        String operation =
                stringValue(alteration.get("operacao"));
        String before =
                stringValue(alteration.get("valorAnterior"));
        String after =
                stringValue(alteration.get("valorNovo"));

        String readableLabel =
                fallback(label, field);

        if (!hasText(readableLabel)) {
            return null;
        }

        if ("ADICIONADO".equals(operation)) {
            return readableLabel + " adicionado: "
                    + fallback(after, "item sem descrição")
                    + ".";
        }

        if ("REMOVIDO".equals(operation)) {
            return readableLabel + " removido: "
                    + fallback(before, "item sem descrição")
                    + ".";
        }

        if ("observacoes".equals(field)) {
            return "Observações alteradas.";
        }

        if (!hasText(before) && !hasText(after)) {
            return readableLabel
                    + " "
                    + changedVerb(readableLabel)
                    + ".";
        }

        return readableLabel
                + " "
                + changedVerb(readableLabel)
                + " de "
                + fallback(before, "não informado")
                + " para "
                + fallback(after, "não informado")
                + ".";
    }

    private String changedVerb(String label) {
        String normalized =
                hasText(label)
                        ? normalizeText(label).trim()
                        : "";

        if (normalized.endsWith("oes")
                || normalized.endsWith("coes")
                || normalized.endsWith("as")) {
            return "alteradas";
        }

        if (normalized.endsWith("ais")
                || normalized.endsWith("os")
                || normalized.endsWith("s")) {
            return "alterados";
        }

        if (normalized.endsWith("a")
                || normalized.endsWith("ao")
                || normalized.endsWith("cao")
                || normalized.endsWith("gem")) {
            return "alterada";
        }

        return "alterado";
    }

    private String buildProgrammingAnswer(
            List<StaviaEvidence> evidences
    ) {
        List<StaviaEvidence> rdos = evidences.stream()
                .filter(evidence ->
                        StaviaEvidenceTypes.RDO.equals(
                                evidence.type()
                        )
                )
                .toList();

        if (rdos.isEmpty()) {
            String relationSummary = evidences.stream()
                    .filter(evidence ->
                            StaviaEvidenceTypes.RELACAO_ONTOLOGICA
                                    .equals(evidence.type())
                    )
                    .map(StaviaEvidence::summary)
                    .collect(Collectors.joining("\n"));

            return hasText(relationSummary)
                    ? relationSummary
                    : "Nenhum RDO cadastral foi encontrado para verificar programação operacional.";
        }

        return rdos.stream()
                .map(this::programmingLine)
                .collect(Collectors.joining("\n\n"));
    }

    private String programmingLine(StaviaEvidence rdo) {
        Map<String, Object> attributes =
                rdo.attributes();

        String rdoNumber =
                fallback(
                        attributeText(attributes, "numeroRdo"),
                        "RDO sem número"
                );

        String programacaoId =
                attributeText(attributes, "programacaoId");

        if (!hasText(programacaoId)) {
            return "O RDO "
                    + rdoNumber
                    + " não possui programação operacional associada.";
        }

        StringBuilder line =
                new StringBuilder();

        line.append("O RDO ")
                .append(rdoNumber)
                .append(" foi gerado a partir da programação operacional");

        String programacaoData =
                formatDate(
                        attributeText(
                                attributes,
                                "programacaoData"
                        )
                );

        if (hasText(programacaoData)) {
            line.append(" de ")
                    .append(programacaoData);
        }

        String service =
                attributeText(
                        attributes,
                        "programacaoServico"
                );

        if (hasText(service)) {
            line.append(", referente ao serviço \"")
                    .append(service)
                    .append("\"");
        }

        String start =
                attributeText(attributes, "programacaoKmInicial");
        String end =
                attributeText(attributes, "programacaoKmFinal");

        if (hasText(start) || hasText(end)) {
            line.append(", no trecho km ")
                    .append(hasText(start) ? start : "?")
                    .append(" a ")
                    .append(hasText(end) ? end : "?");
        }

        appendSentenceDetail(
                line,
                "período",
                attributeText(attributes, "programacaoPeriodo")
        );
        appendSentenceDetail(
                line,
                "encarregado",
                attributeText(attributes, "programacaoEncarregado")
        );
        appendSentenceDetail(
                line,
                "área",
                withUnit(attributeText(attributes, "programacaoAreaM2"), "m²")
        );
        appendSentenceDetail(
                line,
                "volume",
                withUnit(attributeText(attributes, "programacaoVolumeM3"), "m³")
        );
        appendSentenceDetail(
                line,
                "massa",
                withUnit(attributeText(attributes, "programacaoToneladaMassa"), "t")
        );

        line.append(".");
        return line.toString();
    }

    private void appendSentenceDetail(
            StringBuilder line,
            String label,
            String value
    ) {
        if (hasText(value)) {
            line.append(", ")
                    .append(label)
                    .append(" ")
                    .append(value);
        }
    }

    private String withUnit(String value, String unit) {
        return hasText(value) ? value + " " + unit : "";
    }

    private String buildPdocAnswer(
            List<StaviaEvidence> evidences
    ) {
        StaviaEvidence pdoc = evidences.stream()
                .filter(evidence ->
                        StaviaEvidenceTypes.PDOC.equals(
                                evidence.type()
                        )
                )
                .findFirst()
                .orElse(evidences.getFirst());

        Map<String, Object> attributes =
                pdoc.attributes();

        String status =
                attributeText(attributes, "statusExecucao");
        String code =
                fallback(
                        attributeText(attributes, "codigoCw"),
                        fallback(
                                attributeText(
                                        attributes,
                                        "codigoContrato"
                                ),
                                attributeText(attributes, "obraId")
                        )
                );

        if ("NO_SNAPSHOT".equals(status)) {
            return "Ainda não existe snapshot PDOC para a obra "
                    + code
                    + ". A Stav.IA não executou simulação artificial; consulte ou execute o cálculo PDOC real antes de interpretar risco de estouro.";
        }

        if ("OBRA_NAO_LOCALIZADA".equals(status)) {
            return "O PDOC não localizou a obra informada no cadastro operacional.";
        }

        if ("INSUFFICIENT_DATA".equals(status)) {
            return insufficientPdocAnswer(code, attributes);
        }

        if ("FAILED".equals(status)) {
            String error =
                    attributeText(attributes, "erroExecucao");

            return "O PDOC foi executado para a obra "
                    + code
                    + ", mas terminou com erro"
                    + (hasText(error) ? ": " + error : ".")
                    + ".";
        }

        return successfulPdocAnswer(code, attributes);
    }

    private String buildFinancialAnswer(
            List<StaviaEvidence> evidences,
            StaviaIntent intent
    ) {
        StaviaEvidence snapshot = evidences.stream()
                .filter(evidence ->
                        StaviaEvidenceTypes.PREVISAO_FINANCEIRA.equals(
                                evidence.type()
                        )
                )
                .findFirst()
                .orElse(evidences.getFirst());

        Map<String, Object> attributes =
                snapshot.attributes();

        String status =
                attributeText(attributes, "statusExecucao");
        String code =
                fallback(
                        attributeText(attributes, "codigoObra"),
                        attributeText(attributes, "obraId")
                );

        if ("OBRA_NAO_LOCALIZADA".equals(status)) {
            return "A previsão financeira não localizou a obra informada no cadastro operacional.";
        }

        if ("SEM_SNAPSHOT".equals(status)) {
            return "Ainda não existe snapshot de previsão financeira para a obra "
                    + code
                    + ". Gere o cálculo financeiro a partir dos itens contratuais e RDOs para consultar receita, produção e margem.";
        }

        if ("DADOS_INSUFICIENTES".equals(status)) {
            StringBuilder insufficient =
                    new StringBuilder();

            insufficient.append("A previsão financeira foi executada para a obra ")
                    .append(code)
                    .append(", mas ainda não há dados suficientes para consolidar receita, custo e margem.");

            appendFinancialReference(insufficient, attributes);
            appendMetric(
                    insufficient,
                    "Status do cálculo",
                    readableStatus(
                            attributeText(attributes, "statusCalculo")
                    )
            );
            appendMetric(
                    insufficient,
                    "Confiança",
                    percentText(attributes.get("confianca"))
            );

            return insufficient.toString();
        }

        StringBuilder answer =
                new StringBuilder();

        answer.append(financialOpening(intent))
                .append(" da obra ")
                .append(code)
                .append(".");

        appendFinancialReference(answer, attributes);

        switch (intent) {
            case CONSULTAR_RECEITA -> {
                appendMetric(
                        answer,
                        "Receita operacional estimada no dia",
                        moneyText(attributes.get("receitaEstimadaDia"))
                );
                appendMetric(
                        answer,
                        "Receita operacional estimada acumulada",
                        moneyText(attributes.get("receitaEstimadaAcumulada"))
                );
                appendMetric(
                        answer,
                        "Receita prevista final",
                        moneyText(attributes.get("receitaPrevistaFinal"))
                );
                appendMetric(
                        answer,
                        "Receita medida",
                        moneyText(attributes.get("receitaMedida"))
                );
                appendMetric(
                        answer,
                        "Receita faturada",
                        moneyText(attributes.get("receitaFaturada"))
                );
            }
            case CONSULTAR_MARGEM -> {
                appendMetric(
                        answer,
                        "Margem atual",
                        moneyText(attributes.get("margemAtual"))
                );
                appendMetric(
                        answer,
                        "Margem prevista",
                        moneyText(attributes.get("margemPrevista"))
                );
                appendMetric(
                        answer,
                        "Margem percentual",
                        percentText(attributes.get("margemPercentual"))
                );
                appendMetric(
                        answer,
                        "Custo realizado",
                        moneyText(attributes.get("custoRealizado"))
                );
                appendMetric(
                        answer,
                        "Custo previsto final",
                        moneyText(attributes.get("custoPrevistoFinal"))
                );
            }
            case CONSULTAR_PRODUCAO -> {
                appendMetric(
                        answer,
                        "Produção planejada",
                        numberText(attributes.get("producaoPlanejada"))
                );
                appendMetric(
                        answer,
                        "Produção realizada",
                        numberText(attributes.get("producaoRealizada"))
                );
                appendMetric(
                        answer,
                        "Backlog contratual",
                        numberText(attributes.get("backlogContratual"))
                );
            }
            case CONSULTAR_RECEITA_EM_RISCO -> {
                appendMetric(
                        answer,
                        "Receita em risco",
                        moneyText(attributes.get("receitaEmRisco"))
                );
                appendMetric(
                        answer,
                        "Backlog contratual",
                        numberText(attributes.get("backlogContratual"))
                );
                appendMetric(
                        answer,
                        "Confiança",
                        percentText(attributes.get("confianca"))
                );
            }
            default -> {
                appendMetric(
                        answer,
                        "Status",
                        readableStatus(status)
                );
                appendMetric(
                        answer,
                        "Status do cálculo",
                        readableStatus(
                                attributeText(attributes, "statusCalculo")
                        )
                );
                appendMetric(
                        answer,
                        "Receita operacional estimada acumulada",
                        moneyText(attributes.get("receitaEstimadaAcumulada"))
                );
                appendMetric(
                        answer,
                        "Receita prevista final",
                        moneyText(attributes.get("receitaPrevistaFinal"))
                );
                appendMetric(
                        answer,
                        "Custo previsto final",
                        moneyText(attributes.get("custoPrevistoFinal"))
                );
                appendMetric(
                        answer,
                        "Margem prevista",
                        moneyText(attributes.get("margemPrevista"))
                );
                appendMetric(
                        answer,
                        "Margem percentual",
                        percentText(attributes.get("margemPercentual"))
                );
                appendMetric(
                        answer,
                        "Receita em risco",
                        moneyText(attributes.get("receitaEmRisco"))
                );
                appendMetric(
                        answer,
                        "Confiança",
                        percentText(attributes.get("confianca"))
                );
            }
        }

        if (
                Boolean.TRUE.equals(
                        attributes.get("receitaOperacionalNaoFaturada")
                )
        ) {
            answer.append("\n\nNota: receita operacional estimada não equivale automaticamente a faturamento, aprovação de medição ou recebimento financeiro.");
        }

        return answer.toString();
    }

    private String financialOpening(StaviaIntent intent) {
        return switch (intent) {
            case CONSULTAR_RECEITA ->
                    "A receita operacional estimada foi consolidada";
            case CONSULTAR_MARGEM ->
                    "A margem operacional foi consolidada";
            case CONSULTAR_PRODUCAO ->
                    "A produção operacional foi consolidada";
            case CONSULTAR_RECEITA_EM_RISCO ->
                    "A receita em risco foi consolidada";
            default ->
                    "A previsão financeira foi consolidada";
        };
    }

    private void appendFinancialReference(
            StringBuilder answer,
            Map<String, Object> attributes
    ) {
        String reference =
                formatDate(
                        attributeText(
                                attributes,
                                "dataReferencia"
                        )
                );

        if (hasText(reference)) {
            answer.append("\n\nReferência: ")
                    .append(reference)
                    .append(".");
        }
    }

    private String buildAllocationAnswer(
            StaviaQuestion question,
            List<StaviaEvidence> evidences,
            StaviaIntent intent
    ) {
        if (
                intent == StaviaIntent.CONSULTAR_FREQUENCIA
                        || intent == StaviaIntent.CONSULTAR_BANCO_HORAS
        ) {
            List<StaviaEvidence> frequency = evidences.stream()
                    .filter(evidence ->
                            StaviaEvidenceTypes.FREQUENCIA.equals(
                                    evidence.type()
                            )
                    )
                    .toList();

            if (!frequency.isEmpty()) {
                return buildFrequencyAnswer(frequency, intent);
            }
        }

        List<StaviaEvidence> allocations = evidences.stream()
                .filter(evidence ->
                        StaviaEvidenceTypes.ALOCACAO_COLABORADOR.equals(
                                evidence.type()
                        )
                )
                .sorted(
                        Comparator.comparing(
                                        (StaviaEvidence evidence) -> attributeText(
                                                evidence.attributes(),
                                                "data"
                                        ),
                                        Comparator.reverseOrder()
                                )
                                .thenComparing(evidence ->
                                        attributeText(
                                                evidence.attributes(),
                                                "horaInicio"
                                        )
                                )
                )
                .toList();

        List<StaviaEvidence> collaborators = evidences.stream()
                .filter(evidence ->
                        StaviaEvidenceTypes.COLABORADOR.equals(
                                evidence.type()
                        )
                )
                .toList();

        if (allocations.isEmpty()) {
            if (!collaborators.isEmpty()) {
                return buildCollaboratorCatalogAnswer(
                        question,
                        collaborators,
                        intent
                );
            }
            return allocationEmptyAnswer(intent);
        }

        if (isCollaboratorWorksiteListQuestion(question)) {
            return buildCollaboratorWorksitesAnswer(allocations);
        }

        if (isCollaboratorProfileQuestion(question)) {
            return buildCollaboratorProfileAnswer(allocations);
        }

        String code =
                fallback(
                        attributeText(
                                allocations.getFirst().attributes(),
                                "codigoCw"
                        ),
                        fallback(
                                attributeText(
                                        allocations.getFirst().attributes(),
                                        "codigoContrato"
                                ),
                                attributeText(
                                        allocations.getFirst().attributes(),
                                        "obraId"
                                )
                        )
                );

        boolean multipleWorksites = allocations.stream()
                .map(evidence -> fallback(
                        attributeText(evidence.attributes(), "obraId"),
                        attributeText(evidence.attributes(), "codigoCw")
                ))
                .filter(this::hasText)
                .distinct()
                .count() > 1;

        StringBuilder answer = new StringBuilder();

        if (multipleWorksites) {
            answer.append("Sim. Foram encontrados registros de alocação em mais de uma obra:");
        } else {
            answer.append(allocationOpening(intent))
                    .append(" da obra ")
                    .append(code)
                    .append(":");
        }

        int displayed =
                Math.min(allocations.size(), 10);

        for (int index = 0; index < displayed; index++) {
            StaviaEvidence allocation =
                    allocations.get(index);
            Map<String, Object> attributes =
                    allocation.attributes();

            answer.append("\n\n- ")
                    .append(
                            fallback(
                                    attributeText(
                                            attributes,
                                            "colaboradorNome"
                                    ),
                                    "Colaborador sem nome"
                            )
                    );

            appendInline(answer, worksiteText(attributes));
            appendInline(
                    answer,
                    formatDate(
                            attributeText(attributes, "data")
                    )
            );
            appendInline(
                    answer,
                    timeRange(attributes)
            );
            appendInline(
                    answer,
                    hoursText(attributes.get("minutos"))
            );
            appendInline(
                    answer,
                    attributeText(attributes, "equipe")
            );
            appendInline(
                    answer,
                    attributeText(attributes, "servicoNome")
            );
            appendInline(
                    answer,
                    readableStatus(
                            attributeText(attributes, "status")
                    )
            );
        }

        if (allocations.size() > displayed) {
            answer.append("\n\nForam omitidas ")
                    .append(allocations.size() - displayed)
                    .append(" alocações adicionais nesta resposta.");
        }

        if (intent == StaviaIntent.CONSULTAR_BANCO_HORAS) {
            answer.append("\n\nNota: esta resposta usa as alocações temporais disponíveis. Saldos e ajustes formais de banco de horas dependem dos lançamentos específicos de frequência e banco de horas.");
        }

        return answer.toString();
    }

    private boolean isCollaboratorProfileQuestion(StaviaQuestion question) {
        return question != null
                && normalizeText(question.text()).trim().startsWith("quem e ");
    }

    private String buildCollaboratorCatalogAnswer(
            StaviaQuestion question,
            List<StaviaEvidence> collaborators,
            StaviaIntent intent
    ) {
        boolean profileQuestion =
                isCollaboratorProfileQuestion(question);

        if (profileQuestion && collaborators.size() == 1) {
            Map<String, Object> attributes =
                    collaborators.getFirst().attributes();

            StringBuilder answer =
                    new StringBuilder();

            answer.append(
                    fallback(
                            attributeText(attributes, "colaboradorNome"),
                            "O colaborador"
                    )
            ).append(" está cadastrado no Academy");

            appendInline(
                    answer,
                    codeText(
                            "código",
                            attributeText(attributes, "codigoColaborador")
                    )
            );
            appendInline(
                    answer,
                    codeText(
                            "grupo",
                            attributeText(attributes, "nomeGrupo")
                    )
            );
            appendInline(
                    answer,
                    codeText(
                            "perfil",
                            attributeText(attributes, "nomePerfil")
                    )
            );

            answer.append(". Não encontrei registro operacional de alocação/RDO para essa pessoa no recorte consultado.");

            return answer.toString();
        }

        StringBuilder answer =
                new StringBuilder();

        answer.append(
                intent == StaviaIntent.CONSULTAR_ALOCACAO_COLABORADOR
                        ? "Encontrei cadastro de colaborador no Academy, mas não encontrei alocação/RDO compatível no recorte consultado:"
                        : "Colaboradores cadastrados no Academy:"
        );

        int displayed =
                Math.min(collaborators.size(), 10);

        for (int index = 0; index < displayed; index++) {
            Map<String, Object> attributes =
                    collaborators.get(index).attributes();

            answer.append("\n\n- ")
                    .append(
                            fallback(
                                    attributeText(
                                            attributes,
                                            "colaboradorNome"
                                    ),
                                    "Colaborador sem nome"
                            )
                    );

            appendInline(
                    answer,
                    codeText(
                            "código",
                            attributeText(attributes, "codigoColaborador")
                    )
            );
            appendInline(
                    answer,
                    codeText(
                            "grupo",
                            attributeText(attributes, "nomeGrupo")
                    )
            );
            appendInline(
                    answer,
                    codeText(
                            "perfil",
                            attributeText(attributes, "nomePerfil")
                    )
            );
        }

        if (collaborators.size() > displayed) {
            answer.append("\n\nForam omitidos ")
                    .append(collaborators.size() - displayed)
                    .append(" colaboradores adicionais.");
        }

        return answer.toString();
    }

    private String codeText(
            String label,
            String value
    ) {
        if (!hasText(value)) {
            return "";
        }

        return label + " " + value.trim();
    }

    private boolean isCollaboratorWorksiteListQuestion(StaviaQuestion question) {
        if (question == null) {
            return false;
        }

        String normalized = normalizeText(question.text()).trim();
        return normalized.startsWith("quais sao as obras que")
                || normalized.startsWith("quais obras que")
                || normalized.startsWith("em quais obras")
                || normalized.contains("em qual obra")
                || normalized.contains("em quais obras");
    }

    private String buildCollaboratorWorksitesAnswer(
            List<StaviaEvidence> allocations
    ) {
        String collaborator = fallback(
                attributeText(
                        allocations.getFirst().attributes(),
                        "colaboradorNome"
                ),
                "O colaborador"
        );

        Map<String, StaviaEvidence> worksiteEvidence = new LinkedHashMap<>();
        for (StaviaEvidence allocation : allocations) {
            String worksiteId = fallback(
                    attributeText(allocation.attributes(), "obraId"),
                    allocation.id()
            );
            worksiteEvidence.putIfAbsent(worksiteId, allocation);
        }

        StringBuilder answer = new StringBuilder();
        answer.append(collaborator)
                .append(worksiteEvidence.size() == 1
                        ? " possui registro na seguinte obra:"
                        : " possui registros nas seguintes obras:");

        for (StaviaEvidence evidence : worksiteEvidence.values()) {
            Map<String, Object> attributes = evidence.attributes();
            answer.append("\n\n- ")
                    .append(worksiteText(attributes));
            appendInline(
                    answer,
                    formatDate(attributeText(attributes, "data"))
            );
            appendInline(
                    answer,
                    attributeText(attributes, "funcao")
            );
            appendInline(
                    answer,
                    readableStatus(attributeText(attributes, "status"))
            );
        }

        return answer.toString();
    }

    private String buildCollaboratorProfileAnswer(
            List<StaviaEvidence> allocations
    ) {
        StaviaEvidence first = allocations.getFirst();
        String collaborator = fallback(
                attributeText(first.attributes(), "colaboradorNome"),
                "Colaborador sem nome"
        );

        List<String> roles = allocations.stream()
                .map(evidence -> attributeText(evidence.attributes(), "funcao"))
                .filter(this::hasText)
                .distinct()
                .toList();

        StringBuilder answer = new StringBuilder();
        answer.append(collaborator)
                .append(" possui registros de mão de obra no Córtex.");

        if (roles.size() == 1) {
            answer.append(" No histórico disponível, aparece como ")
                    .append(roles.getFirst())
                    .append(".");
        } else if (!roles.isEmpty()) {
            answer.append(" No histórico disponível, aparece nas funções: ")
                    .append(String.join(", ", roles))
                    .append(".");
        }

        List<StaviaEvidence> distinctWorksites = allocations.stream()
                .collect(java.util.stream.Collectors.toMap(
                        evidence -> fallback(
                                attributeText(evidence.attributes(), "obraId"),
                                evidence.id()
                        ),
                        evidence -> evidence,
                        (left, right) -> left,
                        LinkedHashMap::new
                ))
                .values()
                .stream()
                .toList();

        answer.append("\n\nRegistros por obra:");
        for (StaviaEvidence evidence : distinctWorksites) {
            Map<String, Object> attributes = evidence.attributes();
            answer.append("\n\n- ")
                    .append(worksiteText(attributes));
            appendInline(
                    answer,
                    formatDate(attributeText(attributes, "data"))
            );
            appendInline(
                    answer,
                    attributeText(attributes, "funcao")
            );
            appendInline(
                    answer,
                    readableStatus(attributeText(attributes, "status"))
            );
        }

        return answer.toString();
    }

    private String buildAssetAnswer(
            List<StaviaEvidence> evidences
    ) {
        List<StaviaEvidence> catalogAssets =
                evidences.stream()
                        .filter(evidence ->
                                StaviaEvidenceTypes.ATIVO.equals(
                                        evidence.type()
                                )
                        )
                        .toList();

        if (!catalogAssets.isEmpty()) {
            StringBuilder answer =
                    new StringBuilder();

            int displayed =
                    Math.min(catalogAssets.size(), 10);

            answer.append(
                    catalogAssets.size() == 1
                            ? "Encontrei 1 ativo cadastrado na Zeladoria:"
                            : "Exibindo "
                                    + displayed
                                    + " ativos cadastrados na Zeladoria:"
            );

            for (int index = 0; index < displayed; index++) {
                Map<String, Object> attributes =
                        catalogAssets.get(index).attributes();

                answer.append("\n\n- ")
                        .append(
                                fallback(
                                        attributeText(
                                                attributes,
                                                "codigoAtivo"
                                        ),
                                        "sem código"
                                )
                        );

                appendInline(
                        answer,
                        attributeText(attributes, "nomeAtivo")
                );
                appendInline(
                        answer,
                        attributeText(attributes, "categoriaAtivo")
                );
            }

            if (catalogAssets.size() > displayed) {
                answer.append("\n\nHá pelo menos ")
                        .append(catalogAssets.size() - displayed)
                        .append(" ativos adicionais compatíveis; refine a pergunta por código, modelo ou categoria para reduzir a lista.");
            }

            return answer.toString();
        }

        StringBuilder answer =
                new StringBuilder();

        answer.append(
                evidences.size() == 1
                        ? "Foi identificado 1 registro de equipamento:"
                        : "Foram identificados "
                                + evidences.size()
                                + " registros de equipamento:"
        );

        int displayed =
                Math.min(evidences.size(), 10);

        for (int index = 0; index < displayed; index++) {
            answer.append("\n\n- ")
                    .append(
                            removeTerminalPeriod(
                                    evidences.get(index).summary()
                            )
                    );
        }

        if (evidences.size() > displayed) {
            answer.append("\n\nForam omitidos ")
                    .append(evidences.size() - displayed)
                    .append(" registros adicionais nesta resposta.");
        }

        return answer.toString();
    }

    private String worksiteText(Map<String, Object> attributes) {
        String code = fallback(
                attributeText(attributes, "codigoCw"),
                fallback(
                        attributeText(attributes, "codigoContrato"),
                        attributeText(attributes, "obraId")
                )
        );
        String name = attributeText(attributes, "obraNome");

        if (!hasText(code)) {
            return hasText(name) ? "obra " + name : "";
        }

        return hasText(name)
                ? "obra " + code + " (" + name + ")"
                : "obra " + code;
    }

    private String buildFrequencyAnswer(
            List<StaviaEvidence> evidences,
            StaviaIntent intent
    ) {
        if (evidences.isEmpty()) {
            return allocationEmptyAnswer(intent);
        }

        String classe =
                attributeText(
                        evidences.getFirst().attributes(),
                        "classe"
                );

        if ("SEM_DADOS_FREQUENCIA".equals(classe)) {
            return "Nenhuma ocorrência de frequência foi encontrada para a obra no período consultado.";
        }

        if ("SEM_DADOS_BANCO_HORAS".equals(classe)) {
            return "Nenhum saldo de banco de horas foi encontrado para colaboradores alocados na obra no período consultado.";
        }

        StringBuilder answer =
                new StringBuilder();

        answer.append(
                intent == StaviaIntent.CONSULTAR_BANCO_HORAS
                        ? "Banco de horas encontrado:"
                        : "Ocorrências de frequência encontradas:"
        );

        int displayed =
                Math.min(evidences.size(), 10);

        for (int index = 0; index < displayed; index++) {
            StaviaEvidence evidence =
                    evidences.get(index);
            Map<String, Object> attributes =
                    evidence.attributes();
            String itemClass =
                    attributeText(attributes, "classe");

            answer.append("\n\n- ")
                    .append(
                            fallback(
                                    attributeText(
                                            attributes,
                                            "colaboradorNome"
                                    ),
                                    "Colaborador sem nome"
                            )
                    );

            if ("BANCO_HORAS".equals(itemClass)) {
                appendInline(
                        answer,
                        "período "
                                + formatDate(
                                        attributeText(
                                                attributes,
                                                "periodoInicio"
                                        )
                                )
                                + " a "
                                + formatDate(
                                        attributeText(
                                                attributes,
                                                "periodoFim"
                                        )
                                )
                );
                appendInline(
                        answer,
                        "saldo "
                                + hoursText(
                                        attributes.get(
                                                "minutosSaldoAtual"
                                        )
                                )
                );
                appendInline(
                        answer,
                        "crédito "
                                + hoursText(
                                        attributes.get(
                                                "minutosCredito"
                                        )
                                )
                );
                appendInline(
                        answer,
                        "débito "
                                + hoursText(
                                        attributes.get(
                                                "minutosDebito"
                                        )
                                )
                );
                continue;
            }

            appendInline(
                    answer,
                    formatDate(
                            attributeText(attributes, "data")
                    )
            );
            appendInline(
                    answer,
                    readableStatus(
                            attributeText(attributes, "tipo")
                    )
            );
            appendInline(
                    answer,
                    hoursText(attributes.get("minutos"))
            );
            appendInline(
                    answer,
                    readableStatus(
                            attributeText(attributes, "status")
                    )
            );
            appendInline(
                    answer,
                    attributeText(attributes, "justificativa")
            );
        }

        if (evidences.size() > displayed) {
            answer.append("\n\nForam omitidos ")
                    .append(evidences.size() - displayed)
                    .append(" registros adicionais nesta resposta.");
        }

        answer.append("\n\nNota: frequência operacional e banco de horas no Córtex não substituem o registro oficial de ponto ou folha.");

        return answer.toString();
    }

    private String allocationEmptyAnswer(StaviaIntent intent) {
        return switch (intent) {
            case CONSULTAR_FREQUENCIA ->
                    "Nenhum registro de frequência ou alocação temporal foi encontrado para esta obra no recorte consultado.";
            case CONSULTAR_BANCO_HORAS ->
                    "Nenhum saldo, ajuste de banco de horas ou alocação temporal foi encontrado para esta obra no recorte consultado.";
            default ->
                    "Nenhuma alocação de colaborador foi encontrada para esta obra no recorte consultado.";
        };
    }

    private String allocationOpening(StaviaIntent intent) {
        return switch (intent) {
            case CONSULTAR_FREQUENCIA ->
                    "A frequência operacional encontrada";
            case CONSULTAR_BANCO_HORAS ->
                    "A base temporal encontrada para banco de horas";
            default ->
                    "A alocação de colaboradores encontrada";
        };
    }

    private String timeRange(Map<String, Object> attributes) {
        String start =
                attributeText(attributes, "horaInicio");
        String end =
                attributeText(attributes, "horaFim");

        if (hasText(start) && hasText(end)) {
            return start + " a " + end;
        }

        if (hasText(start)) {
            return "início " + start;
        }

        if (hasText(end)) {
            return "fim " + end;
        }

        return "";
    }

    private String hoursText(Object value) {
        BigDecimal minutes =
                decimalValue(value);

        if (minutes == null) {
            return "";
        }

        BigDecimal hours =
                minutes.divide(
                        BigDecimal.valueOf(60),
                        2,
                        java.math.RoundingMode.HALF_UP
                ).stripTrailingZeros();

        return hours.toPlainString() + "h";
    }

    private String insufficientPdocAnswer(
            String code,
            Map<String, Object> attributes
    ) {
        StringBuilder answer =
                new StringBuilder();

        answer.append("O PDOC foi executado para a obra ")
                .append(code)
                .append(", mas não conseguiu calcular o risco de estouro de custos porque ainda não há dados suficientes.");

        appendPdocReference(answer, attributes);

        List<String> missing =
                pdocMissingLabels(
                        attributes.get("missingRequiredFields")
                );

        if (!missing.isEmpty()) {
            answer.append("\n\nDados ausentes ou insuficientes:");

            for (String item : missing) {
                answer.append("\n- ")
                        .append(item)
                        .append(";");
            }

            int lastSemicolon = answer.lastIndexOf(";");

            if (lastSemicolon >= 0) {
                answer.replace(lastSemicolon, lastSemicolon + 1, ".");
            }
        }

        answer.append("\n\nPor isso, P50, P80, P95 e probabilidades de estouro não foram calculados.");

        return answer.toString();
    }

    private String successfulPdocAnswer(
            String code,
            Map<String, Object> attributes
    ) {
        StringBuilder answer =
                new StringBuilder();

        answer.append("O PDOC calculou o risco de estouro de custos da obra ")
                .append(code)
                .append(".");

        appendPdocReference(answer, attributes);

        appendMetric(
                answer,
                "Status",
                attributeText(attributes, "statusExecucaoLabel")
        );
        appendMetric(
                answer,
                "Versão do modelo",
                attributeText(attributes, "versaoModelo")
        );
        appendMetric(
                answer,
                "Versão das premissas",
                attributeText(attributes, "versaoPremissas")
        );
        appendMetric(
                answer,
                "Confiança",
                percentText(attributes.get("confidence"))
        );
        appendMetric(answer, "P50", moneyText(attributes.get("costP50")));
        appendMetric(answer, "P80", moneyText(attributes.get("costP80")));
        appendMetric(answer, "P95", moneyText(attributes.get("costP95")));
        appendMetric(
                answer,
                "Probabilidade de exceder 5%",
                percentText(
                        attributes.get("probabilityOverFivePercent")
                )
        );
        appendMetric(
                answer,
                "Probabilidade de exceder 10%",
                percentText(
                        attributes.get("probabilityOverTenPercent")
                )
        );
        appendMetric(
                answer,
                "Score heurístico",
                percentText(attributes.get("heuristicRiskScore"))
        );

        return answer.toString();
    }

    private void appendPdocReference(
            StringBuilder answer,
            Map<String, Object> attributes
    ) {
        String reference =
                formatDate(
                        attributeText(
                                attributes,
                                "dataReferencia"
                        )
                );

        if (hasText(reference)) {
            answer.append("\n\nReferência: ")
                    .append(reference)
                    .append(".");
        }
    }

    private void appendMetric(
            StringBuilder answer,
            String label,
            String value
    ) {
        if (!hasText(value)) {
            return;
        }

        if (answer.indexOf("\n\nIndicadores:") < 0) {
            answer.append("\n\nIndicadores:");
        }

        answer.append("\n- ")
                .append(label)
                .append(": ")
                .append(value);
    }

    private List<String> pdocMissingLabels(Object value) {
        if (!(value instanceof List<?> values)) {
            return List.of();
        }

        return values.stream()
                .map(String::valueOf)
                .map(this::pdocMissingLabel)
                .distinct()
                .toList();
    }

    private String pdocMissingLabel(String field) {
        return switch (field) {
            case "approvedBudget" ->
                    "orçamento aprovado";
            case "actualCost" ->
                    "custo realizado";
            case "committedCost" ->
                    "custo comprometido";
            case "actualExecutedQuantity" ->
                    "produção real validada";
            case "totalPlannedQuantity" ->
                    "quantidade total planejada";
            case "plannedExecutedQuantity" ->
                    "quantidade planejada até a referência";
            default ->
                    field;
        };
    }

    private long commitSequence(StaviaEvidence evidence) {
        Object value = evidence.attributes().get("commitSequence");

        if (value instanceof Number number) {
            return number.longValue();
        }

        try {
            return Long.parseLong(String.valueOf(value));
        } catch (RuntimeException ignored) {
            return Long.MAX_VALUE;
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> payload(StaviaEvidence evidence) {
        Object payload = evidence.attributes().get("payload");

        if (payload instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }

        return Map.of();
    }

    private String payloadText(
            StaviaEvidence evidence,
            String key
    ) {
        return stringValue(payload(evidence).get(key));
    }

    private String formatInstant(Instant value) {
        if (value == null) {
            return "Data não informada";
        }

        return DATE_TIME_FORMAT.format(
                value.atZone(BUSINESS_ZONE)
        );
    }

    private String formatDate(String value) {
        if (!hasText(value)) {
            return "";
        }

        try {
            return DATE_FORMAT.format(LocalDate.parse(value));
        } catch (DateTimeParseException ignored) {
            return value;
        }
    }

    private String readableStatus(String value) {
        if (!hasText(value)) {
            return "";
        }

        String normalized = value.trim()
                .replace('_', ' ')
                .toLowerCase(Locale.ROOT);

        return normalized.substring(0, 1).toUpperCase(Locale.ROOT)
                + normalized.substring(1);
    }

    private String displayRdoNumber(String value) {
        if (!hasText(value)) {
            return "RDO sem número";
        }

        String trimmed = value.trim();

        return trimmed.toUpperCase(Locale.ROOT).startsWith("RDO")
                ? trimmed
                : "RDO " + trimmed;
    }

    private String stringValue(Object value) {
        if (value == null) {
            return "";
        }

        return String.valueOf(value).trim();
    }

    private String fallback(
            String preferred,
            String alternative
    ) {
        return hasText(preferred) ? preferred.trim() : alternative;
    }

    private void appendInline(
            StringBuilder builder,
            String value
    ) {
        if (hasText(value)) {
            builder.append(" - ")
                    .append(value);
        }
    }

    private String moneyText(Object value) {
        BigDecimal decimal = decimalValue(value);

        if (decimal == null) {
            return "";
        }

        return "R$ " + decimal.toPlainString();
    }

    private String numberText(Object value) {
        BigDecimal decimal = decimalValue(value);

        if (decimal == null) {
            return "";
        }

        return decimal.toPlainString();
    }

    private String percentText(Object value) {
        BigDecimal decimal = decimalValue(value);

        if (decimal == null) {
            return "";
        }

        return decimal.multiply(BigDecimal.valueOf(100))
                .stripTrailingZeros()
                .toPlainString()
                + "%";
    }

    private BigDecimal decimalValue(Object value) {
        if (value instanceof BigDecimal decimal) {
            return decimal.stripTrailingZeros();
        }

        if (value instanceof Number number) {
            return BigDecimal.valueOf(number.doubleValue())
                    .stripTrailingZeros();
        }

        if (value == null || String.valueOf(value).isBlank()) {
            return null;
        }

        try {
            return new BigDecimal(String.valueOf(value))
                    .stripTrailingZeros();
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private List<StaviaEvidence> focusEvidence(
            StaviaQuestion question,
            StaviaIntent intent,
            List<StaviaEvidence> evidences
    ) {
        if (intent == StaviaIntent.CONSULTAR_OBRA) {
            return preferType(
                    evidences,
                    StaviaEvidenceTypes.OBRA
            );
        }

        if (intent == StaviaIntent.CONSULTAR_RDO) {
            List<StaviaEvidence> attributeEvidence =
                    preferType(
                            evidences,
                            StaviaEvidenceTypes.RDO_ATTRIBUTE
                    );

            if (!attributeEvidence.equals(evidences)) {
                return attributeEvidence;
            }

            List<StaviaEvidence> directRdos =
                    evidences.stream()
                            .filter(evidence ->
                                    StaviaEvidenceTypes.RDO.equals(
                                            evidence.type()
                                    )
                            )
                            .toList();

            if (!directRdos.isEmpty()) {
                return directRdos;
            }
        }

        if (intent == StaviaIntent.CONSULTAR_PROGRAMACAO) {
            List<StaviaEvidence> directRdos =
                    preferType(
                            evidences,
                            StaviaEvidenceTypes.RDO
                    );

            if (!directRdos.equals(evidences)) {
                return directRdos;
            }

            return preferRelation(evidences, "GERADO_A_PARTIR_DE");
        }

        if (intent == StaviaIntent.CONSULTAR_HISTORICO) {
            return preferType(
                    evidences,
                    StaviaEvidenceTypes.EVENTO_OPERACIONAL
            );
        }

        if (
                intent == StaviaIntent.CONSULTAR_RECEITA
                        || intent == StaviaIntent.CONSULTAR_MARGEM
                        || intent == StaviaIntent.CONSULTAR_PREVISAO_FINANCEIRA
                        || intent == StaviaIntent.CONSULTAR_PRODUCAO
                        || intent == StaviaIntent.CONSULTAR_RECEITA_EM_RISCO
        ) {
            List<StaviaEvidence> financial =
                    preferType(
                            evidences,
                            StaviaEvidenceTypes.PREVISAO_FINANCEIRA
                    );

            return financial.equals(evidences)
                    ? preferType(evidences, StaviaEvidenceTypes.PDOC)
                    : financial;
        }

        if (
                intent == StaviaIntent.CONSULTAR_ALOCACAO_COLABORADOR
                        || intent == StaviaIntent.CONSULTAR_FREQUENCIA
                        || intent == StaviaIntent.CONSULTAR_BANCO_HORAS
        ) {
            if (
                    intent == StaviaIntent.CONSULTAR_FREQUENCIA
                            || intent == StaviaIntent.CONSULTAR_BANCO_HORAS
            ) {
                List<StaviaEvidence> frequency =
                        preferType(
                                evidences,
                                StaviaEvidenceTypes.FREQUENCIA
                        );

                if (!frequency.equals(evidences)) {
                    return frequency;
                }
            }

            return preferType(
                    evidences,
                    StaviaEvidenceTypes.ALOCACAO_COLABORADOR
            );
        }

        if (intent == StaviaIntent.CONSULTAR_ATIVO) {
            String normalizedQuestion =
                    normalizeText(question.text());

            if (containsAny(
                    normalizedQuestion,
                    "zeladoria",
                    "cadastro",
                    "ativo cadastrado",
                    "ativos cadastrados",
                    "equipamento cadastrado",
                    "equipamentos cadastrados"
            )) {
                return preferType(
                        evidences,
                        StaviaEvidenceTypes.ATIVO
                );
            }

            return preferType(
                    evidences,
                    StaviaEvidenceTypes.EQUIPAMENTO
            );
        }

        return evidences;
    }

    private List<StaviaEvidence> preferRelation(
            List<StaviaEvidence> evidences,
            String relationType
    ) {
        List<StaviaEvidence> matching =
                evidences.stream()
                        .filter(evidence ->
                                StaviaEvidenceTypes
                                        .RELACAO_ONTOLOGICA
                                        .equals(evidence.type())
                        )
                        .filter(evidence ->
                                relationType.equals(
                                        String.valueOf(
                                                evidence.attributes()
                                                        .get(
                                                                "relationType"
                                                        )
                                        )
                                )
                        )
                        .toList();

        return matching.isEmpty()
                ? evidences
                : matching;
    }

    private List<StaviaEvidence> preferType(
            List<StaviaEvidence> evidences,
            String evidenceType
    ) {
        List<StaviaEvidence> matching =
                evidences.stream()
                        .filter(evidence ->
                                evidenceType.equals(
                                        evidence.type()
                                )
                        )
                        .toList();

        return matching.isEmpty()
                ? evidences
                : matching;
    }

    private boolean hasEvidenceType(
            List<StaviaEvidence> evidences,
            String evidenceType
    ) {
        return evidences.stream()
                .anyMatch(evidence ->
                        evidenceType.equals(evidence.type())
                );
    }

    private String buildWorksiteSummary(
            StaviaQuestion question,
            List<StaviaEvidence> evidences
    ) {
        StaviaEvidence worksite =
                evidences.stream()
                        .filter(evidence ->
                                StaviaEvidenceTypes.OBRA.equals(
                                        evidence.type()
                                )
                        )
                        .findFirst()
                        .orElse(evidences.getFirst());

        Map<String, Object> attributes =
                worksite.attributes();

        String normalizedQuestion =
                normalizeText(question.text());

        String directAttribute =
                directWorksiteAttributeAnswer(
                        normalizedQuestion,
                        attributes
                );

        if (hasText(directAttribute)) {
            return directAttribute;
        }

        if (
                containsAny(
                        normalizedQuestion,
                        "quando comec",
                        "quando iniciou",
                        "data de inicio",
                        "inicio da obra"
                )
        ) {
            return "A data de início operacional da obra "
                    + "não está registrada no cadastro atual. "
                    + worksiteRegistrationDates(attributes);
        }

        if (
                containsAny(
                        normalizedQuestion,
                        "quando termin",
                        "data de termino",
                        "data de fim",
                        "fim da obra",
                        "previsao de termino"
                )
        ) {
            return "A data prevista de término operacional da obra "
                    + "não está registrada no cadastro atual. "
                    + worksiteRegistrationDates(attributes);
        }

        if (
                containsAny(
                        normalizedQuestion,
                        "data",
                        "quando",
                        "cadastro",
                        "cadastrada",
                        "criada",
                        "atualizada"
                )
        ) {
            return worksiteRegistrationDates(attributes);
        }

        if (
                containsAny(
                        normalizedQuestion,
                        "codigo cw",
                        "qual o cw",
                        "qual e o cw",
                        "codigo da obra",
                        "contrato"
                )
        ) {
            String codigoCw =
                    attributeText(
                            attributes,
                            "codigoCw"
                    );

            String codigoContrato =
                    attributeText(
                            attributes,
                            "codigoContrato"
                    );

            if (
                    hasText(codigoCw)
                    && hasText(codigoContrato)
                    && !codigoCw.equals(codigoContrato)
            ) {
                return "A obra possui código CW "
                        + codigoCw
                        + " e contrato "
                        + codigoContrato;
            }

            if (hasText(codigoCw)) {
                return "O código CW da obra é "
                        + codigoCw;
            }

            if (hasText(codigoContrato)) {
                return "O código de contrato da obra é "
                        + codigoContrato;
            }

            return "O cadastro atual não possui código CW "
                    + "nem código de contrato disponível";
        }

        if (
                containsAny(
                        normalizedQuestion,
                        "status",
                        "situacao",
                        "como esta"
                )
        ) {
            String status =
                    attributeText(
                            attributes,
                            "status"
                    );

            return hasText(status)
                    ? "O status cadastrado da obra é "
                            + status
                    : "O cadastro atual não possui status disponível";
        }

        if (
                containsAny(
                        normalizedQuestion,
                        "onde",
                        "cidade",
                        "local",
                        "localizacao",
                        "rodovia",
                        "uf"
                )
        ) {
            String cidade =
                    attributeText(
                            attributes,
                            "cidade"
                    );

            String uf =
                    attributeText(
                            attributes,
                            "uf"
                    );

            String rodovia =
                    attributeText(
                            attributes,
                            "rodovia"
                    );

            String location = "";

            if (hasText(cidade) && hasText(uf)) {
                location = cidade + "/" + uf;
            } else if (hasText(cidade)) {
                location = cidade;
            } else if (hasText(uf)) {
                location = uf;
            }

            if (hasText(location) && hasText(rodovia)) {
                return "A obra está cadastrada em "
                        + location
                        + ", na rodovia "
                        + rodovia;
            }

            if (hasText(location)) {
                return "A obra está cadastrada em "
                        + location;
            }

            if (hasText(rodovia)) {
                return "A rodovia cadastrada para a obra é "
                        + rodovia;
            }

            return "O cadastro atual não possui localização disponível";
        }

        if (
                containsAny(
                        normalizedQuestion,
                        "nome",
                        "qual obra",
                        "que obra"
                )
        ) {
            String nome =
                    attributeText(
                            attributes,
                            "nome"
                    );

            return hasText(nome)
                    ? "A obra cadastrada é "
                            + nome
                    : "O cadastro atual não possui nome disponível";
        }

        return removeTerminalPeriod(
                worksite.summary()
        );
    }

    private String directWorksiteAttributeAnswer(
            String normalizedQuestion,
            Map<String, Object> attributes
    ) {
        if (
                containsAny(
                        normalizedQuestion,
                        "cidade",
                        "municipio"
                )
        ) {
            return valueOrNotInformed(attributes, "cidade");
        }

        if (
                containsAny(
                        normalizedQuestion,
                        "cliente",
                        "contratante"
                )
        ) {
            return valueOrNotInformed(attributes, "cliente");
        }

        if (
                containsAny(
                        normalizedQuestion,
                        "rodovia",
                        "estrada"
                )
        ) {
            return valueOrNotInformed(attributes, "rodovia");
        }

        if (
                containsAny(
                        normalizedQuestion,
                        "uf",
                        "estado"
                )
        ) {
            return valueOrNotInformed(attributes, "uf");
        }

        if (
                containsAny(
                        normalizedQuestion,
                        "status",
                        "situacao"
                )
        ) {
            return valueOrNotInformed(attributes, "status");
        }

        if (
                containsAny(
                        normalizedQuestion,
                        "codigo cw",
                        "codigo da obra",
                        "codigo",
                        "contrato"
                )
        ) {
            String codigoCw =
                    attributeText(attributes, "codigoCw");
            String codigoContrato =
                    attributeText(attributes, "codigoContrato");
            String codigoInterno =
                    attributeText(attributes, "codigoInterno");

            if (containsAny(normalizedQuestion, "contrato")
                    && hasText(codigoContrato)) {
                return codigoContrato;
            }

            return fallback(
                    codigoCw,
                    fallback(codigoContrato, codigoInterno)
            );
        }

        if (
                containsAny(
                        normalizedQuestion,
                        "nome da obra",
                        "qual obra",
                        "que obra"
                )
        ) {
            return valueOrNotInformed(attributes, "nome");
        }

        return "";
    }

    private boolean asksForAtomicWorksiteAttribute(String question) {
        String normalizedQuestion =
                normalizeText(question);

        return containsAny(
                normalizedQuestion,
                "cidade",
                "municipio",
                "cliente",
                "contratante",
                "rodovia",
                "estrada",
                "uf",
                "estado",
                "status",
                "situacao",
                "codigo",
                "contrato",
                "nome da obra",
                "qual obra",
                "que obra"
        );
    }

    private String valueOrNotInformed(
            Map<String, Object> attributes,
            String key
    ) {
        String value = attributeText(attributes, key);

        return hasText(value) ? value : "Não informado";
    }

    private String worksiteRegistrationDates(
            Map<String, Object> attributes
    ) {
        String criadoEm =
                formatLocalDateTime(
                        attributeText(
                                attributes,
                                "criadoEm"
                        )
                );

        String atualizadoEm =
                formatLocalDateTime(
                        attributeText(
                                attributes,
                                "atualizadoEm"
                        )
                );

        if (
                hasText(criadoEm)
                && hasText(atualizadoEm)
                && criadoEm.equals(atualizadoEm)
        ) {
            return String.format(
                    "A obra foi cadastrada no Córtex em %s "
                            + "e ainda não possui uma atualização posterior. "
                            + "As datas de início e término operacional "
                            + "não estão registradas no cadastro atual",
                    criadoEm
            );
        }

        if (
                hasText(criadoEm)
                && hasText(atualizadoEm)
        ) {
            return "A obra foi cadastrada no Córtex em "
                    + criadoEm
                    + " e atualizada pela última vez em "
                    + atualizadoEm
                    + ". As datas de início e término operacional "
                    + "não estão registradas no cadastro atual";
        }

        if (hasText(criadoEm)) {
            return "A obra foi cadastrada no Córtex em "
                    + criadoEm
                    + ". As datas de início e término operacional "
                    + "não estão registradas no cadastro atual";
        }

        if (hasText(atualizadoEm)) {
            return "A última atualização cadastrada da obra ocorreu em "
                    + atualizadoEm
                    + ". As datas de início e término operacional "
                    + "não estão registradas no cadastro atual";
        }

        return "O cadastro atual não possui datas disponíveis "
                + "para esta obra";
    }

    private String formatLocalDateTime(
            String value
    ) {
        if (!hasText(value)) {
            return "";
        }

        try {
            LocalDateTime dateTime =
                    LocalDateTime.parse(value);

            return dateTime.format(
                    DateTimeFormatter.ofPattern(
                            "dd/MM/yyyy 'às' HH:mm"
                    )
            );
        } catch (DateTimeParseException ignored) {
            return value;
        }
    }

    private String attributeText(
            Map<String, Object> attributes,
            String key
    ) {
        Object value = attributes.get(key);

        return value == null
                ? ""
                : String.valueOf(value).trim();
    }

    private boolean containsAny(
            String value,
            String... candidates
    ) {
        for (String candidate : candidates) {
            if (value.contains(candidate)) {
                return true;
            }
        }

        return false;
    }

    private boolean hasText(
            String value
    ) {
        return value != null
                && !value.isBlank();
    }

    private String normalizeGeneratedAnswer(
            String answer
    ) {
        return answer
                .replaceAll(
                        "ainda[\\p{Cf}\\s]*não",
                        "ainda não"
                )
                .replaceAll(
                        "registro[\\p{Cf}\\s]*não",
                        "registro não"
                )
                .replaceAll(
                        "identificado[\\p{Cf}\\s]*no",
                        "identificado no"
                )
                .replaceAll("[ \\t]{2,}", " ")
                .trim();
    }

    private String normalizeText(String value) {
        String decomposed =
                Normalizer.normalize(
                        value,
                        Normalizer.Form.NFD
                );

        return decomposed
                .replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT);
    }

    private String removeTerminalPeriod(
            String summary
    ) {
        String normalized = summary.trim();

        while (normalized.endsWith(".")) {
            normalized = normalized.substring(
                    0,
                    normalized.length() - 1
            ).trim();
        }

        return normalized;
    }

    private String evidenceKey(
            StaviaEvidence evidence
    ) {
        return StaviaEvidenceKeys.key(evidence);
    }
}
