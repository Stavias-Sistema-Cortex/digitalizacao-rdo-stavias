package com.projeto.cortex.intelligence.stavia.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
    private final ObjectMapper objectMapper;
    private final com.projeto.cortex.intelligence.stavia.semantic.rdo
            .RdoOntology rdoOntology;

    public StaviaSnapshotService(JdbcTemplate jdbcTemplate) {
        this(
                jdbcTemplate,
                com.projeto.cortex.intelligence.stavia.semantic.rdo
                        .RdoOntology.load(),
                new ObjectMapper()
        );
    }

    @org.springframework.beans.factory.annotation.Autowired
    public StaviaSnapshotService(
            JdbcTemplate jdbcTemplate,
            com.projeto.cortex.intelligence.stavia.semantic.rdo
                    .RdoOntology rdoOntology,
            ObjectMapper objectMapper
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.rdoOntology = rdoOntology;
        this.objectMapper = objectMapper;
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
        Map<String, List<StaviaSnapshotResponse.AttachmentSnapshot>> attachments =
                attachments(rdoIds);

        List<StaviaSnapshotResponse.RdoSnapshot> rdos =
                rdoBases.stream()
                        .map(rdo -> new StaviaSnapshotResponse.RdoSnapshot(
                                rdo.id(),
                                rdo.obraId(),
                                rdo.programacaoId(),
                                rdo.numeroRdo(),
                                rdo.dataRdo(),
                                rdo.diaSemana(),
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
                                rdo.fonteCriacao(),
                                rdo.estadoReceita(),
                                rdo.fonteArquivo(),
                                rdo.abaOrigem(),
                                rdo.linhaOrigem(),
                                rdo.dataOriginal(),
                                rdo.dataImportacao(),
                                rdo.usuarioImportacao(),
                                rdo.criadoEm(),
                                rdo.enviadoEm(),
                                rdo.aprovadoEm(),
                                rdo.versaoLinha(),
                                "SYNCED",
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
                                alocacoes.getOrDefault(rdo.id(), List.of()),
                                attachments.getOrDefault(rdo.id(), List.of())
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
                pdors(),
                operationalEvents(rdoIds),
                rdoOntology.raw()
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
                    dia_semana,
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
                    fonte_criacao,
                    estado_receita,
                    fonte_arquivo,
                    aba_origem,
                    linha_origem,
                    data_original,
                    data_importacao,
                    usuario_importacao,
                    criado_em,
                    enviado_em,
                    aprovado_em,
                    versao_linha,
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
                        rs.getString("dia_semana"),
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
                        rs.getString("fonte_criacao"),
                        rs.getString("estado_receita"),
                        rs.getString("fonte_arquivo"),
                        rs.getString("aba_origem"),
                        rs.getObject("linha_origem", Integer.class),
                        toLocalDate(rs, "data_original"),
                        toLocalDateTime(rs.getTimestamp("data_importacao")),
                        rs.getString("usuario_importacao"),
                        toLocalDateTime(rs.getTimestamp("criado_em")),
                        toLocalDateTime(rs.getTimestamp("enviado_em")),
                        toLocalDateTime(rs.getTimestamp("aprovado_em")),
                        rs.getObject("versao_linha", Long.class),
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
                            colaborador_id,
                            nome_colaborador,
                            cargo,
                            tipo_vinculo,
                            quantidade,
                            hora_inicio,
                            hora_fim,
                            observacoes
                        FROM rdo_mao_obra mo
                        JOIN (
                            SELECT id
                            FROM rdo
                            WHERE cancelado_em IS NULL
                            ORDER BY data_rdo DESC, atualizado_em DESC, id
                            LIMIT ?
                        ) recent_rdo ON recent_rdo.id = mo.rdo_id
                        ORDER BY mo.rdo_id, mo.criado_em, mo.id
                        LIMIT ?
                        """,
                        (rs, rowNum) -> new GroupedItem<>(
                                rs.getString("rdo_id"),
                                new StaviaSnapshotResponse.MaoObraSnapshot(
                                        rs.getString("colaborador_id"),
                                        rs.getString("nome_colaborador"),
                                        rs.getString("cargo"),
                                        rs.getString("tipo_vinculo"),
                                        rs.getBigDecimal("quantidade"),
                                        toLocalTime(rs.getTime("hora_inicio")),
                                        toLocalTime(rs.getTime("hora_fim")),
                                        rs.getString("observacoes")
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
                            asset_id,
                            prefixo,
                            descricao,
                            tipo_equipamento,
                            tipo_vinculo,
                            quantidade,
                            hora_inicio,
                            hora_fim,
                            observacoes
                        FROM rdo_equipamento eq
                        JOIN (
                            SELECT id
                            FROM rdo
                            WHERE cancelado_em IS NULL
                            ORDER BY data_rdo DESC, atualizado_em DESC, id
                            LIMIT ?
                        ) recent_rdo ON recent_rdo.id = eq.rdo_id
                        ORDER BY eq.rdo_id, eq.criado_em, eq.id
                        LIMIT ?
                        """,
                        (rs, rowNum) -> new GroupedItem<>(
                                rs.getString("rdo_id"),
                                new StaviaSnapshotResponse.EquipamentoSnapshot(
                                        rs.getString("asset_id"),
                                        rs.getString("prefixo"),
                                        rs.getString("descricao"),
                                        rs.getString("tipo_equipamento"),
                                        rs.getString("tipo_vinculo"),
                                        rs.getBigDecimal("quantidade"),
                                        toLocalTime(rs.getTime("hora_inicio")),
                                        toLocalTime(rs.getTime("hora_fim")),
                                        rs.getString("observacoes")
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
                            quantidade_sobra,
                            nota_fiscal,
                            fornecedor,
                            observacoes
                        FROM rdo_material mat
                        JOIN (
                            SELECT id
                            FROM rdo
                            WHERE cancelado_em IS NULL
                            ORDER BY data_rdo DESC, atualizado_em DESC, id
                            LIMIT ?
                        ) recent_rdo ON recent_rdo.id = mat.rdo_id
                        ORDER BY mat.rdo_id, mat.criado_em, mat.id
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
                                        rs.getBigDecimal("quantidade_sobra"),
                                        rs.getString("nota_fiscal"),
                                        rs.getString("fornecedor"),
                                        rs.getString("observacoes")
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
                            estaca_inicial,
                            estaca_final,
                            km_inicial,
                            km_final,
                            pista,
                            faixa,
                            ordem_servico,
                            comprimento_m,
                            largura_m,
                            espessura_1_cm,
                            espessura_2_cm,
                            espessura_3_cm,
                            espessura_media_cm,
                            area_m2,
                            volume_m3,
                            densidade,
                            massa_tonelada,
                            atividade_observacoes,
                            observacoes
                        FROM rdo_controle_geometrico cg
                        JOIN (
                            SELECT id
                            FROM rdo
                            WHERE cancelado_em IS NULL
                            ORDER BY data_rdo DESC, atualizado_em DESC, id
                            LIMIT ?
                        ) recent_rdo ON recent_rdo.id = cg.rdo_id
                        ORDER BY cg.rdo_id, cg.criado_em, cg.id
                        LIMIT ?
                        """,
                        (rs, rowNum) -> new GroupedItem<>(
                                rs.getString("rdo_id"),
                                new StaviaSnapshotResponse.ControleGeometricoSnapshot(
                                        rs.getString("subtrecho"),
                                        rs.getString("numero"),
                                        rs.getString("estaca_inicial"),
                                        rs.getString("estaca_final"),
                                        rs.getString("km_inicial"),
                                        rs.getString("km_final"),
                                        rs.getString("pista"),
                                        rs.getString("faixa"),
                                        rs.getString("ordem_servico"),
                                        rs.getBigDecimal("comprimento_m"),
                                        rs.getBigDecimal("largura_m"),
                                        rs.getBigDecimal("espessura_1_cm"),
                                        rs.getBigDecimal("espessura_2_cm"),
                                        rs.getBigDecimal("espessura_3_cm"),
                                        rs.getBigDecimal("espessura_media_cm"),
                                        rs.getBigDecimal("area_m2"),
                                        rs.getBigDecimal("volume_m3"),
                                        rs.getBigDecimal("densidade"),
                                        rs.getBigDecimal("massa_tonelada"),
                                        rs.getString("atividade_observacoes"),
                                        rs.getString("observacoes")
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
                            item_contratual_id,
                            quantidade_executada,
                            unidade_medida,
                            trecho_inicial,
                            trecho_final,
                            localizacao,
                            data_execucao,
                            turno,
                            status_validacao,
                            estado_receita,
                            receita_operacional_estimativa,
                            custo_realizado,
                            retrabalho,
                            producao_rejeitada,
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
                        ORDER BY servico.rdo_id, servico.criado_em, servico.id
                        LIMIT ?
                        """,
                        (rs, rowNum) -> new GroupedItem<>(
                                rs.getString("rdo_id"),
                                new StaviaSnapshotResponse.ServicoExecutadoSnapshot(
                                        rs.getString("servico_nome"),
                                        rs.getString("item_contratual_id"),
                                        rs.getBigDecimal("quantidade_executada"),
                                        rs.getString("unidade_medida"),
                                        rs.getString("trecho_inicial"),
                                        rs.getString("trecho_final"),
                                        rs.getString("localizacao"),
                                        toLocalDate(rs, "data_execucao"),
                                        rs.getString("turno"),
                                        rs.getString("status_validacao"),
                                        rs.getString("estado_receita"),
                                        rs.getBigDecimal("receita_operacional_estimativa"),
                                        rs.getBigDecimal("custo_realizado"),
                                        toBoolean(rs, "retrabalho"),
                                        toBoolean(rs, "producao_rejeitada"),
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
                            minutos,
                            percentual_dia,
                            turno,
                            funcao,
                            centro_custo,
                            tipo_alocacao,
                            fonte,
                            status,
                            custo_hora,
                            custo_total,
                            observacoes
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
                        ORDER BY al.rdo_id, al.criado_em, al.id
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
                                        rs.getObject("minutos", Integer.class),
                                        rs.getBigDecimal("percentual_dia"),
                                        rs.getString("turno"),
                                        rs.getString("funcao"),
                                        rs.getString("centro_custo"),
                                        rs.getString("tipo_alocacao"),
                                        rs.getString("fonte"),
                                        rs.getString("status"),
                                        rs.getBigDecimal("custo_hora"),
                                        rs.getBigDecimal("custo_total"),
                                        rs.getString("observacoes")
                                )
                        ),
                        MAX_RDOS,
                        MAX_CHILD_ROWS
                )
        );
    }

    private Map<String, List<StaviaSnapshotResponse.AttachmentSnapshot>>
    attachments(List<String> rdoIds) {
        if (rdoIds.isEmpty()) {
            return Map.of();
        }

        return grouped(
                jdbcTemplate.query(
                        """
                        SELECT
                            attachment.rdo_id,
                            attachment.id,
                            attachment.obra_id,
                            attachment.tipo,
                            attachment.nome,
                            attachment.nome_original,
                            attachment.mime_type,
                            attachment.tamanho_original_bytes,
                            attachment.tamanho_comprimido_bytes,
                            attachment.tamanho_bytes,
                            attachment.sync_status,
                            attachment.criado_em,
                            attachment.atualizado_em,
                            attachment.removido_em,
                            attachment.metadata_json
                        FROM rdo_attachment attachment
                        JOIN (
                            SELECT id
                            FROM rdo
                            WHERE cancelado_em IS NULL
                            ORDER BY data_rdo DESC, atualizado_em DESC, id
                            LIMIT ?
                        ) recent_rdo ON recent_rdo.id = attachment.rdo_id
                        ORDER BY attachment.rdo_id, attachment.criado_em, attachment.id
                        LIMIT ?
                        """,
                        (rs, rowNum) -> new GroupedItem<>(
                                rs.getString("rdo_id"),
                                new StaviaSnapshotResponse.AttachmentSnapshot(
                                        rs.getString("id"),
                                        rs.getString("rdo_id"),
                                        rs.getString("obra_id"),
                                        rs.getString("tipo"),
                                        rs.getString("nome"),
                                        rs.getString("nome_original"),
                                        rs.getString("mime_type"),
                                        rs.getObject("tamanho_original_bytes", Long.class),
                                        rs.getObject("tamanho_comprimido_bytes", Long.class),
                                        rs.getObject("tamanho_bytes", Long.class),
                                        rs.getString("sync_status"),
                                        toLocalDateTime(rs.getTimestamp("criado_em")),
                                        toLocalDateTime(rs.getTimestamp("atualizado_em")),
                                        toLocalDateTime(rs.getTimestamp("removido_em")),
                                        parseJson(rs.getString("metadata_json"), true)
                                )
                        ),
                        MAX_RDOS,
                        MAX_CHILD_ROWS
                )
        );
    }

    private List<StaviaSnapshotResponse.OperationalEventSnapshot>
    operationalEvents(List<String> rdoIds) {
        if (rdoIds.isEmpty()) {
            return List.of();
        }

        return jdbcTemplate.query(
                """
                SELECT
                    oper_event.id,
                    oper_event.tipo_evento,
                    oper_event.tipo_entidade,
                    oper_event.entidade_id,
                    oper_event.obra_id,
                    oper_event.rdo_id,
                    oper_event.colaborador_id,
                    oper_event.ocorrido_em,
                    oper_event.sincronizado_em,
                    oper_event.origem,
                    oper_event.sync_status,
                    COALESCE(
                        JSON_UNQUOTE(JSON_EXTRACT(oper_event.payload_json, '$.responsibleUserId')),
                        CONVERT(oper_event.usuario_id USING utf8mb4)
                    ) AS responsible_user_id,
                    JSON_UNQUOTE(JSON_EXTRACT(oper_event.payload_json, '$.responsibleUserName'))
                        AS responsible_user_name,
                    oper_event.schema_version,
                    oper_event.entidades_relacionadas_json,
                    oper_event.payload_json
                FROM cortex_evento_operacional oper_event
                JOIN (
                    SELECT id
                    FROM rdo
                    WHERE cancelado_em IS NULL
                    ORDER BY data_rdo DESC, atualizado_em DESC, id
                    LIMIT ?
                ) recent_rdo ON recent_rdo.id = oper_event.rdo_id
                ORDER BY oper_event.ocorrido_em DESC, oper_event.commit_seq DESC
                LIMIT ?
                """,
                (rs, rowNum) -> new StaviaSnapshotResponse.OperationalEventSnapshot(
                        rs.getString("id"),
                        rs.getString("tipo_evento"),
                        rs.getString("tipo_entidade"),
                        rs.getString("entidade_id"),
                        rs.getString("obra_id"),
                        rs.getString("rdo_id"),
                        rs.getString("colaborador_id"),
                        toLocalDateTime(rs.getTimestamp("ocorrido_em")),
                        toLocalDateTime(rs.getTimestamp("sincronizado_em")),
                        rs.getString("origem"),
                        rs.getString("sync_status"),
                        rs.getString("responsible_user_id"),
                        rs.getString("responsible_user_name"),
                        rs.getObject("schema_version", Integer.class),
                        parseJson(rs.getString("entidades_relacionadas_json"), true),
                        parseJson(rs.getString("payload_json"), false)
                ),
                MAX_RDOS,
                MAX_CHILD_ROWS
        );
    }

    private List<StaviaSnapshotResponse.PdorSnapshot> pdors() {
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
                    p.rac_ponderado,
                    p.p10_receita,
                    p.p50_receita,
                    p.p80_receita,
                    p.p95_receita,
                    p.prob_abaixo_contrato,
                    p.prob_abaixo_95_pct,
                    p.prob_abaixo_90_pct,
                    p.score_heuristico,
                    p.confianca
                FROM pdor_snapshot p
                JOIN (
                    SELECT obra_id, MAX(executado_em) AS max_executado_em
                    FROM pdor_snapshot
                    GROUP BY obra_id
                ) latest
                  ON latest.obra_id = p.obra_id
                 AND latest.max_executado_em = p.executado_em
                ORDER BY p.executado_em DESC, p.id
                LIMIT ?
                """,
                (rs, rowNum) -> new StaviaSnapshotResponse.PdorSnapshot(
                        rs.getString("obra_id"),
                        rs.getString("id"),
                        toLocalDate(rs, "data_referencia"),
                        toLocalDateTime(rs.getTimestamp("executado_em")),
                        rs.getString("status_execucao"),
                        rs.getString("calibracao"),
                        rs.getString("nivel_risco"),
                        rs.getBigDecimal("rac_ponderado"),
                        rs.getBigDecimal("p10_receita"),
                        rs.getBigDecimal("p50_receita"),
                        rs.getBigDecimal("p80_receita"),
                        rs.getBigDecimal("p95_receita"),
                        rs.getBigDecimal("prob_abaixo_contrato"),
                        rs.getBigDecimal("prob_abaixo_95_pct"),
                        rs.getBigDecimal("prob_abaixo_90_pct"),
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
                    SELECT MAX(atualizado_em) AS updated_at FROM rdo_attachment
                    UNION ALL
                    SELECT MAX(criado_em) AS updated_at FROM cortex_evento_operacional
                    UNION ALL
                    SELECT MAX(atualizado_em) AS updated_at FROM programacao_operacional
                    UNION ALL
                    SELECT MAX(executado_em) AS updated_at FROM pdor_snapshot
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

    private JsonNode parseJson(String json, boolean arrayFallback) {
        try {
            if (json == null || json.isBlank()) {
                return arrayFallback
                        ? objectMapper.createArrayNode()
                        : objectMapper.createObjectNode();
            }

            return objectMapper.readTree(json);
        } catch (JsonProcessingException exception) {
            return arrayFallback
                    ? objectMapper.createArrayNode()
                    : objectMapper.createObjectNode();
        }
    }

    private Integer toInteger(ResultSet rs, String column)
            throws SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }

    private Boolean toBoolean(ResultSet rs, String column)
            throws SQLException {
        boolean value = rs.getBoolean(column);
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
            String diaSemana,
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
            String fonteCriacao,
            String estadoReceita,
            String fonteArquivo,
            String abaOrigem,
            Integer linhaOrigem,
            LocalDate dataOriginal,
            LocalDateTime dataImportacao,
            String usuarioImportacao,
            LocalDateTime criadoEm,
            LocalDateTime enviadoEm,
            LocalDateTime aprovadoEm,
            Long versaoLinha,
            String observacoes,
            String preenchidoPor,
            String apontadorRdo,
            String encarregadoObra,
            String fiscalizacaoCampo,
            LocalDateTime updatedAt
    ) {
    }
}
