package com.projeto.cortex.obras;

import com.projeto.cortex.memory.CortexOperationalMemoryService;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Gestão do vínculo explícito colaborador ↔ obra (papel Alfa). Cada mudança de
 * estado gera um evento ontológico e mantém a relação {@code VINCULADO_A} no
 * grafo, garantindo rastreabilidade e auditoria (quem vinculou/revogou, quando,
 * em qual obra, estado anterior e posterior).
 */
@Service
public class VinculoColaboradorObraService {

    private static final String FONTE = "GESTAO_VINCULO";
    private static final String TIPO_ENTIDADE = "VINCULO_OBRA";
    private static final String RELACAO_VINCULO = "VINCULADO_A";
    private static final String EVENTO_ATRIBUIDO = "VINCULO_OBRA_ATRIBUIDO";
    private static final String EVENTO_REVOGADO = "VINCULO_OBRA_REVOGADO";
    private static final String PAPEL_PADRAO = "OPERACIONAL";

    private final JdbcTemplate jdbcTemplate;
    private final CortexOperationalMemoryService memoryService;

    public VinculoColaboradorObraService(
            JdbcTemplate jdbcTemplate,
            CortexOperationalMemoryService memoryService
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.memoryService = memoryService;
    }

    public List<VinculoColaboradorObraResponse> listarPorObra(String obraId) {
        String normalizedObraId = exigirObra(obraId);

        return jdbcTemplate.query(
                """
                SELECT
                    v.id,
                    v.obra_id,
                    v.colaborador_id,
                    c.nome AS colaborador_nome,
                    v.status,
                    v.papel_na_obra,
                    v.atribuido_em,
                    v.atribuido_por,
                    v.revogado_em,
                    v.revogado_por
                FROM vinculo_colaborador_obra v
                LEFT JOIN colaborador c ON c.id = v.colaborador_id
                WHERE v.obra_id = ?
                ORDER BY (v.status = 'ATIVO') DESC, v.atribuido_em DESC, v.id
                """,
                (rs, rowNum) -> new VinculoColaboradorObraResponse(
                        rs.getString("id"),
                        rs.getString("obra_id"),
                        rs.getString("colaborador_id"),
                        rs.getString("colaborador_nome"),
                        rs.getString("status"),
                        rs.getString("papel_na_obra"),
                        toLocalDateTime(rs.getTimestamp("atribuido_em")),
                        rs.getString("atribuido_por"),
                        toLocalDateTime(rs.getTimestamp("revogado_em")),
                        rs.getString("revogado_por")
                ),
                normalizedObraId
        );
    }

    /**
     * Vincula (ou reativa) um colaborador a uma obra. Idempotente: revincular um
     * colaborador já ativo retorna o vínculo vigente sem gerar novo evento.
     */
    @Transactional
    public VinculoColaboradorObraResponse vincular(
            String obraId,
            String colaboradorId,
            String papelNaObra,
            String atribuidoPor
    ) {
        return vincularComId(
                null,
                obraId,
                colaboradorId,
                papelNaObra,
                atribuidoPor
        );
    }

    /**
     * Variante canônica que preserva a identidade escolhida no cliente.
     * O endpoint online continua podendo omitir o identificador.
     */
    @Transactional
    public VinculoColaboradorObraResponse vincularComId(
            String requestedVinculoId,
            String obraId,
            String colaboradorId,
            String papelNaObra,
            String atribuidoPor
    ) {
        String normalizedObraId = exigirObra(obraId);
        String normalizedColaboradorId = exigirColaborador(colaboradorId);
        String papel = normalizarPapel(papelNaObra);
        String canonicalRequestedId = requestedVinculoId == null
                ? null
                : normalizarId(requestedVinculoId, "vinculoId");

        VinculoAtual atual = buscarVinculo(normalizedColaboradorId, normalizedObraId);
        LocalDateTime agora = LocalDateTime.now();

        if (atual != null && "ATIVO".equals(atual.status())) {
            if (canonicalRequestedId != null
                    && !canonicalRequestedId.equals(atual.id())) {
                throw new ResponseStatusException(
                        HttpStatus.CONFLICT,
                        "Já existe vínculo ativo com outro identificador."
                );
            }
            return carregar(atual.id());
        }

        String vinculoId;
        String estadoAnterior;

        if (atual == null) {
            vinculoId = canonicalRequestedId == null
                    ? UUID.randomUUID().toString()
                    : canonicalRequestedId;
            estadoAnterior = "INEXISTENTE";
            jdbcTemplate.update(
                    """
                    INSERT INTO vinculo_colaborador_obra (
                        id, obra_id, colaborador_id, status, papel_na_obra,
                        atribuido_em, atribuido_por
                    ) VALUES (?, ?, ?, 'ATIVO', ?, ?, ?)
                    """,
                    vinculoId,
                    normalizedObraId,
                    normalizedColaboradorId,
                    papel,
                    agora,
                    atribuidoPor
            );
        } else {
            vinculoId = atual.id();
            estadoAnterior = atual.status();
            jdbcTemplate.update(
                    """
                    UPDATE vinculo_colaborador_obra
                    SET status = 'ATIVO',
                        papel_na_obra = ?,
                        atribuido_em = ?,
                        atribuido_por = ?,
                        revogado_em = NULL,
                        revogado_por = NULL,
                        versao_linha = versao_linha + 1
                    WHERE id = ?
                    """,
                    papel,
                    agora,
                    atribuidoPor,
                    vinculoId
            );
        }

        registrarNoGrafo(
                vinculoId,
                normalizedObraId,
                normalizedColaboradorId,
                EVENTO_ATRIBUIDO,
                "ATRIBUIR",
                estadoAnterior,
                "ATIVO",
                papel,
                atribuidoPor,
                agora
        );
        memoryService.registrarRelacaoAtiva(
                "COLABORADOR",
                normalizedColaboradorId,
                "OBRA",
                normalizedObraId,
                RELACAO_VINCULO,
                FONTE,
                "Vínculo de autorização do colaborador com a obra."
        );

        return carregar(vinculoId);
    }

    /** Revoga o vínculo ativo, encerrando o acesso Beta sem apagar o histórico. */
    @Transactional
    public VinculoColaboradorObraResponse revogar(
            String obraId,
            String colaboradorId,
            String revogadoPor
    ) {
        String normalizedObraId = exigirObra(obraId);
        String normalizedColaboradorId = normalizarId(colaboradorId, "colaboradorId");

        VinculoAtual atual = buscarVinculo(normalizedColaboradorId, normalizedObraId);
        if (atual == null) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "Vínculo não encontrado para este colaborador nesta obra."
            );
        }

        if ("REVOGADO".equals(atual.status())) {
            return carregar(atual.id());
        }

        LocalDateTime agora = LocalDateTime.now();
        jdbcTemplate.update(
                """
                UPDATE vinculo_colaborador_obra
                SET status = 'REVOGADO',
                    revogado_em = ?,
                    revogado_por = ?,
                    versao_linha = versao_linha + 1
                WHERE id = ?
                """,
                agora,
                revogadoPor,
                atual.id()
        );

        registrarNoGrafo(
                atual.id(),
                normalizedObraId,
                normalizedColaboradorId,
                EVENTO_REVOGADO,
                "REVOGAR",
                atual.status(),
                "REVOGADO",
                atual.papelNaObra(),
                revogadoPor,
                agora
        );
        memoryService.encerrarRelacaoAtiva(
                "COLABORADOR",
                normalizedColaboradorId,
                "OBRA",
                normalizedObraId,
                RELACAO_VINCULO
        );

        return carregar(atual.id());
    }

    private void registrarNoGrafo(
            String vinculoId,
            String obraId,
            String colaboradorId,
            String tipoEvento,
            String operacao,
            String estadoAnterior,
            String estadoPosterior,
            String papelNaObra,
            String ator,
            LocalDateTime ocorridoEm
    ) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("schemaVersion", 1);
        payload.put("obraId", obraId);
        payload.put("colaboradorId", colaboradorId);
        payload.put("vinculoId", vinculoId);
        payload.put("papelNaObra", papelNaObra);
        payload.put("operation", operacao);
        payload.put("beforeState", Map.of("status", estadoAnterior));
        payload.put("afterState", Map.of("status", estadoPosterior));
        payload.put("changedFields", List.of("status"));
        payload.put("actorId", ator);

        Map<String, Object> obraRelacionada = new LinkedHashMap<>();
        obraRelacionada.put("tipo", "OBRA");
        obraRelacionada.put("id", obraId);
        Map<String, Object> colaboradorRelacionado = new LinkedHashMap<>();
        colaboradorRelacionado.put("tipo", "COLABORADOR");
        colaboradorRelacionado.put("id", colaboradorId);

        memoryService.registrarEventoDetalhado(
                UUID.randomUUID().toString(),
                TIPO_ENTIDADE,
                vinculoId,
                tipoEvento,
                FONTE,
                obraId,
                null,
                colaboradorId,
                List.of(obraRelacionada, colaboradorRelacionado),
                "ONLINE",
                "SYNCED",
                ocorridoEm,
                LocalDateTime.now(),
                1,
                payload
        );
    }

    private VinculoAtual buscarVinculo(String colaboradorId, String obraId) {
        List<VinculoAtual> rows = jdbcTemplate.query(
                """
                SELECT id, status, papel_na_obra
                FROM vinculo_colaborador_obra
                WHERE colaborador_id = ?
                  AND obra_id = ?
                LIMIT 1
                """,
                (rs, rowNum) -> new VinculoAtual(
                        rs.getString("id"),
                        rs.getString("status"),
                        rs.getString("papel_na_obra")
                ),
                colaboradorId,
                obraId
        );

        return rows.isEmpty() ? null : rows.getFirst();
    }

    private VinculoColaboradorObraResponse carregar(String vinculoId) {
        try {
            return jdbcTemplate.queryForObject(
                    """
                    SELECT
                        v.id,
                        v.obra_id,
                        v.colaborador_id,
                        c.nome AS colaborador_nome,
                        v.status,
                        v.papel_na_obra,
                        v.atribuido_em,
                        v.atribuido_por,
                        v.revogado_em,
                        v.revogado_por
                    FROM vinculo_colaborador_obra v
                    LEFT JOIN colaborador c ON c.id = v.colaborador_id
                    WHERE v.id = ?
                    """,
                    (rs, rowNum) -> new VinculoColaboradorObraResponse(
                            rs.getString("id"),
                            rs.getString("obra_id"),
                            rs.getString("colaborador_id"),
                            rs.getString("colaborador_nome"),
                            rs.getString("status"),
                            rs.getString("papel_na_obra"),
                            toLocalDateTime(rs.getTimestamp("atribuido_em")),
                            rs.getString("atribuido_por"),
                            toLocalDateTime(rs.getTimestamp("revogado_em")),
                            rs.getString("revogado_por")
                    ),
                    vinculoId
            );
        } catch (EmptyResultDataAccessException exception) {
            throw new ResponseStatusException(
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    "Vínculo não pôde ser recarregado após a operação."
            );
        }
    }

    private String exigirObra(String obraId) {
        String normalized = normalizarId(obraId, "obraId");
        Integer existe = jdbcTemplate.queryForObject(
                """
                SELECT CASE WHEN EXISTS (
                    SELECT 1 FROM obra WHERE id = ? AND arquivado_em IS NULL
                ) THEN 1 ELSE 0 END
                """,
                Integer.class,
                normalized
        );
        if (existe == null || existe == 0) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "Obra não encontrada."
            );
        }
        return normalized;
    }

    private String exigirColaborador(String colaboradorId) {
        String normalized = normalizarId(colaboradorId, "colaboradorId");
        Integer existe = jdbcTemplate.queryForObject(
                """
                SELECT CASE WHEN EXISTS (
                    SELECT 1 FROM colaborador
                    WHERE id = ? AND ativo = TRUE AND deletado_em IS NULL
                ) THEN 1 ELSE 0 END
                """,
                Integer.class,
                normalized
        );
        if (existe == null || existe == 0) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "Colaborador não encontrado ou inativo."
            );
        }
        return normalized;
    }

    private String normalizarPapel(String papelNaObra) {
        if (papelNaObra == null || papelNaObra.isBlank()) {
            return PAPEL_PADRAO;
        }
        return papelNaObra.trim().toUpperCase(Locale.ROOT);
    }

    private String normalizarId(String value, String campo) {
        if (value == null || value.isBlank()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    campo + " é obrigatório."
            );
        }
        return value.trim();
    }

    private static LocalDateTime toLocalDateTime(java.sql.Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toLocalDateTime();
    }

    /** Estado mínimo do vínculo vigente. Visível no pacote para testes. */
    record VinculoAtual(String id, String status, String papelNaObra) {
    }
}
