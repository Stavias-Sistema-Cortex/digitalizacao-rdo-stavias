package com.projeto.cortex.memory;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.temporal.TemporalAccessor;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

@Service
public class CortexOperationalMemoryService {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final ApplicationEventPublisher eventPublisher;

    public CortexOperationalMemoryService(
            JdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper,
            ApplicationEventPublisher eventPublisher
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.eventPublisher = eventPublisher;
    }

    @Transactional
    public long registrarEvento(
            String tipoEntidade,
            String entidadeId,
            String tipoEvento,
            String fonte,
            Map<String, Object> payload
    ) {
        return registrarEventoDetalhado(
                null,
                tipoEntidade,
                entidadeId,
                tipoEvento,
                fonte,
                null,
                null,
                null,
                List.of(),
                "ONLINE",
                "SYNCED",
                LocalDateTime.now(),
                LocalDateTime.now(),
                1,
                payload
        );
    }

    @Transactional
    public long registrarEventoDetalhado(
            String eventoIdInformado,
            String tipoEntidade,
            String entidadeId,
            String tipoEvento,
            String fonte,
            String obraId,
            String rdoId,
            String colaboradorId,
            List<Map<String, Object>> entidadesRelacionadas,
            String origem,
            String syncStatus,
            LocalDateTime ocorridoEm,
            LocalDateTime sincronizadoEm,
            Integer schemaVersion,
            Map<String, Object> payload
    ) {
        String eventoId = primeiroNaoVazio(eventoIdInformado, UUID.randomUUID().toString());

        Long existingCommitSeq = commitSeqExistente(eventoId);
        if (existingCommitSeq != null) {
            return existingCommitSeq;
        }

        long commitSeq = proximaCommitSeq();

        String payloadJson = toJson(payload);
        String relatedJson = toJson(entidadesRelacionadas == null ? List.of() : entidadesRelacionadas);
        String normalizedStatus = primeiroNaoVazio(syncStatus, "SYNCED");
        String normalizedOrigin = primeiroNaoVazio(origem, "ONLINE");

        jdbcTemplate.update(
                """
                INSERT INTO cortex_evento_operacional (
                    id,
                    commit_seq,
                    tipo_entidade,
                    entidade_id,
                    obra_id,
                    rdo_id,
                    colaborador_id,
                    tipo_evento,
                    fonte,
                    origem,
                    sync_status,
                    sincronizado_em,
                    entidades_relacionadas_json,
                    schema_version,
                    payload_json
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                eventoId,
                commitSeq,
                tipoEntidade,
                entidadeId,
                nuloSeVazio(obraId),
                nuloSeVazio(rdoId),
                nuloSeVazio(colaboradorId),
                tipoEvento,
                fonte,
                normalizedOrigin,
                normalizedStatus,
                sincronizadoEm,
                relatedJson,
                schemaVersion == null ? 1 : schemaVersion,
                payloadJson
        );

        if (ocorridoEm != null) {
            jdbcTemplate.update(
                    """
                    UPDATE cortex_evento_operacional
                    SET ocorrido_em = ?
                    WHERE id = ?
                    """,
                    ocorridoEm,
                    eventoId
            );
        }

        Long sequencia = jdbcTemplate.queryForObject(
                """
                SELECT sequencia
                FROM cortex_evento_operacional
                WHERE id = ?
                """,
                Long.class,
                eventoId
        );

        if (sequencia == null) {
            throw new IllegalStateException("Evento operacional foi criado sem sequência.");
        }

        jdbcTemplate.update(
                """
                INSERT INTO cortex_estado_entidade (
                    tipo_entidade,
                    entidade_id,
                    versao_entidade,
                    ultimo_evento_seq
                ) VALUES (?, ?, 1, ?)
                ON DUPLICATE KEY UPDATE
                    versao_entidade = versao_entidade + 1,
                    ultimo_evento_seq = VALUES(ultimo_evento_seq)
                """,
                tipoEntidade,
                entidadeId,
                sequencia
        );

        eventPublisher.publishEvent(new CortexObservacaoRegistrada(
                eventoId,
                tipoEntidade,
                entidadeId,
                tipoEvento,
                fonte,
                nuloSeVazio(obraId)
        ));

        return commitSeq;
    }

    private Long commitSeqExistente(String eventoId) {
        if (eventoId == null || eventoId.isBlank()) {
            return null;
        }

        try {
            return jdbcTemplate.queryForObject(
                    """
                    SELECT commit_seq
                    FROM cortex_evento_operacional
                    WHERE id = ?
                    """,
                    Long.class,
                    eventoId
            );
        } catch (EmptyResultDataAccessException ignored) {
            return null;
        }
    }

    @Transactional
    public void registrarObjeto(
            String tipoEntidade,
            String entidadeId,
            String codigoExterno,
            String nome,
            String status,
            String fonte
    ) {
        registrarObjeto(
                tipoEntidade,
                entidadeId,
                codigoExterno,
                nome,
                status,
                fonte,
                tabelaEntidadePadrao(tipoEntidade),
                Map.of()
        );
    }

    @Transactional
    public void registrarObjeto(
            String tipoEntidade,
            String entidadeId,
            String codigoExterno,
            String nome,
            String status,
            String fonte,
            String tabelaEntidade,
            Map<String, Object> metadados
    ) {
        jdbcTemplate.update(
                """
                INSERT INTO cortex_objeto (
                    id,
                    tipo_entidade,
                    tabela_entidade,
                    entidade_id,
                    codigo_externo,
                    chave_canonica,
                    nome,
                    status,
                    fonte,
                    metadados_json
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE
                    tabela_entidade = COALESCE(VALUES(tabela_entidade), tabela_entidade),
                    codigo_externo = VALUES(codigo_externo),
                    chave_canonica = VALUES(chave_canonica),
                    nome = VALUES(nome),
                    status = VALUES(status),
                    fonte = VALUES(fonte),
                    metadados_json = COALESCE(VALUES(metadados_json), metadados_json),
                    visto_por_ultimo_em = CURRENT_TIMESTAMP(6),
                    versao_linha = versao_linha + 1
                """,
                UUID.randomUUID().toString(),
                tipoEntidade,
                nuloSeVazio(tabelaEntidade),
                entidadeId,
                codigoExterno,
                chaveCanonica(tipoEntidade, entidadeId, codigoExterno, nome),
                nome,
                status,
                fonte,
                toNullableJson(metadados)
        );
    }

    @Transactional
    public void registrarEvidencia(
            String tipoEntidade,
            String entidadeId,
            String nomeCampo,
            Object valorCampo,
            String tipoCampo,
            String fonte,
            BigDecimal confianca,
            Map<String, Object> metadados
    ) {
        if (nomeCampo == null || nomeCampo.isBlank()) {
            return;
        }

        String valorNormalizado = valorEvidencia(valorCampo);
        if (valorNormalizado == null) {
            return;
        }

        jdbcTemplate.update(
                """
                INSERT INTO cortex_evidencia_operacional (
                    id,
                    objeto_id,
                    tipo_entidade,
                    entidade_id,
                    nome_campo,
                    valor_campo,
                    tipo_campo,
                    fonte,
                    confianca,
                    metadados_json
                )
                SELECT
                    ?,
                    objeto.id,
                    objeto.tipo_entidade,
                    objeto.entidade_id,
                    ?,
                    ?,
                    ?,
                    ?,
                    ?,
                    ?
                FROM cortex_objeto objeto
                WHERE objeto.tipo_entidade = ?
                  AND objeto.entidade_id = ?
                ON DUPLICATE KEY UPDATE
                    valor_campo = VALUES(valor_campo),
                    tipo_campo = VALUES(tipo_campo),
                    confianca = VALUES(confianca),
                    valido_em = CURRENT_TIMESTAMP(6),
                    metadados_json = COALESCE(VALUES(metadados_json), cortex_evidencia_operacional.metadados_json)
                """,
                UUID.randomUUID().toString(),
                nomeCampo.trim(),
                valorNormalizado,
                tipoCampo(tipoCampo),
                fonte,
                confianca == null ? BigDecimal.ONE : confianca,
                toNullableJson(metadados),
                tipoEntidade,
                entidadeId
        );
    }

    @Transactional
    public void registrarEvidencias(
            String tipoEntidade,
            String entidadeId,
            String fonte,
            Map<String, Object> campos
    ) {
        if (campos == null || campos.isEmpty()) {
            return;
        }

        campos.forEach((nomeCampo, valorCampo) ->
                registrarEvidencia(
                        tipoEntidade,
                        entidadeId,
                        nomeCampo,
                        valorCampo,
                        inferirTipoCampo(valorCampo),
                        fonte,
                        BigDecimal.ONE,
                        Map.of()
                )
        );
    }

    @Transactional
    public void registrarMapeamentoLegado(
            String tipoEntidade,
            String entidadeId,
            String sistemaLegado,
            String tabelaLegado,
            String idLegado,
            String hashLegado,
            String importBatchId,
            Map<String, Object> snapshotOrigem
    ) {
        if (
                idLegado == null
                || idLegado.isBlank()
                || sistemaLegado == null
                || sistemaLegado.isBlank()
                || tabelaLegado == null
                || tabelaLegado.isBlank()
        ) {
            return;
        }

        jdbcTemplate.update(
                """
                INSERT INTO cortex_mapeamento_legado (
                    id,
                    sistema_legado,
                    tabela_legado,
                    id_legado,
                    hash_legado,
                    objeto_id,
                    tipo_entidade,
                    entidade_id,
                    import_batch_id,
                    snapshot_origem_json,
                    ativo
                )
                SELECT
                    ?,
                    ?,
                    ?,
                    ?,
                    ?,
                    objeto.id,
                    objeto.tipo_entidade,
                    objeto.entidade_id,
                    ?,
                    ?,
                    1
                FROM cortex_objeto objeto
                WHERE objeto.tipo_entidade = ?
                  AND objeto.entidade_id = ?
                ON DUPLICATE KEY UPDATE
                    hash_legado = VALUES(hash_legado),
                    objeto_id = VALUES(objeto_id),
                    tipo_entidade = VALUES(tipo_entidade),
                    entidade_id = VALUES(entidade_id),
                    import_batch_id = VALUES(import_batch_id),
                    snapshot_origem_json = VALUES(snapshot_origem_json),
                    ativo = 1,
                    visto_por_ultimo_em = CURRENT_TIMESTAMP(6)
                """,
                UUID.randomUUID().toString(),
                sistemaLegado.trim(),
                tabelaLegado.trim(),
                idLegado.trim(),
                nuloSeVazio(hashLegado),
                nuloSeVazio(importBatchId),
                toNullableJson(snapshotOrigem),
                tipoEntidade,
                entidadeId
        );
    }

    @Transactional
    public void substituirRelacaoAtiva(
            String origemTipo,
            String origemId,
            String destinoTipo,
            String destinoId,
            String tipoRelacao,
            String fonte,
            String observacoes
    ) {
        jdbcTemplate.update(
                """
                UPDATE cortex_relacao
                SET
                    ativa = 0,
                    encerrado_em = CURRENT_TIMESTAMP(6),
                    versao_linha = versao_linha + 1
                WHERE origem_tipo = ?
                  AND origem_id = ?
                  AND tipo_relacao = ?
                  AND ativa = 1
                  AND NOT (destino_tipo = ? AND destino_id = ?)
                """,
                origemTipo,
                origemId,
                tipoRelacao,
                destinoTipo,
                destinoId
        );

        registrarRelacaoAtiva(
                origemTipo,
                origemId,
                destinoTipo,
                destinoId,
                tipoRelacao,
                fonte,
                observacoes
        );
    }

    @Transactional
    public void registrarRelacaoAtiva(
            String origemTipo,
            String origemId,
            String destinoTipo,
            String destinoId,
            String tipoRelacao,
            String fonte,
            String observacoes
    ) {
        if (destinoId == null || destinoId.isBlank()) {
            return;
        }

        try {
            jdbcTemplate.update(
                    """
                    INSERT INTO cortex_relacao (
                        id,
                        origem_tipo,
                        origem_id,
                        destino_tipo,
                        destino_id,
                        tipo_relacao,
                        ativa,
                        fonte,
                        observacoes
                    ) VALUES (?, ?, ?, ?, ?, ?, 1, ?, ?)
                    """,
                    UUID.randomUUID().toString(),
                    origemTipo,
                    origemId,
                    destinoTipo,
                    destinoId,
                    tipoRelacao,
                    fonte,
                    observacoes
            );
        } catch (DuplicateKeyException ignored) {
            // Relação ativa já existe. Isso mantém a operação idempotente.
        }
    }

    private long proximaCommitSeq() {
        int linhasAtualizadas = jdbcTemplate.update(
                """
                UPDATE cortex_evento_commit_sequence
                SET ultima_commit_seq = LAST_INSERT_ID(ultima_commit_seq + 1)
                WHERE id = 1
                """
        );

        if (linhasAtualizadas != 1) {
            throw new IllegalStateException(
                    "Sequência de commit do córtex não inicializada (linha id=1 ausente)."
            );
        }

        Long commitSeq = jdbcTemplate.queryForObject(
                "SELECT LAST_INSERT_ID()",
                Long.class
        );

        if (commitSeq == null) {
            throw new IllegalStateException("Não foi possível gerar commit_seq.");
        }

        return commitSeq;
    }

    private String toJson(Object payload) {
        try {
            return objectMapper.writeValueAsString(payload == null ? Map.of() : payload);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Payload inválido para evento operacional.", exception);
        }
    }

    private String toNullableJson(Map<String, Object> payload) {
        if (payload == null || payload.isEmpty()) {
            return null;
        }

        return toJson(payload);
    }

    private String chaveCanonica(
            String tipoEntidade,
            String entidadeId,
            String codigoExterno,
            String nome
    ) {
        String base = primeiroNaoVazio(
                codigoExterno,
                nome,
                entidadeId
        );

        return tipoEntidade + ":" + base;
    }

    private String tabelaEntidadePadrao(String tipoEntidade) {
        if (tipoEntidade == null || tipoEntidade.isBlank()) {
            return null;
        }

        return switch (tipoEntidade) {
            case "OBRA" -> "obra";
            case "RDO" -> "rdo";
            case "PROGRAMACAO_OPERACIONAL" -> "programacao_operacional";
            case "COLABORADOR" -> "colaborador";
            case "ATIVO", "EQUIPAMENTO" -> "asset";
            case "SERVICO" -> "catalogo_tipo_servico";
            case "RDO_FOTO" -> "rdo_attachment";
            case "EXECUCAO_SERVICO_RDO" -> "execucao_servico_rdo";
            case "ALOCACAO_COLABORADOR" -> "alocacao_colaborador";
            case "ITEM_CONTRATUAL" -> "item_contratual";
            case "PREVISAO_FINANCEIRA" -> "previsao_financeira_snapshot";
            case "FREQUENCIA",
                    "JORNADA_PLANEJADA",
                    "REGISTRO_PRESENCA",
                    "OCORRENCIA_FREQUENCIA",
                    "AJUSTE_BANCO_HORAS" -> tipoEntidade.toLowerCase();
            default -> tipoEntidade.toLowerCase();
        };
    }

    private String primeiroNaoVazio(String... valores) {
        for (String valor : valores) {
            if (valor != null && !valor.isBlank()) {
                return valor.trim();
            }
        }

        return "";
    }

    private String nuloSeVazio(String valor) {
        if (valor == null || valor.isBlank()) {
            return null;
        }

        return valor.trim();
    }

    private String valorEvidencia(Object value) {
        if (value == null) {
            return null;
        }

        if (value instanceof CharSequence text) {
            String normalized = text.toString().trim();
            return normalized.isBlank() ? null : normalized;
        }

        if (value instanceof TemporalAccessor) {
            return value.toString();
        }

        if (
                value instanceof Number
                || value instanceof Boolean
        ) {
            return value.toString();
        }

        if (value instanceof Map<?, ?> map) {
            Map<String, Object> normalized = new LinkedHashMap<>();
            map.forEach((key, mapValue) -> {
                if (key != null) {
                    normalized.put(key.toString(), mapValue);
                }
            });
            return toJson(normalized);
        }

        return value.toString();
    }

    private String tipoCampo(String tipoCampo) {
        if (tipoCampo == null || tipoCampo.isBlank()) {
            return "TEXTO";
        }

        return tipoCampo.trim().toUpperCase();
    }

    private String inferirTipoCampo(Object value) {
        if (value instanceof Number) {
            return "NUMERO";
        }

        if (value instanceof Boolean) {
            return "BOOLEANO";
        }

        if (value instanceof TemporalAccessor) {
            return "DATA";
        }

        return "TEXTO";
    }
}
