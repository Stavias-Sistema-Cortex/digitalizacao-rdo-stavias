package com.projeto.cortex.intelligence.stavia.ontology.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.projeto.cortex.intelligence.stavia.ontology.model.OntologyEntity;
import com.projeto.cortex.intelligence.stavia.ontology.model.OntologyEvent;
import com.projeto.cortex.intelligence.stavia.ontology.model.OntologyRelation;
import com.projeto.cortex.intelligence.stavia.ontology.model.OperationalDecision;
import com.projeto.cortex.intelligence.stavia.ontology.model.OperationalEvidence;
import com.projeto.cortex.intelligence.stavia.ontology.model.OperationalState;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class StaviaOntologyService {

    private static final TypeReference<Map<String, Object>> MAP_TYPE =
            new TypeReference<>() {
            };

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public StaviaOntologyService(
            JdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public void synchronizeOperationalData(String obraId) {
        String normalizedObraId = blankToNull(obraId);

        synchronizeWorks(normalizedObraId);
        synchronizeSchedules(normalizedObraId);
        synchronizeRdos(normalizedObraId);
        synchronizeServiceExecutions(normalizedObraId);
        synchronizeRelations(normalizedObraId);
        synchronizeEvents(normalizedObraId);
        synchronizeStates(normalizedObraId);
        synchronizeEvidences(normalizedObraId);
    }

    public List<OntologyEntity> listEntities(
            String type,
            String query,
            Integer limit
    ) {
        StringBuilder sql = new StringBuilder("""
                SELECT *
                FROM ontology_entities
                WHERE 1 = 1
                """);
        List<Object> params = new ArrayList<>();

        if (!isBlank(type)) {
            sql.append(" AND entity_type = ?");
            params.add(type.trim().toUpperCase());
        }

        if (!isBlank(query)) {
            sql.append("""
                     AND (
                        LOWER(canonical_name) LIKE ?
                        OR LOWER(COALESCE(description, '')) LIKE ?
                        OR LOWER(COALESCE(external_ref_id, '')) LIKE ?
                        OR LOWER(id) LIKE ?
                     )
                    """);
            String pattern = "%" + query.trim().toLowerCase() + "%";
            params.add(pattern);
            params.add(pattern);
            params.add(pattern);
            params.add(pattern);
        }

        sql.append(" ORDER BY updated_at DESC, canonical_name ASC LIMIT ?");
        params.add(limit(limit));

        return jdbcTemplate.query(
                sql.toString(),
                this::mapEntity,
                params.toArray()
        );
    }

    public Optional<OntologyEntity> findEntity(String id) {
        if (isBlank(id)) {
            return Optional.empty();
        }

        try {
            return Optional.ofNullable(
                    jdbcTemplate.queryForObject(
                            """
                            SELECT *
                            FROM ontology_entities
                            WHERE id = ?
                            """,
                            this::mapEntity,
                            id.trim()
                    )
            );
        } catch (EmptyResultDataAccessException ignored) {
            return Optional.empty();
        }
    }

    public Optional<OntologyEntity> resolveEntity(String query) {
        if (isBlank(query)) {
            return Optional.empty();
        }

        Optional<OntologyEntity> byId = findEntity(query);
        if (byId.isPresent()) {
            return byId;
        }

        return listEntities(null, query, 1).stream().findFirst();
    }

    public Optional<String> resolveWorksiteId(String query) {
        if (isBlank(query)) {
            return Optional.empty();
        }

        return listEntities("OBRA", query, 1)
                .stream()
                .map(OntologyEntity::externalRefId)
                .filter(value -> !isBlank(value))
                .findFirst();
    }

    public List<OntologyRelation> relationsForEntity(String entityId) {
        if (isBlank(entityId)) {
            return List.of();
        }

        return jdbcTemplate.query(
                """
                SELECT *
                FROM ontology_relations
                WHERE source_entity_id = ? OR target_entity_id = ?
                ORDER BY created_at DESC
                LIMIT 200
                """,
                this::mapRelation,
                entityId.trim(),
                entityId.trim()
        );
    }

    public List<OntologyEvent> eventsForEntity(String entityId) {
        if (isBlank(entityId)) {
            return List.of();
        }

        return jdbcTemplate.query(
                """
                SELECT *
                FROM ontology_events
                WHERE entity_id = ? OR related_entity_id = ?
                ORDER BY occurred_at DESC
                LIMIT 200
                """,
                this::mapEvent,
                entityId.trim(),
                entityId.trim()
        );
    }

    public List<OperationalState> statesForEntity(String entityId) {
        if (isBlank(entityId)) {
            return List.of();
        }

        return jdbcTemplate.query(
                """
                SELECT *
                FROM operational_states
                WHERE entity_id = ?
                ORDER BY valid_from DESC
                LIMIT 200
                """,
                this::mapState,
                entityId.trim()
        );
    }

    public List<OperationalEvidence> evidencesForEntity(String entityId) {
        if (isBlank(entityId)) {
            return List.of();
        }

        return jdbcTemplate.query(
                """
                SELECT *
                FROM operational_evidences
                WHERE entity_id = ?
                ORDER BY created_at DESC
                LIMIT 200
                """,
                this::mapEvidence,
                entityId.trim()
        );
    }

    public List<OperationalDecision> decisionsForEntity(String entityId) {
        if (isBlank(entityId)) {
            return List.of();
        }

        return jdbcTemplate.query(
                """
                SELECT *
                FROM operational_decisions
                WHERE entity_id = ?
                ORDER BY COALESCE(decided_at, created_at) DESC
                LIMIT 200
                """,
                this::mapDecision,
                entityId.trim()
        );
    }

    public List<OntologyEntity> delayedActivities(String obraId, int limit) {
        StringBuilder sql = new StringBuilder("""
                SELECT activity.*
                FROM ontology_entities activity
                JOIN operational_states delay_state
                  ON delay_state.entity_id = activity.id
                 AND delay_state.state_type = 'DELAY_DAYS'
                 AND delay_state.valid_to IS NULL
                WHERE activity.entity_type = 'ATIVIDADE'
                  AND delay_state.numeric_value > 0
                """);
        List<Object> params = new ArrayList<>();

        if (!isBlank(obraId)) {
            sql.append(" AND JSON_UNQUOTE(JSON_EXTRACT(activity.metadata_json, '$.obraId')) = ?");
            params.add(obraId.trim());
        }

        sql.append(" ORDER BY delay_state.numeric_value DESC, activity.updated_at DESC LIMIT ?");
        params.add(limit(limit));

        return jdbcTemplate.query(
                sql.toString(),
                this::mapEntity,
                params.toArray()
        );
    }

    public List<OntologyEvent> eventsForEntities(
            List<String> entityIds,
            int limit
    ) {
        List<String> ids = nonBlank(entityIds);
        if (ids.isEmpty()) {
            return List.of();
        }

        String placeholders = placeholders(ids.size());
        List<Object> params = new ArrayList<>();
        params.addAll(ids);
        params.addAll(ids);
        params.add(limit(limit));

        return jdbcTemplate.query(
                """
                SELECT *
                FROM ontology_events
                WHERE entity_id IN (%s) OR related_entity_id IN (%s)
                ORDER BY occurred_at DESC
                LIMIT ?
                """.formatted(placeholders, placeholders),
                this::mapEvent,
                params.toArray()
        );
    }

    public List<OntologyRelation> relationsForEntities(
            List<String> entityIds,
            int limit
    ) {
        List<String> ids = nonBlank(entityIds);
        if (ids.isEmpty()) {
            return List.of();
        }

        String placeholders = placeholders(ids.size());
        List<Object> params = new ArrayList<>();
        params.addAll(ids);
        params.addAll(ids);
        params.add(limit(limit));

        return jdbcTemplate.query(
                """
                SELECT *
                FROM ontology_relations
                WHERE source_entity_id IN (%s) OR target_entity_id IN (%s)
                ORDER BY created_at DESC
                LIMIT ?
                """.formatted(placeholders, placeholders),
                this::mapRelation,
                params.toArray()
        );
    }

    public List<OperationalState> statesForEntities(
            List<String> entityIds,
            int limit
    ) {
        List<String> ids = nonBlank(entityIds);
        if (ids.isEmpty()) {
            return List.of();
        }

        String placeholders = placeholders(ids.size());
        List<Object> params = new ArrayList<>(ids);
        params.add(limit(limit));

        return jdbcTemplate.query(
                """
                SELECT *
                FROM operational_states
                WHERE entity_id IN (%s)
                ORDER BY valid_from DESC
                LIMIT ?
                """.formatted(placeholders),
                this::mapState,
                params.toArray()
        );
    }

    public List<OperationalEvidence> evidencesForEntities(
            List<String> entityIds,
            int limit
    ) {
        List<String> ids = nonBlank(entityIds);
        if (ids.isEmpty()) {
            return List.of();
        }

        String placeholders = placeholders(ids.size());
        List<Object> params = new ArrayList<>(ids);
        params.add(limit(limit));

        return jdbcTemplate.query(
                """
                SELECT *
                FROM operational_evidences
                WHERE entity_id IN (%s)
                ORDER BY created_at DESC
                LIMIT ?
                """.formatted(placeholders),
                this::mapEvidence,
                params.toArray()
        );
    }

    @Transactional
    public String saveQuery(
            String userId,
            String queryText,
            String detectedIntent,
            Map<String, Object> scope,
            List<String> entityIds,
            List<String> eventIds,
            List<String> relationIds,
            String responseText,
            double confidence
    ) {
        String id = UUID.randomUUID().toString();

        jdbcTemplate.update(
                """
                INSERT INTO stavia_queries (
                    id, user_id, query_text, detected_intent, query_scope_json,
                    entities_used_json, events_used_json, relations_used_json,
                    response_text, confidence
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                id,
                blankToNull(userId),
                queryText,
                detectedIntent,
                toJson(scope == null ? Map.of() : scope),
                toJson(entityIds == null ? List.of() : entityIds),
                toJson(eventIds == null ? List.of() : eventIds),
                toJson(relationIds == null ? List.of() : relationIds),
                responseText,
                BigDecimal.valueOf(confidence)
        );

        return id;
    }

    @Transactional
    public void saveContextSnapshot(
            String queryId,
            Map<String, Object> context
    ) {
        if (isBlank(queryId)) {
            return;
        }

        jdbcTemplate.update(
                """
                INSERT INTO stavia_context_snapshots (id, query_id, context_json)
                VALUES (?, ?, ?)
                """,
                UUID.randomUUID().toString(),
                queryId.trim(),
                toJson(context == null ? Map.of() : context)
        );
    }

    private void synchronizeWorks(String obraId) {
        jdbcTemplate.update(
                """
                INSERT INTO ontology_entities (
                    id, entity_type, external_ref_type, external_ref_id,
                    canonical_name, description, status, metadata_json,
                    created_at, updated_at
                )
                SELECT UUID(), 'OBRA', 'obra', o.id,
                       COALESCE(NULLIF(o.nome, ''), CONCAT('Obra ', o.id)),
                       o.descricao,
                       o.status,
                       JSON_OBJECT(
                           'codigoContrato', o.codigo_contrato,
                           'codigoCw', o.codigo_cw,
                           'codigoInterno', o.codigo_interno,
                           'cliente', o.cliente,
                           'cidade', o.cidade,
                           'uf', o.uf,
                           'rodovia', o.rodovia,
                           'fonte', 'obra'
                       ),
                       COALESCE(o.criado_em, CURRENT_TIMESTAMP(6)),
                       COALESCE(o.atualizado_em, CURRENT_TIMESTAMP(6))
                FROM obra o
                WHERE (? IS NULL OR o.id = ?)
                ON DUPLICATE KEY UPDATE
                    canonical_name = VALUES(canonical_name),
                    description = VALUES(description),
                    status = VALUES(status),
                    metadata_json = VALUES(metadata_json),
                    updated_at = CURRENT_TIMESTAMP(6)
                """,
                obraId,
                obraId
        );
    }

    private void synchronizeSchedules(String obraId) {
        jdbcTemplate.update(
                """
                INSERT INTO ontology_entities (
                    id, entity_type, external_ref_type, external_ref_id,
                    canonical_name, description, status, metadata_json,
                    created_at, updated_at
                )
                SELECT UUID(), 'PROGRAMACAO', 'programacao_operacional', p.id,
                       CONCAT('Programação ', DATE_FORMAT(p.data_programacao, '%d/%m/%Y')),
                       p.observacoes,
                       p.status,
                       JSON_OBJECT(
                           'obraId', p.obra_id,
                           'dataProgramacao', CAST(p.data_programacao AS CHAR),
                           'equipe', p.equipe,
                           'servico', p.servico,
                           'tipoServico', p.tipo_servico,
                           'encarregado', p.encarregado,
                           'engenheiro', p.engenheiro,
                           'cidade', p.cidade,
                           'rodovia', p.rodovia,
                           'kmInicial', p.km_inicial,
                           'kmFinal', p.km_final,
                           'fonte', 'programacao_operacional'
                       ),
                       COALESCE(p.criado_em, CURRENT_TIMESTAMP(6)),
                       COALESCE(p.atualizado_em, CURRENT_TIMESTAMP(6))
                FROM programacao_operacional p
                WHERE (? IS NULL OR p.obra_id = ?)
                ON DUPLICATE KEY UPDATE
                    canonical_name = VALUES(canonical_name),
                    description = VALUES(description),
                    status = VALUES(status),
                    metadata_json = VALUES(metadata_json),
                    updated_at = CURRENT_TIMESTAMP(6)
                """,
                obraId,
                obraId
        );

        jdbcTemplate.update(
                """
                INSERT INTO ontology_entities (
                    id, entity_type, external_ref_type, external_ref_id,
                    canonical_name, description, status, metadata_json,
                    created_at, updated_at
                )
                SELECT UUID(), 'ATIVIDADE', 'programacao_operacional', p.id,
                       COALESCE(NULLIF(p.servico, ''), NULLIF(p.tipo_servico, ''), CONCAT('Atividade ', p.id)),
                       CONCAT_WS(' — ', NULLIF(p.rodovia, ''), NULLIF(p.faixa, ''), NULLIF(p.km_inicial, ''), NULLIF(p.km_final, '')),
                       p.status,
                       JSON_OBJECT(
                           'obraId', p.obra_id,
                           'programacaoId', p.id,
                           'dataProgramacao', CAST(p.data_programacao AS CHAR),
                           'equipe', p.equipe,
                           'servico', p.servico,
                           'tipoServico', p.tipo_servico,
                           'faixa', p.faixa,
                           'kmInicial', p.km_inicial,
                           'kmFinal', p.km_final,
                           'fonte', 'programacao_operacional'
                       ),
                       COALESCE(p.criado_em, CURRENT_TIMESTAMP(6)),
                       COALESCE(p.atualizado_em, CURRENT_TIMESTAMP(6))
                FROM programacao_operacional p
                WHERE (? IS NULL OR p.obra_id = ?)
                ON DUPLICATE KEY UPDATE
                    canonical_name = VALUES(canonical_name),
                    description = VALUES(description),
                    status = VALUES(status),
                    metadata_json = VALUES(metadata_json),
                    updated_at = CURRENT_TIMESTAMP(6)
                """,
                obraId,
                obraId
        );
    }

    private void synchronizeRdos(String obraId) {
        jdbcTemplate.update(
                """
                INSERT INTO ontology_entities (
                    id, entity_type, external_ref_type, external_ref_id,
                    canonical_name, description, status, metadata_json,
                    created_at, updated_at
                )
                SELECT UUID(), 'RDO', 'rdo', r.id,
                       CONCAT('RDO ', COALESCE(NULLIF(r.numero_rdo, ''), r.id), ' — ', DATE_FORMAT(r.data_rdo, '%d/%m/%Y')),
                       r.observacoes,
                       r.status,
                       JSON_OBJECT(
                           'obraId', r.obra_id,
                           'programacaoId', r.programacao_id,
                           'numeroRdo', r.numero_rdo,
                           'dataRdo', CAST(r.data_rdo AS CHAR),
                           'contrato', r.contrato,
                           'rodovia', r.rodovia,
                           'cidade', r.cidade,
                           'turno', r.turno,
                           'pluviometriaMm', r.pluviometria_mm,
                           'fonte', 'rdo'
                       ),
                       COALESCE(r.criado_em, CURRENT_TIMESTAMP(6)),
                       COALESCE(r.atualizado_em, CURRENT_TIMESTAMP(6))
                FROM rdo r
                WHERE (? IS NULL OR r.obra_id = ?)
                ON DUPLICATE KEY UPDATE
                    canonical_name = VALUES(canonical_name),
                    description = VALUES(description),
                    status = VALUES(status),
                    metadata_json = VALUES(metadata_json),
                    updated_at = CURRENT_TIMESTAMP(6)
                """,
                obraId,
                obraId
        );
    }

    private void synchronizeServiceExecutions(String obraId) {
        jdbcTemplate.update(
                """
                INSERT INTO ontology_entities (
                    id, entity_type, external_ref_type, external_ref_id,
                    canonical_name, description, status, metadata_json,
                    created_at, updated_at
                )
                SELECT UUID(), 'ATIVIDADE', 'execucao_servico_rdo', e.id,
                       COALESCE(NULLIF(e.servico_nome, ''), CONCAT('Execução ', e.id)),
                       CONCAT_WS(' — ', NULLIF(e.localizacao, ''), NULLIF(e.trecho_inicial, ''), NULLIF(e.trecho_final, '')),
                       e.status_validacao,
                       JSON_OBJECT(
                           'obraId', e.obra_id,
                           'rdoId', e.rdo_id,
                           'programacaoId', e.programacao_id,
                           'dataExecucao', CAST(e.data_execucao AS CHAR),
                           'quantidadeExecutada', e.quantidade_executada,
                           'unidadeMedida', e.unidade_medida,
                           'retrabalho', e.retrabalho,
                           'producaoRejeitada', e.producao_rejeitada,
                           'fonte', 'execucao_servico_rdo'
                       ),
                       COALESCE(e.criado_em, CURRENT_TIMESTAMP(6)),
                       COALESCE(e.atualizado_em, CURRENT_TIMESTAMP(6))
                FROM execucao_servico_rdo e
                WHERE (? IS NULL OR e.obra_id = ?)
                  AND e.cancelada = 0
                ON DUPLICATE KEY UPDATE
                    canonical_name = VALUES(canonical_name),
                    description = VALUES(description),
                    status = VALUES(status),
                    metadata_json = VALUES(metadata_json),
                    updated_at = CURRENT_TIMESTAMP(6)
                """,
                obraId,
                obraId
        );
    }

    private void synchronizeRelations(String obraId) {
        relationFromExternalRefs(
                "OBRA",
                "obra",
                "ATIVIDADE",
                "programacao_operacional",
                "CONTAINS",
                "p.obra_id",
                "p.id",
                "programacao_operacional p",
                "(? IS NULL OR p.obra_id = ?)",
                obraId
        );
        relationFromExternalRefs(
                "PROGRAMACAO",
                "programacao_operacional",
                "ATIVIDADE",
                "programacao_operacional",
                "SCHEDULES",
                "p.id",
                "p.id",
                "programacao_operacional p",
                "(? IS NULL OR p.obra_id = ?)",
                obraId
        );
        relationFromExternalRefs(
                "OBRA",
                "obra",
                "RDO",
                "rdo",
                "CONTAINS",
                "r.obra_id",
                "r.id",
                "rdo r",
                "(? IS NULL OR r.obra_id = ?)",
                obraId
        );
        relationFromExternalRefs(
                "ATIVIDADE",
                "programacao_operacional",
                "RDO",
                "rdo",
                "SUPPORTED_BY",
                "r.programacao_id",
                "r.id",
                "rdo r",
                "r.programacao_id IS NOT NULL AND (? IS NULL OR r.obra_id = ?)",
                obraId
        );
    }

    private void relationFromExternalRefs(
            String sourceType,
            String sourceExternalType,
            String targetType,
            String targetExternalType,
            String relationType,
            String sourceExpression,
            String targetExpression,
            String fromClause,
            String whereClause,
            String obraId
    ) {
        String sql = """
                INSERT INTO ontology_relations (
                    id, source_entity_id, relation_type, target_entity_id,
                    confidence, metadata_json
                )
                SELECT UUID(), source_entity.id, '%s', target_entity.id,
                       1.000000,
                       JSON_OBJECT('fonte', 'sincronizacao_ontologica')
                FROM %s
                JOIN ontology_entities source_entity
                  ON source_entity.entity_type = '%s'
                 AND source_entity.external_ref_type = '%s'
                 AND source_entity.external_ref_id = %s
                JOIN ontology_entities target_entity
                  ON target_entity.entity_type = '%s'
                 AND target_entity.external_ref_type = '%s'
                 AND target_entity.external_ref_id = %s
                WHERE %s
                ON DUPLICATE KEY UPDATE
                    confidence = GREATEST(confidence, VALUES(confidence)),
                    metadata_json = VALUES(metadata_json)
                """.formatted(
                relationType,
                fromClause,
                sourceType,
                sourceExternalType,
                sourceExpression,
                targetType,
                targetExternalType,
                targetExpression,
                whereClause
        );

        jdbcTemplate.update(sql, obraId, obraId);
    }

    private void synchronizeEvents(String obraId) {
        jdbcTemplate.update(
                """
                INSERT INTO ontology_events (
                    id, event_type, entity_id, related_entity_id,
                    source_type, source_id, description, payload_json, occurred_at
                )
                SELECT UUID(),
                       CASE WHEN r.enviado_em IS NOT NULL THEN 'RDO_SUBMITTED' ELSE 'ENTITY_UPDATED' END,
                       rdo_entity.id,
                       obra_entity.id,
                       'rdo',
                       r.id,
                       CONCAT('RDO ', COALESCE(r.numero_rdo, r.id), ' registrado no Córtex.'),
                       JSON_OBJECT(
                           'obraId', r.obra_id,
                           'programacaoId', r.programacao_id,
                           'dataRdo', CAST(r.data_rdo AS CHAR),
                           'status', r.status
                       ),
                       COALESCE(r.enviado_em, r.atualizado_em, r.criado_em, CURRENT_TIMESTAMP(6))
                FROM rdo r
                JOIN ontology_entities rdo_entity
                  ON rdo_entity.entity_type = 'RDO'
                 AND rdo_entity.external_ref_type = 'rdo'
                 AND rdo_entity.external_ref_id = r.id
                JOIN ontology_entities obra_entity
                  ON obra_entity.entity_type = 'OBRA'
                 AND obra_entity.external_ref_type = 'obra'
                 AND obra_entity.external_ref_id = r.obra_id
                WHERE (? IS NULL OR r.obra_id = ?)
                ON DUPLICATE KEY UPDATE
                    description = VALUES(description),
                    payload_json = VALUES(payload_json),
                    occurred_at = VALUES(occurred_at)
                """,
                obraId,
                obraId
        );

        jdbcTemplate.update(
                """
                INSERT INTO ontology_events (
                    id, event_type, entity_id, related_entity_id,
                    source_type, source_id, description, payload_json, occurred_at
                )
                SELECT UUID(),
                       'WEATHER_IMPACT_REPORTED',
                       rdo_entity.id,
                       activity_entity.id,
                       'rdo',
                       CONCAT(r.id, ':weather'),
                       CONCAT('Condição climática registrada no RDO ', COALESCE(r.numero_rdo, r.id), '.'),
                       JSON_OBJECT(
                           'condicaoManha', r.condicao_manha,
                           'condicaoTarde', r.condicao_tarde,
                           'condicaoNoite', r.condicao_noite,
                           'pluviometriaMm', r.pluviometria_mm
                       ),
                       COALESCE(r.enviado_em, r.atualizado_em, r.criado_em, CURRENT_TIMESTAMP(6))
                FROM rdo r
                JOIN ontology_entities rdo_entity
                  ON rdo_entity.entity_type = 'RDO'
                 AND rdo_entity.external_ref_type = 'rdo'
                 AND rdo_entity.external_ref_id = r.id
                LEFT JOIN ontology_entities activity_entity
                  ON activity_entity.entity_type = 'ATIVIDADE'
                 AND activity_entity.external_ref_type = 'programacao_operacional'
                 AND activity_entity.external_ref_id = r.programacao_id
                WHERE (? IS NULL OR r.obra_id = ?)
                  AND (
                        COALESCE(r.pluviometria_mm, 0) > 0
                        OR UPPER(CONCAT_WS(' ', r.condicao_manha, r.condicao_tarde, r.condicao_noite, r.observacoes)) LIKE '%CHUVA%'
                  )
                ON DUPLICATE KEY UPDATE
                    description = VALUES(description),
                    payload_json = VALUES(payload_json),
                    occurred_at = VALUES(occurred_at)
                """,
                obraId,
                obraId
        );
    }

    private void synchronizeStates(String obraId) {
        closeCurrentDelayStates(obraId);

        insertScheduleState(
                obraId,
                "STATUS",
                "p.status",
                "NULL",
                "NULL",
                "p.atualizado_em"
        );
        insertScheduleState(
                obraId,
                "PLANNED_START",
                "CAST(p.data_programacao AS CHAR)",
                "NULL",
                "'data'",
                "p.atualizado_em"
        );
        insertScheduleState(
                obraId,
                "PLANNED_END",
                "CAST(p.data_programacao AS CHAR)",
                "NULL",
                "'data'",
                "p.atualizado_em"
        );

        jdbcTemplate.update(
                """
                INSERT INTO operational_states (
                    id, entity_id, state_type, state_value, numeric_value,
                    unit, valid_from, metadata_json
                )
                SELECT UUID(), activity.id, 'DELAY_DAYS',
                       CAST(GREATEST(DATEDIFF(CURRENT_DATE(), p.data_programacao), 0) AS CHAR),
                       GREATEST(DATEDIFF(CURRENT_DATE(), p.data_programacao), 0),
                       'dias',
                       CURRENT_TIMESTAMP(6),
                       JSON_OBJECT(
                           'fonte', 'programacao_operacional',
                           'criterio', 'data_programada_passada_sem_execucao_confirmada',
                           'obraId', p.obra_id,
                           'dataProgramacao', CAST(p.data_programacao AS CHAR)
                       )
                FROM programacao_operacional p
                JOIN ontology_entities activity
                  ON activity.entity_type = 'ATIVIDADE'
                 AND activity.external_ref_type = 'programacao_operacional'
                 AND activity.external_ref_id = p.id
                WHERE (? IS NULL OR p.obra_id = ?)
                  AND p.data_programacao < CURRENT_DATE()
                  AND UPPER(COALESCE(p.status, '')) NOT IN ('CANCELADA', 'CONCLUIDA', 'CONCLUÍDA', 'FINALIZADA')
                  AND NOT EXISTS (
                      SELECT 1
                      FROM execucao_servico_rdo e
                      WHERE e.programacao_id = p.id
                        AND e.cancelada = 0
                        AND e.status_validacao IN ('REGISTRADA', 'VALIDADA')
                  )
                ON DUPLICATE KEY UPDATE
                    state_value = VALUES(state_value),
                    numeric_value = VALUES(numeric_value),
                    unit = VALUES(unit),
                    valid_from = VALUES(valid_from),
                    metadata_json = VALUES(metadata_json)
                """,
                obraId,
                obraId
        );
    }

    private void closeCurrentDelayStates(String obraId) {
        jdbcTemplate.update(
                """
                UPDATE operational_states state
                JOIN ontology_entities activity
                  ON activity.id = state.entity_id
                SET state.valid_to = CURRENT_TIMESTAMP(6)
                WHERE state.state_type = 'DELAY_DAYS'
                  AND state.valid_to IS NULL
                  AND activity.entity_type = 'ATIVIDADE'
                  AND (? IS NULL OR JSON_UNQUOTE(JSON_EXTRACT(activity.metadata_json, '$.obraId')) = ?)
                """,
                obraId,
                obraId
        );
    }

    private void insertScheduleState(
            String obraId,
            String stateType,
            String stateValueExpression,
            String numericValueExpression,
            String unitExpression,
            String validFromExpression
    ) {
        String sql = """
                INSERT INTO operational_states (
                    id, entity_id, state_type, state_value, numeric_value,
                    unit, valid_from, metadata_json
                )
                SELECT UUID(), activity.id, '%s', %s, %s, %s,
                       COALESCE(%s, CURRENT_TIMESTAMP(6)),
                       JSON_OBJECT('fonte', 'programacao_operacional', 'obraId', p.obra_id)
                FROM programacao_operacional p
                JOIN ontology_entities activity
                  ON activity.entity_type = 'ATIVIDADE'
                 AND activity.external_ref_type = 'programacao_operacional'
                 AND activity.external_ref_id = p.id
                WHERE (? IS NULL OR p.obra_id = ?)
                ON DUPLICATE KEY UPDATE
                    state_value = VALUES(state_value),
                    numeric_value = VALUES(numeric_value),
                    unit = VALUES(unit),
                    valid_from = VALUES(valid_from),
                    metadata_json = VALUES(metadata_json)
                """.formatted(
                stateType,
                stateValueExpression,
                numericValueExpression,
                unitExpression,
                validFromExpression
        );

        jdbcTemplate.update(sql, obraId, obraId);
    }

    private void synchronizeEvidences(String obraId) {
        jdbcTemplate.update(
                """
                INSERT INTO operational_evidences (
                    id, entity_id, evidence_type, source_type, source_id,
                    description, metadata_json, created_at
                )
                SELECT UUID(), activity.id, 'PROGRAMACAO_PLANEJADA', 'programacao_operacional', p.id,
                       CONCAT('Atividade programada para ', DATE_FORMAT(p.data_programacao, '%d/%m/%Y'),
                              COALESCE(CONCAT(' — ', NULLIF(p.servico, '')), '')),
                       JSON_OBJECT(
                           'obraId', p.obra_id,
                           'dataProgramacao', CAST(p.data_programacao AS CHAR),
                           'equipe', p.equipe,
                           'encarregado', p.encarregado,
                           'status', p.status
                       ),
                       COALESCE(p.atualizado_em, p.criado_em, CURRENT_TIMESTAMP(6))
                FROM programacao_operacional p
                JOIN ontology_entities activity
                  ON activity.entity_type = 'ATIVIDADE'
                 AND activity.external_ref_type = 'programacao_operacional'
                 AND activity.external_ref_id = p.id
                WHERE (? IS NULL OR p.obra_id = ?)
                ON DUPLICATE KEY UPDATE
                    description = VALUES(description),
                    metadata_json = VALUES(metadata_json),
                    created_at = VALUES(created_at)
                """,
                obraId,
                obraId
        );

        jdbcTemplate.update(
                """
                INSERT INTO operational_evidences (
                    id, entity_id, evidence_type, source_type, source_id,
                    description, metadata_json, created_at
                )
                SELECT UUID(), rdo_entity.id, 'RDO_OBSERVACAO', 'rdo', r.id,
                       r.observacoes,
                       JSON_OBJECT(
                           'obraId', r.obra_id,
                           'numeroRdo', r.numero_rdo,
                           'dataRdo', CAST(r.data_rdo AS CHAR)
                       ),
                       COALESCE(r.enviado_em, r.atualizado_em, r.criado_em, CURRENT_TIMESTAMP(6))
                FROM rdo r
                JOIN ontology_entities rdo_entity
                  ON rdo_entity.entity_type = 'RDO'
                 AND rdo_entity.external_ref_type = 'rdo'
                 AND rdo_entity.external_ref_id = r.id
                WHERE (? IS NULL OR r.obra_id = ?)
                  AND r.observacoes IS NOT NULL
                  AND r.observacoes <> ''
                ON DUPLICATE KEY UPDATE
                    description = VALUES(description),
                    metadata_json = VALUES(metadata_json),
                    created_at = VALUES(created_at)
                """,
                obraId,
                obraId
        );
    }

    private OntologyEntity mapEntity(
            ResultSet rs,
            int rowNum
    ) throws SQLException {
        return new OntologyEntity(
                rs.getString("id"),
                rs.getString("entity_type"),
                rs.getString("external_ref_type"),
                rs.getString("external_ref_id"),
                rs.getString("canonical_name"),
                rs.getString("description"),
                rs.getString("status"),
                readMap(rs.getObject("metadata_json")),
                toInstant(rs.getTimestamp("created_at")),
                toInstant(rs.getTimestamp("updated_at"))
        );
    }

    private OntologyRelation mapRelation(
            ResultSet rs,
            int rowNum
    ) throws SQLException {
        return new OntologyRelation(
                rs.getString("id"),
                rs.getString("source_entity_id"),
                rs.getString("relation_type"),
                rs.getString("target_entity_id"),
                rs.getBigDecimal("confidence"),
                readMap(rs.getObject("metadata_json")),
                toInstant(rs.getTimestamp("created_at"))
        );
    }

    private OntologyEvent mapEvent(
            ResultSet rs,
            int rowNum
    ) throws SQLException {
        return new OntologyEvent(
                rs.getString("id"),
                rs.getString("event_type"),
                rs.getString("entity_id"),
                rs.getString("related_entity_id"),
                rs.getString("source_type"),
                rs.getString("source_id"),
                rs.getString("description"),
                readMap(rs.getObject("payload_json")),
                toInstant(rs.getTimestamp("occurred_at")),
                toInstant(rs.getTimestamp("created_at"))
        );
    }

    private OperationalState mapState(
            ResultSet rs,
            int rowNum
    ) throws SQLException {
        return new OperationalState(
                rs.getString("id"),
                rs.getString("entity_id"),
                rs.getString("state_type"),
                rs.getString("state_value"),
                rs.getBigDecimal("numeric_value"),
                rs.getString("unit"),
                toInstant(rs.getTimestamp("valid_from")),
                toInstant(rs.getTimestamp("valid_to")),
                rs.getString("source_event_id"),
                readMap(rs.getObject("metadata_json"))
        );
    }

    private OperationalEvidence mapEvidence(
            ResultSet rs,
            int rowNum
    ) throws SQLException {
        return new OperationalEvidence(
                rs.getString("id"),
                rs.getString("entity_id"),
                rs.getString("evidence_type"),
                rs.getString("source_type"),
                rs.getString("source_id"),
                rs.getString("description"),
                rs.getString("file_ref"),
                readMap(rs.getObject("metadata_json")),
                toInstant(rs.getTimestamp("created_at"))
        );
    }

    private OperationalDecision mapDecision(
            ResultSet rs,
            int rowNum
    ) throws SQLException {
        return new OperationalDecision(
                rs.getString("id"),
                rs.getString("entity_id"),
                rs.getString("decision_type"),
                rs.getString("description"),
                rs.getString("decided_by"),
                toInstant(rs.getTimestamp("decided_at")),
                rs.getString("expected_impact"),
                readMap(rs.getObject("metadata_json")),
                toInstant(rs.getTimestamp("created_at"))
        );
    }

    private Map<String, Object> readMap(Object value) {
        if (value == null) {
            return Map.of();
        }

        String json = value instanceof byte[] bytes
                ? new String(bytes, StandardCharsets.UTF_8)
                : value.toString();

        if (json.isBlank()) {
            return Map.of();
        }

        try {
            Map<String, Object> parsed = objectMapper.readValue(json, MAP_TYPE);
            return parsed == null ? Map.of() : parsed;
        } catch (JsonProcessingException exception) {
            Map<String, Object> fallback = new LinkedHashMap<>();
            fallback.put("raw", json);
            fallback.put("parseError", exception.getClass().getSimpleName());
            return fallback;
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(
                    value == null ? Map.of() : value
            );
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException(
                    "Conteúdo JSON inválido para a ontologia operacional.",
                    exception
            );
        }
    }

    private Instant toInstant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    private String blankToNull(String value) {
        return isBlank(value) ? null : value.trim();
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private int limit(Integer limit) {
        if (limit == null || limit <= 0) {
            return 50;
        }

        return Math.min(limit, 200);
    }

    private String placeholders(int size) {
        return String.join(
                ", ",
                java.util.Collections.nCopies(size, "?")
        );
    }

    private List<String> nonBlank(List<String> values) {
        if (values == null) {
            return List.of();
        }

        return values.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(String::trim)
                .distinct()
                .toList();
    }
}
