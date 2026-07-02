package com.projeto.cortex.intelligence.stavia.api;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Time;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class StaviaSnapshotService {

    private static final int MAX_OBRAS = 500;
    private static final int MAX_RDOS = 1_500;
    private static final int MAX_CHILD_ROWS = 8_000;
    private static final String DICTIONARY_VERSION =
            "STAVIA-PT-BR-0.1.0";

    private final JdbcTemplate jdbcTemplate;

    public StaviaSnapshotService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public StaviaSnapshotResponse buildSnapshot() {
        List<StaviaSnapshotResponse.ObraSnapshot> obras = obras();
        List<RdoBase> rdoBases = rdos();
        List<String> rdoIds = rdoBases.stream()
                .map(RdoBase::id)
                .toList();

        Map<String, List<StaviaSnapshotResponse.MaoObraSnapshot>> maoObra =
                maoObra(rdoIds);
        Map<String, List<StaviaSnapshotResponse.EquipamentoSnapshot>> equipamentos =
                equipamentos(rdoIds);
        Map<String, List<StaviaSnapshotResponse.MaterialSnapshot>> materiais =
                materiais(rdoIds);
        Map<String, List<StaviaSnapshotResponse.ControleGeometricoSnapshot>> controles =
                controles(rdoIds);
        Map<String, List<StaviaSnapshotResponse.ServicoExecutadoSnapshot>> servicos =
                servicosExecutados(rdoIds);
        Map<String, List<StaviaSnapshotResponse.AlocacaoSnapshot>> alocacoes =
                alocacoes(rdoIds);

        List<StaviaSnapshotResponse.RdoSnapshot> rdos =
                rdoBases.stream()
                        .map(rdo -> new StaviaSnapshotResponse.RdoSnapshot(
                                rdo.id(),
                                rdo.obraId(),
                                rdo.programacaoId(),
                                rdo.numeroRdo(),
                                rdo.dataRdo(),
                                rdo.cliente(),
                                rdo.cidade(),
                                rdo.contrato(),
                                rdo.rodovia(),
                                rdo.uf(),
                                rdo.kmInicialProgramado(),
                                rdo.kmFinalProgramado(),
                                rdo.kmInicialInterditado(),
                                rdo.kmFinalInterditado(),
                                rdo.turno(),
                                rdo.horaInicio(),
                                rdo.horaFim(),
                                rdo.condicaoManha(),
                                rdo.condicaoTarde(),
                                rdo.condicaoNoite(),
                                rdo.pluviometriaMm(),
                                rdo.status(),
                                rdo.observacoes(),
                                rdo.preenchidoPor(),
                                rdo.apontadorRdo(),
                                rdo.encarregadoObra(),
                                rdo.fiscalizacaoCampo(),
                                rdo.updatedAt(),
                                servicos.getOrDefault(rdo.id(), List.of()),
                                maoObra.getOrDefault(rdo.id(), List.of()),
                                equipamentos.getOrDefault(rdo.id(), List.of()),
                                materiais.getOrDefault(rdo.id(), List.of()),
                                controles.getOrDefault(rdo.id(), List.of()),
                                alocacoes.getOrDefault(rdo.id(), List.of())
                        ))
                        .toList();

        return new StaviaSnapshotResponse(
                new StaviaSnapshotResponse.Metadata(
                        "default",
                        LocalDateTime.now(),
                        databaseUpdatedAt(),
                        null,
                        "CORTEX_OPERATIONAL_MEMORY",
                        "COMPLETO",
                        DICTIONARY_VERSION
                ),
                obras,
                rdos,
                programacoes(),
                pdocs()
        );
    }

    private List<StaviaSnapshotResponse.ObraSnapshot> obras() {
        return jdbcTemplate.query(
                """
                SELECT
                    id,
                    codigo_contrato,
                    codigo_cw,
                    codigo_interno,
                    nome,
                    cliente,
                    cidade,
                    uf,
                    rodovia,
                    status,
                    atualizado_em
                FROM obra
                WHERE arquivado_em IS NULL
                ORDER BY atualizado_em DESC, id
                LIMIT ?
                """,
                (rs, rowNum) -> new StaviaSnapshotResponse.ObraSnapshot(
                        rs.getString("id"),
                        rs.getString("codigo_contrato"),
                        rs.getString("codigo_cw"),
                        rs.getString("codigo_interno"),
                        rs.getString("nome"),
                        rs.getString("cliente"),
                        rs.getString("cidade"),
                        rs.getString("uf"),
                        rs.getString("rodovia"),
                        rs.getString("status"),
                        toLocalDateTime(rs.getTimestamp("atualizado_em"))
                ),
                MAX_OBRAS
        );
    }

    private List<RdoBase> rdos() {
        return jdbcTemplate.query(
                """
                SELECT
                    id,
                    obra_id,
                    programacao_id,
                    numero_rdo,
                    data_rdo,
                    cliente,
                    cidade,
                    contrato,
                    rodovia,
                    uf,
                    km_inicial_programado,
                    km_final_programado,
                    km_inicial_interditado,
                    km_final_interditado,
                    turno,
                    hora_inicio,
                    hora_fim,
                    condicao_manha,
                    condicao_tarde,
                    condicao_noite,
                    pluviometria_mm,
                    status,
                    observacoes,
                    preenchido_por,
                    apontador_rdo,
                    encarregado_obra,
                    fiscalizacao_campo,
                    atualizado_em
                FROM rdo
                WHERE cancelado_em IS NULL
                ORDER BY data_rdo DESC, atualizado_em DESC, id
                LIMIT ?
                """,
                (rs, rowNum) -> new RdoBase(
                        rs.getString("id"),
                        rs.getString("obra_id"),
                        rs.getString("programacao_id"),
                        rs.getString("numero_rdo"),
                        toLocalDate(rs, "data_rdo"),
                        rs.getString("cliente"),
                        rs.getString("cidade"),
                        rs.getString("contrato"),
                        rs.getString("rodovia"),
                        rs.getString("uf"),
                        rs.getString("km_inicial_programado"),
                        rs.getString("km_final_programado"),
                        rs.getString("km_inicial_interditado"),
                        rs.getString("km_final_interditado"),
                        rs.getString("turno"),
                        toLocalTime(rs.getTime("hora_inicio")),
                        toLocalTime(rs.getTime("hora_fim")),
                        rs.getString("condicao_manha"),
                        rs.getString("condicao_tarde"),
                        rs.getString("condicao_noite"),
                        rs.getBigDecimal("pluviometria_mm"),
                        rs.getString("status"),
                        rs.getString("observacoes"),
                        rs.getString("preenchido_por"),
                        rs.getString("apontador_rdo"),
                        rs.getString("encarregado_obra"),
                        rs.getString("fiscalizacao_campo"),
                        toLocalDateTime(rs.getTimestamp("atualizado_em"))
                ),
                MAX_RDOS
        );
    }

    private Map<String, List<StaviaSnapshotResponse.MaoObraSnapshot>>
    maoObra(List<String> rdoIds) {
        if (rdoIds.isEmpty()) {
            return Map.of();
        }

        return grouped(
                jdbcTemplate.query(
                        """
                        SELECT
                            rdo_id,
                            nome_colaborador,
                            cargo,
                            tipo_vinculo,
                            quantidade
                        FROM rdo_mao_obra mo
                        JOIN (
                            SELECT id
                            FROM rdo
                            WHERE cancelado_em IS NULL
                            ORDER BY data_rdo DESC, atualizado_em DESC, id
                            LIMIT ?
                        ) recent_rdo ON recent_rdo.id = mo.rdo_id
                        ORDER BY mo.rdo_id, cargo, nome_colaborador
                        LIMIT ?
                        """,
                        (rs, rowNum) -> new GroupedItem<>(
                                rs.getString("rdo_id"),
                                new StaviaSnapshotResponse.MaoObraSnapshot(
                                        rs.getString("nome_colaborador"),
                                        rs.getString("cargo"),
                                        rs.getString("tipo_vinculo"),
                                        rs.getBigDecimal("quantidade")
                                )
                        ),
                        MAX_RDOS,
                        MAX_CHILD_ROWS
                )
        );
    }

    private Map<String, List<StaviaSnapshotResponse.EquipamentoSnapshot>>
    equipamentos(List<String> rdoIds) {
        if (rdoIds.isEmpty()) {
            return Map.of();
        }

        return grouped(
                jdbcTemplate.query(
                        """
                        SELECT
                            rdo_id,
                            prefixo,
                            descricao,
                            tipo_equipamento,
                            tipo_vinculo,
                            quantidade
                        FROM rdo_equipamento eq
                        JOIN (
                            SELECT id
                            FROM rdo
                            WHERE cancelado_em IS NULL
                            ORDER BY data_rdo DESC, atualizado_em DESC, id
                            LIMIT ?
                        ) recent_rdo ON recent_rdo.id = eq.rdo_id
                        ORDER BY eq.rdo_id, prefixo, descricao
                        LIMIT ?
                        """,
                        (rs, rowNum) -> new GroupedItem<>(
                                rs.getString("rdo_id"),
                                new StaviaSnapshotResponse.EquipamentoSnapshot(
                                        rs.getString("prefixo"),
                                        rs.getString("descricao"),
                                        rs.getString("tipo_equipamento"),
                                        rs.getString("tipo_vinculo"),
                                        rs.getBigDecimal("quantidade")
                                )
                        ),
                        MAX_RDOS,
                        MAX_CHILD_ROWS
                )
        );
    }

    private Map<String, List<StaviaSnapshotResponse.MaterialSnapshot>>
    materiais(List<String> rdoIds) {
        if (rdoIds.isEmpty()) {
            return Map.of();
        }

        return grouped(
                jdbcTemplate.query(
                        """
                        SELECT
                            rdo_id,
                            material_nome,
                            unidade,
                            quantidade_prevista,
                            quantidade_usinada,
                            quantidade_aplicada,
                            quantidade_sobra
                        FROM rdo_material mat
                        JOIN (
                            SELECT id
                            FROM rdo
                            WHERE cancelado_em IS NULL
                            ORDER BY data_rdo DESC, atualizado_em DESC, id
                            LIMIT ?
                        ) recent_rdo ON recent_rdo.id = mat.rdo_id
                        ORDER BY mat.rdo_id, material_nome
                        LIMIT ?
                        """,
                        (rs, rowNum) -> new GroupedItem<>(
                                rs.getString("rdo_id"),
                                new StaviaSnapshotResponse.MaterialSnapshot(
                                        rs.getString("material_nome"),
                                        rs.getString("unidade"),
                                        rs.getBigDecimal("quantidade_prevista"),
                                        rs.getBigDecimal("quantidade_usinada"),
                                        rs.getBigDecimal("quantidade_aplicada"),
                                        rs.getBigDecimal("quantidade_sobra")
                                )
                        ),
                        MAX_RDOS,
                        MAX_CHILD_ROWS
                )
        );
    }

    private Map<String, List<StaviaSnapshotResponse.ControleGeometricoSnapshot>>
    controles(List<String> rdoIds) {
        if (rdoIds.isEmpty()) {
            return Map.of();
        }

        return grouped(
                jdbcTemplate.query(
                        """
                        SELECT
                            rdo_id,
                            subtrecho,
                            numero,
                            km_inicial,
                            km_final,
                            pista,
                            faixa,
                            ordem_servico,
                            comprimento_m,
                            largura_m,
                            area_m2,
                            volume_m3,
                            atividade_observacoes
                        FROM rdo_controle_geometrico cg
                        JOIN (
                            SELECT id
                            FROM rdo
                            WHERE cancelado_em IS NULL
                            ORDER BY data_rdo DESC, atualizado_em DESC, id
                            LIMIT ?
                        ) recent_rdo ON recent_rdo.id = cg.rdo_id
                        ORDER BY cg.rdo_id, subtrecho
                        LIMIT ?
                        """,
                        (rs, rowNum) -> new GroupedItem<>(
                                rs.getString("rdo_id"),
                                new StaviaSnapshotResponse.ControleGeometricoSnapshot(
                                        rs.getString("subtrecho"),
                                        rs.getString("numero"),
                                        rs.getString("km_inicial"),
                                        rs.getString("km_final"),
                                        rs.getString("pista"),
                                        rs.getString("faixa"),
                                        rs.getString("ordem_servico"),
                                        rs.getBigDecimal("comprimento_m"),
                                        rs.getBigDecimal("largura_m"),
                                        rs.getBigDecimal("area_m2"),
                                        rs.getBigDecimal("volume_m3"),
                                        rs.getString("atividade_observacoes")
                                )
                        ),
                        MAX_RDOS,
                        MAX_CHILD_ROWS
                )
        );
    }

    private Map<String, List<StaviaSnapshotResponse.ServicoExecutadoSnapshot>>
    servicosExecutados(List<String> rdoIds) {
        if (rdoIds.isEmpty()) {
            return Map.of();
        }

        return grouped(
                jdbcTemplate.query(
                        """
                        SELECT
                            rdo_id,
                            servico_nome,
                            quantidade_executada,
                            unidade_medida,
                            trecho_inicial,
                            trecho_final,
                            localizacao,
                            turno,
                            status_validacao,
                            observacoes
                        FROM execucao_servico_rdo servico
                        JOIN (
                            SELECT id
                            FROM rdo
                            WHERE cancelado_em IS NULL
                            ORDER BY data_rdo DESC, atualizado_em DESC, id
                            LIMIT ?
                        ) recent_rdo ON recent_rdo.id = servico.rdo_id
                        WHERE servico.cancelada = 0
                        ORDER BY servico.rdo_id, servico.servico_nome
                        LIMIT ?
                        """,
                        (rs, rowNum) -> new GroupedItem<>(
                                rs.getString("rdo_id"),
                                new StaviaSnapshotResponse.ServicoExecutadoSnapshot(
                                        rs.getString("servico_nome"),
                                        rs.getBigDecimal("quantidade_executada"),
                                        rs.getString("unidade_medida"),
                                        rs.getString("trecho_inicial"),
                                        rs.getString("trecho_final"),
                                        rs.getString("localizacao"),
                                        rs.getString("turno"),
                                        rs.getString("status_validacao"),
                                        rs.getString("observacoes")
                                )
                        ),
                        MAX_RDOS,
                        MAX_CHILD_ROWS
                )
        );
    }

    private Map<String, List<StaviaSnapshotResponse.AlocacaoSnapshot>>
    alocacoes(List<String> rdoIds) {
        if (rdoIds.isEmpty()) {
            return Map.of();
        }

        return grouped(
                jdbcTemplate.query(
                        """
                        SELECT
                            rdo_id,
                            al.colaborador_id,
                            colaborador.nome AS nome_colaborador,
                            equipe,
                            servico_nome,
                            hora_inicio,
                            hora_fim,
                            turno,
                            funcao,
                            status
                        FROM alocacao_colaborador al
                        LEFT JOIN colaborador
                          ON colaborador.id = al.colaborador_id
                        JOIN (
                            SELECT id
                            FROM rdo
                            WHERE cancelado_em IS NULL
                            ORDER BY data_rdo DESC, atualizado_em DESC, id
                            LIMIT ?
                        ) recent_rdo ON recent_rdo.id = al.rdo_id
                        ORDER BY al.rdo_id, equipe, funcao
                        LIMIT ?
                        """,
                        (rs, rowNum) -> new GroupedItem<>(
                                rs.getString("rdo_id"),
                                new StaviaSnapshotResponse.AlocacaoSnapshot(
                                        rs.getString("colaborador_id"),
                                        rs.getString("nome_colaborador"),
                                        rs.getString("equipe"),
                                        rs.getString("servico_nome"),
                                        toLocalTime(rs.getTime("hora_inicio")),
                                        toLocalTime(rs.getTime("hora_fim")),
                                        rs.getString("turno"),
                                        rs.getString("funcao"),
                                        rs.getString("status")
                                )
                        ),
                        MAX_RDOS,
                        MAX_CHILD_ROWS
                )
        );
    }

    private List<StaviaSnapshotResponse.PdocSnapshot> pdocs() {
        return jdbcTemplate.query(
                """
                SELECT
                    p.obra_id,
                    p.id,
                    p.data_referencia,
                    p.executado_em,
                    p.status_execucao,
                    p.calibracao,
                    p.nivel_risco,
                    p.prob_qualquer_excedente,
                    p.prob_exceder_5_pct,
                    p.prob_exceder_10_pct,
                    p.score_heuristico,
                    p.confianca
                FROM pdoc_snapshot p
                JOIN (
                    SELECT obra_id, MAX(executado_em) AS max_executado_em
                    FROM pdoc_snapshot
                    GROUP BY obra_id
                ) latest
                  ON latest.obra_id = p.obra_id
                 AND latest.max_executado_em = p.executado_em
                ORDER BY p.executado_em DESC, p.id
                LIMIT ?
                """,
                (rs, rowNum) -> new StaviaSnapshotResponse.PdocSnapshot(
                        rs.getString("obra_id"),
                        rs.getString("id"),
                        toLocalDate(rs, "data_referencia"),
                        toLocalDateTime(rs.getTimestamp("executado_em")),
                        rs.getString("status_execucao"),
                        rs.getString("calibracao"),
                        rs.getString("nivel_risco"),
                        rs.getBigDecimal("prob_qualquer_excedente"),
                        rs.getBigDecimal("prob_exceder_5_pct"),
                        rs.getBigDecimal("prob_exceder_10_pct"),
                        rs.getBigDecimal("score_heuristico"),
                        rs.getBigDecimal("confianca")
                ),
                MAX_OBRAS
        );
    }

    private List<StaviaSnapshotResponse.ProgramacaoSnapshot> programacoes() {
        return jdbcTemplate.query(
                """
                SELECT
                    p.id,
                    p.obra_id,
                    (
                        SELECT r.id
                        FROM rdo r
                        WHERE r.programacao_id = p.id
                          AND r.cancelado_em IS NULL
                        ORDER BY r.data_rdo DESC, r.atualizado_em DESC, r.id
                        LIMIT 1
                    ) AS rdo_id,
                    p.data_programacao,
                    p.equipe,
                    p.fechamento,
                    p.encarregado,
                    p.encarregado_colaborador_id,
                    p.engenheiro,
                    p.cliente,
                    p.servico,
                    p.tipo_servico,
                    p.cidade,
                    p.uf,
                    p.rodovia,
                    p.sentido,
                    p.periodo,
                    p.faixa,
                    p.km_inicial,
                    p.km_final,
                    p.extensao_m,
                    p.largura_m,
                    p.espessura_cm,
                    p.area_m2,
                    p.volume_m3,
                    p.tonelada_massa,
                    p.tipo_cap,
                    p.teor_cap_projeto,
                    p.cap,
                    p.status,
                    p.fonte_criacao,
                    p.fonte_arquivo,
                    p.linha_origem,
                    p.observacoes,
                    p.atualizado_em
                FROM programacao_operacional p
                WHERE p.cancelado_em IS NULL
                ORDER BY p.data_programacao DESC, p.atualizado_em DESC, p.id
                LIMIT ?
                """,
                (rs, rowNum) -> new StaviaSnapshotResponse.ProgramacaoSnapshot(
                        rs.getString("id"),
                        rs.getString("obra_id"),
                        rs.getString("rdo_id"),
                        toLocalDate(rs, "data_programacao"),
                        rs.getString("equipe"),
                        rs.getString("fechamento"),
                        rs.getString("encarregado"),
                        rs.getString("encarregado_colaborador_id"),
                        rs.getString("engenheiro"),
                        rs.getString("cliente"),
                        rs.getString("servico"),
                        rs.getString("tipo_servico"),
                        rs.getString("cidade"),
                        rs.getString("uf"),
                        rs.getString("rodovia"),
                        rs.getString("sentido"),
                        rs.getString("periodo"),
                        rs.getString("faixa"),
                        rs.getString("km_inicial"),
                        rs.getString("km_final"),
                        rs.getBigDecimal("extensao_m"),
                        rs.getBigDecimal("largura_m"),
                        rs.getBigDecimal("espessura_cm"),
                        rs.getBigDecimal("area_m2"),
                        rs.getBigDecimal("volume_m3"),
                        rs.getBigDecimal("tonelada_massa"),
                        rs.getString("tipo_cap"),
                        rs.getBigDecimal("teor_cap_projeto"),
                        rs.getBigDecimal("cap"),
                        rs.getString("status"),
                        rs.getString("fonte_criacao"),
                        rs.getString("fonte_arquivo"),
                        toInteger(rs, "linha_origem"),
                        rs.getString("observacoes"),
                        toLocalDateTime(rs.getTimestamp("atualizado_em"))
                ),
                MAX_RDOS
        );
    }

    private LocalDateTime databaseUpdatedAt() {
        return jdbcTemplate.queryForObject(
                """
                SELECT MAX(updated_at) AS updated_at
                FROM (
                    SELECT MAX(atualizado_em) AS updated_at FROM obra
                    UNION ALL
                    SELECT MAX(atualizado_em) AS updated_at FROM rdo
                    UNION ALL
                    SELECT MAX(atualizado_em) AS updated_at FROM programacao_operacional
                    UNION ALL
                    SELECT MAX(executado_em) AS updated_at FROM pdoc_snapshot
                ) updates
                """,
                (rs, rowNum) -> toLocalDateTime(
                        rs.getTimestamp("updated_at")
                )
        );
    }

    private <T> Map<String, List<T>> grouped(
            List<GroupedItem<T>> rows
    ) {
        Map<String, List<T>> grouped = new LinkedHashMap<>();

        for (GroupedItem<T> row : rows) {
            grouped.computeIfAbsent(
                    row.rdoId(),
                    ignored -> new ArrayList<>()
            ).add(row.value());
        }

        return grouped;
    }

    private LocalDate toLocalDate(ResultSet rs, String column)
            throws SQLException {
        java.sql.Date date = rs.getDate(column);
        return date == null ? null : date.toLocalDate();
    }

    private LocalDateTime toLocalDateTime(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toLocalDateTime();
    }

    private LocalTime toLocalTime(Time time) {
        return time == null ? null : time.toLocalTime();
    }

    private Integer toInteger(ResultSet rs, String column)
            throws SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }

    private record GroupedItem<T>(String rdoId, T value) {
    }

    private record RdoBase(
            String id,
            String obraId,
            String programacaoId,
            String numeroRdo,
            LocalDate dataRdo,
            String cliente,
            String cidade,
            String contrato,
            String rodovia,
            String uf,
            String kmInicialProgramado,
            String kmFinalProgramado,
            String kmInicialInterditado,
            String kmFinalInterditado,
            String turno,
            LocalTime horaInicio,
            LocalTime horaFim,
            String condicaoManha,
            String condicaoTarde,
            String condicaoNoite,
            BigDecimal pluviometriaMm,
            String status,
            String observacoes,
            String preenchidoPor,
            String apontadorRdo,
            String encarregadoObra,
            String fiscalizacaoCampo,
            LocalDateTime updatedAt
    ) {
    }
}
