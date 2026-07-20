package com.projeto.cortex.intelligence.stavia.knowledge.collaborator;

import com.projeto.cortex.intelligence.stavia.StaviaEngine;
import com.projeto.cortex.intelligence.stavia.intent.StaviaIntent;
import com.projeto.cortex.intelligence.stavia.interpret.StaviaEntityFilters;
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

@Component
public class AcademyCollaboratorKnowledgeSource
        implements StaviaKnowledgeSource {

    private static final int MAX_ROWS = 50;

    private final JdbcTemplate jdbcTemplate;

    public AcademyCollaboratorKnowledgeSource(
            JdbcTemplate jdbcTemplate
    ) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public String sourceName() {
        return "cadastro-colaboradores-academy";
    }

    @Override
    public String sourceVersion() {
        return "STAVIA-ACADEMY-COLLABORATOR-SOURCE-0.1.0";
    }

    @Override
    public StaviaSourceDescriptor descriptor() {
        return new StaviaSourceDescriptor(
                sourceName(),
                sourceVersion(),
                Set.of(QueryDomain.COLABORADOR),
                Set.of("COLABORADOR"),
                Set.of(
                        "nome",
                        "codigoColaborador",
                        "nomeGrupo",
                        "nomePerfil",
                        "ativo"
                ),
                Set.of("PERFIL_COLABORADOR"),
                Set.of(
                        QueryOperation.READ_ATTRIBUTE,
                        QueryOperation.LIST_OBJECTS,
                        QueryOperation.TRAVERSE_RELATIONSHIP
                ),
                Set.of(StaviaEngine.REQUIRED_PERMISSION),
                "Sincronizado do Academy para o Córtex",
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

        if (request.intent() != StaviaIntent.CONSULTAR_ALOCACAO_COLABORADOR
                && request.intent() != StaviaIntent.RESUMIR_OBRA) {
            return false;
        }

        if (request.plan().requiredSources().contains(sourceName())) {
            return true;
        }

        StaviaEntityFilters filters =
                StaviaEntityFilters.from(request.plan().entities());

        return request.plan().domain() == QueryDomain.COLABORADOR
                && filters.collaboratorName().isPresent();
    }

    @Override
    public List<StaviaEvidence> retrieve(
            StaviaKnowledgeRequest request
    ) {
        StaviaEntityFilters filters =
                StaviaEntityFilters.from(request.plan().entities());

        String collaborator =
                filters.collaboratorName().orElse(null);

        if (collaborator == null || collaborator.isBlank()) {
            return listActiveCollaborators();
        }

        return findCollaboratorsByName(collaborator);
    }

    private List<StaviaEvidence> findCollaboratorsByName(
            String collaborator
    ) {
        SqlFilter filter =
                collaboratorFilter(collaborator);

        String sql = """
                SELECT
                    id,
                    banco_origem,
                    tabela_origem,
                    pk_origem,
                    codigo_colaborador,
                    nome,
                    email,
                    nome_grupo,
                    nome_perfil,
                    ativo,
                    atualizado_em,
                    visto_por_ultimo_em
                FROM colaborador
                WHERE deletado_em IS NULL
                  AND %s
                ORDER BY ativo DESC, nome
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

    private List<StaviaEvidence> listActiveCollaborators() {
        return jdbcTemplate.query(
                """
                SELECT
                    id,
                    banco_origem,
                    tabela_origem,
                    pk_origem,
                    codigo_colaborador,
                    nome,
                    email,
                    nome_grupo,
                    nome_perfil,
                    ativo,
                    atualizado_em,
                    visto_por_ultimo_em
                FROM colaborador
                WHERE deletado_em IS NULL
                  AND ativo = 1
                ORDER BY nome
                LIMIT ?
                """,
                (rs, rowNumber) -> toEvidence(rs),
                MAX_ROWS
        );
    }

    private StaviaEvidence toEvidence(
            ResultSet rs
    ) throws SQLException {
        Map<String, Object> attributes =
                new LinkedHashMap<>();

        putText(attributes, "colaboradorId", rs.getString("id"));
        putText(attributes, "bancoOrigem", rs.getString("banco_origem"));
        putText(attributes, "tabelaOrigem", rs.getString("tabela_origem"));
        putText(attributes, "pkOrigem", rs.getString("pk_origem"));
        putText(attributes, "codigoColaborador", rs.getString("codigo_colaborador"));
        putText(attributes, "colaboradorNome", rs.getString("nome"));
        putText(attributes, "email", rs.getString("email"));
        putText(attributes, "nomeGrupo", rs.getString("nome_grupo"));
        putText(attributes, "nomePerfil", rs.getString("nome_perfil"));
        attributes.put("ativo", rs.getBoolean("ativo"));

        Timestamp updatedAt =
                rs.getTimestamp("atualizado_em");

        Timestamp lastSeenAt =
                rs.getTimestamp("visto_por_ultimo_em");

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
                StaviaEvidenceTypes.COLABORADOR,
                "COLABORADOR_ACADEMY:" + rs.getString("id"),
                summary(attributes),
                updatedAt == null
                        ? null
                        : updatedAt.toLocalDateTime()
                                .toInstant(ZoneOffset.UTC),
                true,
                attributes
        );
    }

    private String summary(
            Map<String, Object> attributes
    ) {
        StringBuilder summary =
                new StringBuilder();

        summary.append(
                value(attributes, "colaboradorNome", "Colaborador sem nome")
        ).append(" está cadastrado no Academy");

        appendInline(
                summary,
                "código",
                value(attributes, "codigoColaborador", null)
        );
        appendInline(
                summary,
                "grupo",
                value(attributes, "nomeGrupo", null)
        );
        appendInline(
                summary,
                "perfil",
                value(attributes, "nomePerfil", null)
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

    private SqlFilter collaboratorFilter(
            String collaborator
    ) {
        String normalized =
                StaviaText.normalize(collaborator);

        String[] tokens =
                StaviaText.tokenize(normalized);

        List<String> meaningful =
                new ArrayList<>();

        for (String token : tokens) {
            if (token.length() >= 3) {
                meaningful.add(token.toLowerCase(Locale.ROOT));
            }
        }

        if (meaningful.isEmpty()) {
            return new SqlFilter("1 = 1", List.of());
        }

        String condition =
                meaningful.stream()
                        .map(ignored ->
                                """
                                (
                                    LOWER(nome) LIKE ?
                                    OR LOWER(COALESCE(codigo_colaborador, '')) LIKE ?
                                    OR LOWER(COALESCE(nome_grupo, '')) LIKE ?
                                    OR LOWER(COALESCE(nome_perfil, '')) LIKE ?
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
            parameters.add(pattern);
        }

        return new SqlFilter(condition, parameters);
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

    private record SqlFilter(
            String condition,
            List<Object> parameters
    ) {
    }
}
