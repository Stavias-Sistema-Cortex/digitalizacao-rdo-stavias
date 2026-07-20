package com.projeto.cortex.intelligence.stavia.knowledge.asset;

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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class ZeladoriaAssetKnowledgeSource
        implements StaviaKnowledgeSource {

    private static final int MAX_ROWS = 50;
    private static final Pattern EXTERNAL_CODE =
            Pattern.compile("(?iu)\\b[A-Z]{2,4}-\\d{2,4}\\b");

    private static final Set<String> STOP_WORDS =
            Set.of(
                    "qual",
                    "quais",
                    "existe",
                    "existem",
                    "tem",
                    "estao",
                    "está",
                    "esta",
                    "cadastrado",
                    "cadastrados",
                    "cadastro",
                    "ativo",
                    "ativos",
                    "asset",
                    "assets",
                    "equipamento",
                    "equipamentos",
                    "maquina",
                    "maquinas",
                    "máquina",
                    "máquinas",
                    "zeladoria",
                    "cortex",
                    "córtex",
                    "obra",
                    "desta",
                    "nessa"
            );

    private final JdbcTemplate jdbcTemplate;

    public ZeladoriaAssetKnowledgeSource(
            JdbcTemplate jdbcTemplate
    ) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public String sourceName() {
        return "cadastro-ativos-zeladoria";
    }

    @Override
    public String sourceVersion() {
        return "STAVIA-ZELADORIA-ASSET-SOURCE-0.1.0";
    }

    @Override
    public StaviaSourceDescriptor descriptor() {
        return new StaviaSourceDescriptor(
                sourceName(),
                sourceVersion(),
                Set.of(QueryDomain.EQUIPAMENTO),
                Set.of("ATIVO", "EQUIPAMENTO"),
                Set.of(
                        "externalCode",
                        "name",
                        "category",
                        "active"
                ),
                Set.of("CADASTRADO_EM_ZELADORIA"),
                Set.of(
                        QueryOperation.READ_ATTRIBUTE,
                        QueryOperation.LIST_OBJECTS
                ),
                Set.of(StaviaEngine.REQUIRED_PERMISSION),
                "Sincronizado da Zeladoria para o Córtex",
                20,
                MAX_ROWS
        );
    }

    @Override
    public boolean supports(
            StaviaKnowledgeRequest request
    ) {
        if (request == null) {
            return false;
        }

        if (!request.permissions().contains(StaviaEngine.REQUIRED_PERMISSION)) {
            return false;
        }

        if (request.intent() != StaviaIntent.CONSULTAR_ATIVO
                && request.intent() != StaviaIntent.RESUMIR_OBRA) {
            return false;
        }

        if (request.plan().requiredSources().contains(sourceName())) {
            return true;
        }

        String normalized =
                StaviaText.normalize(request.question().text());

        return containsAny(
                normalized,
                "zeladoria",
                "cadastro de ativo",
                "cadastro de equipamento",
                "ativos cadastrados",
                "equipamentos cadastrados"
        );
    }

    @Override
    public List<StaviaEvidence> retrieve(
            StaviaKnowledgeRequest request
    ) {
        SqlFilter filter =
                assetFilter(request.question().text());

        String sql = """
                SELECT
                    id,
                    source_database,
                    source_table,
                    source_pk,
                    external_code,
                    name,
                    category,
                    active,
                    updated_at,
                    last_seen_at
                FROM asset
                WHERE deleted_at IS NULL
                  AND %s
                ORDER BY active DESC, external_code, name
                LIMIT ?
                """.formatted(filter.condition());

        List<Object> parameters =
                new ArrayList<>(filter.parameters());
        parameters.add(MAX_ROWS);

        return jdbcTemplate.query(
                sql,
                (rs, rowNumber) -> toEvidence(rs),
                parameters.toArray()
        );
    }

    private StaviaEvidence toEvidence(
            ResultSet rs
    ) throws SQLException {
        Map<String, Object> attributes =
                new LinkedHashMap<>();

        putText(attributes, "assetId", rs.getString("id"));
        putText(attributes, "bancoOrigem", rs.getString("source_database"));
        putText(attributes, "tabelaOrigem", rs.getString("source_table"));
        putText(attributes, "pkOrigem", rs.getString("source_pk"));
        putText(attributes, "codigoAtivo", rs.getString("external_code"));
        putText(attributes, "nomeAtivo", rs.getString("name"));
        putText(attributes, "categoriaAtivo", rs.getString("category"));
        attributes.put("ativo", rs.getBoolean("active"));

        Timestamp updatedAt =
                rs.getTimestamp("updated_at");

        Timestamp lastSeenAt =
                rs.getTimestamp("last_seen_at");

        if (updatedAt != null) {
            attributes.put(
                    "atualizadoEm",
                    updatedAt.toLocalDateTime().toString()
            );
        }

        if (lastSeenAt != null) {
            attributes.put(
                    "vistoPorUltimoEm",
                    lastSeenAt.toLocalDateTime().toString()
            );
        }

        return new StaviaEvidence(
                StaviaEvidenceTypes.ATIVO,
                "ATIVO_ZELADORIA:" + rs.getString("id"),
                summary(attributes),
                updatedAt == null
                        ? null
                        : updatedAt.toLocalDateTime()
                                .toInstant(ZoneOffset.UTC),
                true,
                attributes
        );
    }

    private SqlFilter assetFilter(
            String question
    ) {
        Matcher codeMatcher =
                EXTERNAL_CODE.matcher(question == null ? "" : question);

        if (codeMatcher.find()) {
            String code =
                    codeMatcher.group().toUpperCase(Locale.ROOT);

            return new SqlFilter(
                    "UPPER(external_code) = ?",
                    List.of(code)
            );
        }

        String normalized =
                StaviaText.normalize(question);

        String[] tokens =
                StaviaText.tokenize(normalized);

        List<String> meaningful =
                new ArrayList<>();

        for (String token : tokens) {
            if (token.length() >= 3
                    && !STOP_WORDS.contains(token)) {
                meaningful.add(token.toLowerCase(Locale.ROOT));
            }
        }

        if (meaningful.isEmpty()) {
            return new SqlFilter("active = 1", List.of());
        }

        String condition =
                meaningful.stream()
                        .map(ignored ->
                                """
                                (
                                    LOWER(COALESCE(external_code, '')) LIKE ?
                                    OR LOWER(name) LIKE ?
                                    OR LOWER(COALESCE(category, '')) LIKE ?
                                )
                                """
                        )
                        .collect(java.util.stream.Collectors.joining(" AND "));

        List<Object> parameters =
                new ArrayList<>();

        for (String token : meaningful) {
            String pattern =
                    "%" + token + "%";
            parameters.add(pattern);
            parameters.add(pattern);
            parameters.add(pattern);
        }

        return new SqlFilter(condition, parameters);
    }

    private String summary(
            Map<String, Object> attributes
    ) {
        StringBuilder summary =
                new StringBuilder();

        summary.append(
                value(attributes, "nomeAtivo", "Ativo sem nome")
        ).append(" está cadastrado na Zeladoria");

        appendInline(
                summary,
                "código",
                value(attributes, "codigoAtivo", null)
        );
        appendInline(
                summary,
                "categoria",
                value(attributes, "categoriaAtivo", null)
        );

        Object active =
                attributes.get("ativo");

        if (active instanceof Boolean booleanValue) {
            summary.append(booleanValue
                    ? ". Cadastro ativo."
                    : ". Cadastro inativo.");
        }

        return summary.toString();
    }

    private void appendInline(
            StringBuilder builder,
            String label,
            String value
    ) {
        if (value != null && !value.isBlank()) {
            builder.append(", ")
                    .append(label)
                    .append(" ")
                    .append(value.trim());
        }
    }

    private String value(
            Map<String, Object> attributes,
            String key,
            String fallback
    ) {
        Object value =
                attributes.get(key);

        if (value == null || String.valueOf(value).isBlank()) {
            return fallback;
        }

        return String.valueOf(value).trim();
    }

    private void putText(
            Map<String, Object> attributes,
            String key,
            String value
    ) {
        if (value != null && !value.isBlank()) {
            attributes.put(key, value.trim());
        }
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

    private record SqlFilter(
            String condition,
            List<Object> parameters
    ) {
    }
}
