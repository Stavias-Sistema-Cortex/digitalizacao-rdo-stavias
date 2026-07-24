package com.projeto.cortex.intelligence.stavia.knowledge.segment;

import com.projeto.cortex.intelligence.stavia.StaviaEngine;
import com.projeto.cortex.intelligence.stavia.intent.StaviaIntent;
import com.projeto.cortex.intelligence.stavia.knowledge.StaviaKnowledgeRequest;
import com.projeto.cortex.intelligence.stavia.knowledge.StaviaKnowledgeSource;
import com.projeto.cortex.intelligence.stavia.knowledge.registry.StaviaSourceDescriptor;
import com.projeto.cortex.intelligence.stavia.model.StaviaEvidence;
import com.projeto.cortex.intelligence.stavia.model.StaviaEvidenceTypes;
import com.projeto.cortex.intelligence.stavia.planning.QueryDomain;
import com.projeto.cortex.intelligence.stavia.planning.QueryOperation;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Retrieves RDO geometric controls that overlap a kilometre range mentioned in
 * the question. It is local-first: no geocoding or map provider is needed for
 * an already known road/km range.
 */
@Component
public class SegmentKnowledgeSource implements StaviaKnowledgeSource {

    private static final Pattern KM_PATTERN = Pattern.compile(
            "(?iu)\\bkm\\s*(\\d+(?:[.,]\\d+)?(?:\\+\\d+)?)"
    );
    private final JdbcTemplate jdbcTemplate;

    public SegmentKnowledgeSource(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public String sourceName() {
        return "trechos-operacionais";
    }

    @Override
    public String sourceVersion() {
        return "STAVIA-SEGMENT-SOURCE-0.2.0";
    }

    @Override
    public StaviaSourceDescriptor descriptor() {
        return new StaviaSourceDescriptor(
                sourceName(),
                sourceVersion(),
                Set.of(QueryDomain.RDO),
                Set.of(StaviaEvidenceTypes.TRECHO_OPERACIONAL),
                Set.of(
                        "subtrecho",
                        "kmInicial",
                        "kmFinal",
                        "comprimentoM",
                        "areaM2",
                        "volumeM3"
                ),
                Set.of("OCORRE_NO_TRECHO"),
                Set.of(QueryOperation.READ_ATTRIBUTE),
                Set.of(StaviaEngine.REQUIRED_PERMISSION),
                "Sob demanda",
                10,
                500
        );
    }

    @Override
    public boolean supports(StaviaKnowledgeRequest request) {
        return request != null
                && request.permissions().contains(StaviaEngine.REQUIRED_PERMISSION)
                && request.intent() == StaviaIntent.CONSULTAR_RDO
                && request.plan().requiredSources().contains(sourceName());
    }

    @Override
    public List<StaviaEvidence> retrieve(StaviaKnowledgeRequest request) {
        Optional<KilometreRange> requestedRange =
                KilometreRange.from(request.question().text());
        boolean aggregateMeasurement =
                request.plan().aggregations()
                        .stream()
                        .anyMatch(aggregation ->
                                "SUM".equalsIgnoreCase(
                                        aggregation.function()
                                )
                                        && (
                                        "areaM2".equals(aggregation.attribute())
                                                || "volumeM3".equals(
                                                        aggregation.attribute()
                                                )
                                )
                        );

        if (requestedRange.isEmpty() && !aggregateMeasurement) {
            return List.of();
        }

        List<StaviaEvidence> segments = jdbcTemplate.query(
                        """
                        SELECT
                            cg.id,
                            r.id AS rdo_id,
                            r.numero_rdo,
                            r.data_rdo,
                            r.status,
                            r.rodovia,
                            cg.subtrecho,
                            cg.km_inicial,
                            cg.km_final,
                            cg.comprimento_m,
                            cg.largura_m,
                            cg.area_m2,
                            cg.volume_m3,
                            cg.observacoes,
                            cg.atualizado_em
                        FROM rdo_controle_geometrico cg
                        JOIN rdo r
                          ON r.id = cg.rdo_id
                        WHERE r.obra_id = ?
                        ORDER BY r.data_rdo DESC, cg.atualizado_em DESC
                        LIMIT 500
                        """,
                        (rs, rowNumber) -> toEvidence(
                                rs,
                                request.worksiteId(),
                                requestedRange
                        ),
                        request.worksiteId()
                )
                .stream()
                .filter(java.util.Objects::nonNull)
                .toList();

        if (!segments.isEmpty()) {
            return segments;
        }

        return requestedRange
                .map(range ->
                        List.of(noSegmentEvidence(request.worksiteId(), range))
                )
                .orElseGet(() ->
                        List.of(noGeometricControlEvidence(request.worksiteId()))
                );
    }

    private StaviaEvidence noSegmentEvidence(
            String worksiteId,
            KilometreRange requestedRange
    ) {
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("obraId", worksiteId);
        attributes.put("classe", "SEM_DADOS_TRECHO");
        attributes.put("kmConsultaInicial", requestedRange.start());
        attributes.put("kmConsultaFinal", requestedRange.end());

        String range = requestedRange.start().stripTrailingZeros().toPlainString()
                + " a "
                + requestedRange.end().stripTrailingZeros().toPlainString();

        return new StaviaEvidence(
                StaviaEvidenceTypes.TRECHO_OPERACIONAL,
                "TRECHO_OPERACIONAL:SEM_DADOS:" + worksiteId + ":" + range,
                "Nenhum controle geométrico foi localizado entre os km "
                        + range + ".",
                null,
                true,
                attributes
        );
    }

    private StaviaEvidence noGeometricControlEvidence(String worksiteId) {
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("obraId", worksiteId);
        attributes.put("classe", "SEM_DADOS_CONTROLE_GEOMETRICO");

        return new StaviaEvidence(
                StaviaEvidenceTypes.TRECHO_OPERACIONAL,
                "TRECHO_OPERACIONAL:SEM_DADOS_CONTROLE:" + worksiteId,
                "Nenhum controle geométrico com área ou volume foi localizado.",
                null,
                true,
                attributes
        );
    }

    private StaviaEvidence toEvidence(
            ResultSet rs,
            String worksiteId,
            Optional<KilometreRange> requestedRange
    ) throws SQLException {
        Optional<KilometreRange> recordRange = KilometreRange.from(
                rs.getString("km_inicial"),
                rs.getString("km_final")
        );

        if (requestedRange.isPresent()
                && (recordRange.isEmpty()
                || !recordRange.get().overlaps(requestedRange.get()))) {
            return null;
        }

        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("obraId", worksiteId);
        putText(attributes, "rdoId", rs.getString("rdo_id"));
        putText(attributes, "numeroRdo", rs.getString("numero_rdo"));
        if (rs.getDate("data_rdo") != null) {
            attributes.put("dataRdo", rs.getDate("data_rdo").toLocalDate().toString());
        }
        putText(attributes, "status", rs.getString("status"));
        putText(attributes, "rodovia", rs.getString("rodovia"));
        putText(attributes, "subtrecho", rs.getString("subtrecho"));
        putText(attributes, "kmInicial", rs.getString("km_inicial"));
        putText(attributes, "kmFinal", rs.getString("km_final"));
        put(attributes, "comprimentoM", rs.getBigDecimal("comprimento_m"));
        put(attributes, "larguraM", rs.getBigDecimal("largura_m"));
        put(attributes, "areaM2", rs.getBigDecimal("area_m2"));
        put(attributes, "volumeM3", rs.getBigDecimal("volume_m3"));
        putText(attributes, "observacoes", rs.getString("observacoes"));

        String range = displayRange(
                rs.getString("km_inicial"),
                rs.getString("km_final")
        );
        String number = rs.getString("numero_rdo");
        String summary = "Controle geométrico " + range
                + " registrado no RDO "
                + (hasText(number) ? number : rs.getString("rdo_id"))
                + ".";

        return new StaviaEvidence(
                StaviaEvidenceTypes.TRECHO_OPERACIONAL,
                "TRECHO_OPERACIONAL:" + rs.getString("id"),
                summary,
                rs.getTimestamp("atualizado_em") == null
                        ? null
                        : rs.getTimestamp("atualizado_em")
                                .toLocalDateTime()
                                .toInstant(ZoneOffset.UTC),
                !"RASCUNHO".equals(rs.getString("status")),
                attributes
        );
    }

    private String displayRange(String start, String end) {
        if (hasText(start) && hasText(end)) {
            return "do km " + start + " ao km " + end;
        }
        return hasText(start) ? "no km " + start : "com km não informado";
    }

    private void putText(Map<String, Object> attributes, String key, String value) {
        if (hasText(value)) {
            attributes.put(key, value.trim());
        }
    }

    private void put(Map<String, Object> attributes, String key, BigDecimal value) {
        if (value != null) {
            attributes.put(key, value);
        }
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    static record KilometreRange(BigDecimal start, BigDecimal end) {

        static Optional<KilometreRange> from(String question) {
            Matcher matcher = KM_PATTERN.matcher(question == null ? "" : question);
            if (!matcher.find()) {
                return Optional.empty();
            }

            BigDecimal first = parse(matcher.group(1));
            if (first == null) {
                return Optional.empty();
            }

            BigDecimal second = matcher.find() ? parse(matcher.group(1)) : first;
            if (second == null) {
                second = first;
            }

            return Optional.of(new KilometreRange(
                    first.min(second),
                    first.max(second)
            ));
        }

        static Optional<KilometreRange> from(String start, String end) {
            BigDecimal parsedStart = parse(start);
            BigDecimal parsedEnd = parse(end);

            if (parsedStart == null && parsedEnd == null) {
                return Optional.empty();
            }

            if (parsedStart == null) {
                parsedStart = parsedEnd;
            }
            if (parsedEnd == null) {
                parsedEnd = parsedStart;
            }

            return Optional.of(new KilometreRange(
                    parsedStart.min(parsedEnd),
                    parsedStart.max(parsedEnd)
            ));
        }

        boolean overlaps(KilometreRange other) {
            return start.compareTo(other.end) <= 0
                    && end.compareTo(other.start) >= 0;
        }

        private static BigDecimal parse(String value) {
            if (value == null || value.isBlank()) {
                return null;
            }

            String normalized = value
                    .replaceAll("(?iu)km", "")
                    .trim()
                    .replace(',', '.');

            try {
                if (normalized.contains("+")) {
                    String[] parts = normalized.split("\\+", 2);
                    return new BigDecimal(parts[0].trim()).add(
                            new BigDecimal(parts[1].trim())
                                    .divide(BigDecimal.valueOf(1000))
                    );
                }
                return new BigDecimal(normalized);
            } catch (NumberFormatException exception) {
                return null;
            }
        }
    }
}
