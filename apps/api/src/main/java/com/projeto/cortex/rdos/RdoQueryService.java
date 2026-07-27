package com.projeto.cortex.rdos;

import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.sql.Time;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

@Service
public class RdoQueryService {

    private final JdbcTemplate jdbcTemplate;
    private final RdoOperationalDetailService operationalDetailService;
    private final RdoAttachmentService attachmentService;

    public RdoQueryService(
            JdbcTemplate jdbcTemplate,
            RdoOperationalDetailService operationalDetailService,
            RdoAttachmentService attachmentService
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.operationalDetailService = operationalDetailService;
        this.attachmentService = attachmentService;
    }

    public RdoResponse buscarPorId(String id) {
        RdoCabecalho cabecalho = buscarCabecalho(id);

        return new RdoResponse(
                cabecalho.id(),
                cabecalho.obraId(),
                cabecalho.programacaoId(),
                cabecalho.numeroRdo(),
                cabecalho.dataRdo(),
                cabecalho.previousRdoId(),
                cabecalho.creationContextVersion(),
                cabecalho.clientMutationId(),
                cabecalho.apontadorColaboradorId(),
                cabecalho.diaSemana(),
                cabecalho.cliente(),
                cabecalho.contrato(),
                cabecalho.rodovia(),
                cabecalho.cidade(),
                cabecalho.uf(),
                cabecalho.kmInicialProgramado(),
                cabecalho.kmFinalProgramado(),
                cabecalho.kmInicialInterditado(),
                cabecalho.kmFinalInterditado(),
                cabecalho.turno(),
                cabecalho.horaInicio(),
                cabecalho.horaFim(),
                cabecalho.condicaoManha(),
                cabecalho.condicaoTarde(),
                cabecalho.condicaoNoite(),
                cabecalho.pluviometriaMm(),
                cabecalho.status(),
                cabecalho.observacoes(),
                cabecalho.preenchidoPor(),
                cabecalho.apontadorRdo(),
                cabecalho.encarregadoObra(),
                cabecalho.fiscalizacaoCampo(),
                listarMaoObra(id),
                listarEquipamentos(id),
                listarMateriais(id),
                listarControlesGeometricos(id),
                operationalDetailService.listarServicos(id),
                operationalDetailService.listarAlocacoes(id),
                attachmentService.listar(id)
        );
    }

    public List<RdoResumoResponse> listarPorObra(String obraId, LocalDate data) {
        if (obraId == null || obraId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "obraId é obrigatório.");
        }

        if (data == null) {
            return jdbcTemplate.query(
                    sqlListagem("""
                    WHERE r.obra_id = ?
                    """),
                    (rs, rowNum) -> mapearResumo(rs),
                    obraId
            );
        }

        return jdbcTemplate.query(
                sqlListagem("""
                WHERE r.obra_id = ?
                  AND r.data_rdo = ?
                """),
                (rs, rowNum) -> mapearResumo(rs),
                obraId,
                data
        );
    }

    private String sqlListagem(String whereClause) {
        return """
                SELECT
                    r.id,
                    r.obra_id,
                    r.programacao_id,
                    r.numero_rdo,
                    r.data_rdo,
                    r.dia_semana,
                    r.cliente,
                    r.contrato,
                    r.rodovia,
                    r.cidade,
                    r.uf,
                    r.turno,
                    r.status,
                    r.criado_em,
                    r.atualizado_em,
                    (
                        SELECT COUNT(*)
                        FROM rdo_mao_obra mo
                        WHERE mo.rdo_id = r.id
                    ) AS total_mao_obra,
                    (
                        SELECT COUNT(*)
                        FROM rdo_equipamento eq
                        WHERE eq.rdo_id = r.id
                    ) AS total_equipamentos,
                    (
                        SELECT COUNT(*)
                        FROM rdo_material mat
                        WHERE mat.rdo_id = r.id
                    ) AS total_materiais,
                    (
                        SELECT COUNT(*)
                        FROM rdo_controle_geometrico cg
                        WHERE cg.rdo_id = r.id
                    ) AS total_controles_geometricos
                FROM rdo r
                """ + whereClause + """
                ORDER BY r.data_rdo DESC, r.criado_em DESC, r.id
                """;
    }

    private RdoResumoResponse mapearResumo(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new RdoResumoResponse(
                rs.getString("id"),
                rs.getString("obra_id"),
                rs.getString("programacao_id"),
                rs.getString("numero_rdo"),
                rs.getDate("data_rdo").toLocalDate(),
                rs.getString("dia_semana"),
                rs.getString("cliente"),
                rs.getString("contrato"),
                rs.getString("rodovia"),
                rs.getString("cidade"),
                rs.getString("uf"),
                rs.getString("turno"),
                rs.getString("status"),
                rs.getInt("total_mao_obra"),
                rs.getInt("total_equipamentos"),
                rs.getInt("total_materiais"),
                rs.getInt("total_controles_geometricos"),
                toLocalDateTime(rs.getTimestamp("criado_em")),
                toLocalDateTime(rs.getTimestamp("atualizado_em"))
        );
    }

    private RdoCabecalho buscarCabecalho(String id) {
        try {
            return jdbcTemplate.queryForObject(
                    """
                    SELECT
                        id,
                        obra_id,
                        programacao_id,
                        numero_rdo,
                        data_rdo,
                        previous_rdo_id,
                        creation_context_version,
                        client_mutation_id,
                        apontador_colaborador_id,
                        dia_semana,
                        cliente,
                        contrato,
                        rodovia,
                        cidade,
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
                        fiscalizacao_campo
                    FROM rdo
                    WHERE id = ?
                    """,
                    (rs, rowNum) -> new RdoCabecalho(
                            rs.getString("id"),
                            rs.getString("obra_id"),
                            rs.getString("programacao_id"),
                            rs.getString("numero_rdo"),
                            rs.getDate("data_rdo").toLocalDate(),
                            rs.getString("previous_rdo_id"),
                            rs.getObject("creation_context_version", Long.class),
                            rs.getString("client_mutation_id"),
                            rs.getString("apontador_colaborador_id"),
                            rs.getString("dia_semana"),
                            rs.getString("cliente"),
                            rs.getString("contrato"),
                            rs.getString("rodovia"),
                            rs.getString("cidade"),
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
                            rs.getString("fiscalizacao_campo")
                    ),
                    id
            );
        } catch (DataAccessException exception) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "RDO não encontrado: " + id);
        }
    }

    private List<RdoResponse.MaoObraItem> listarMaoObra(String rdoId) {
        return jdbcTemplate.query(
                """
                SELECT
                    id,
                    colaborador_id,
                    nome_colaborador,
                    cargo,
                    tipo_vinculo,
                    quantidade,
                    hora_inicio,
                    hora_fim,
                    observacoes,
                    origem_item_id
                FROM rdo_mao_obra
                WHERE rdo_id = ?
                ORDER BY cargo, nome_colaborador, id
                """,
                (rs, rowNum) -> new RdoResponse.MaoObraItem(
                        rs.getString("id"),
                        rs.getString("colaborador_id"),
                        rs.getString("nome_colaborador"),
                        rs.getString("cargo"),
                        rs.getString("tipo_vinculo"),
                        rs.getBigDecimal("quantidade"),
                        rs.getTime("hora_inicio") == null
                                ? null
                                : rs.getTime("hora_inicio").toLocalTime(),
                        rs.getTime("hora_fim") == null
                                ? null
                                : rs.getTime("hora_fim").toLocalTime(),
                        rs.getString("observacoes"),
                        rs.getString("origem_item_id")
                ),
                rdoId
        );
    }

    private List<RdoResponse.EquipamentoItem> listarEquipamentos(String rdoId) {
        return jdbcTemplate.query(
                """
                SELECT
                    id,
                    asset_id,
                    prefixo,
                    descricao,
                    tipo_equipamento,
                    tipo_vinculo,
                    quantidade,
                    hora_inicio,
                    hora_fim,
                    observacoes
                FROM rdo_equipamento
                WHERE rdo_id = ?
                ORDER BY prefixo, descricao, id
                """,
                (rs, rowNum) -> new RdoResponse.EquipamentoItem(
                        rs.getString("id"),
                        rs.getString("asset_id"),
                        rs.getString("prefixo"),
                        rs.getString("descricao"),
                        rs.getString("tipo_equipamento"),
                        rs.getString("tipo_vinculo"),
                        rs.getBigDecimal("quantidade"),
                        rs.getTime("hora_inicio") == null
                                ? null
                                : rs.getTime("hora_inicio").toLocalTime(),
                        rs.getTime("hora_fim") == null
                                ? null
                                : rs.getTime("hora_fim").toLocalTime(),
                        rs.getString("observacoes")
                ),
                rdoId
        );
    }

    private List<RdoResponse.MaterialItem> listarMateriais(String rdoId) {
        return jdbcTemplate.query(
                """
                SELECT
                    id,
                    material_nome,
                    unidade,
                    quantidade_prevista,
                    quantidade_usinada,
                    quantidade_aplicada,
                    quantidade_sobra,
                    nota_fiscal,
                    fornecedor,
                    observacoes
                FROM rdo_material
                WHERE rdo_id = ?
                ORDER BY material_nome, id
                """,
                (rs, rowNum) -> new RdoResponse.MaterialItem(
                        rs.getString("id"),
                        rs.getString("material_nome"),
                        rs.getString("unidade"),
                        rs.getBigDecimal("quantidade_prevista"),
                        rs.getBigDecimal("quantidade_usinada"),
                        rs.getBigDecimal("quantidade_aplicada"),
                        rs.getBigDecimal("quantidade_sobra"),
                        rs.getString("nota_fiscal"),
                        rs.getString("fornecedor"),
                        rs.getString("observacoes")
                ),
                rdoId
        );
    }

    private List<RdoResponse.ControleGeometricoItem> listarControlesGeometricos(String rdoId) {
        return jdbcTemplate.query(
                """
                SELECT
                    id,
                    subtrecho,
                    numero,
                    estaca_inicial,
                    estaca_final,
                    km_inicial,
                    km_final,
                    pista,
                    faixa,
                    ordem_servico,
                    atividade_observacoes,
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
                    observacoes
                FROM rdo_controle_geometrico
                WHERE rdo_id = ?
                ORDER BY subtrecho, id
                """,
                (rs, rowNum) -> new RdoResponse.ControleGeometricoItem(
                        rs.getString("id"),
                        rs.getString("subtrecho"),
                        rs.getString("numero"),
                        rs.getString("estaca_inicial"),
                        rs.getString("estaca_final"),
                        rs.getString("km_inicial"),
                        rs.getString("km_final"),
                        rs.getString("pista"),
                        rs.getString("faixa"),
                        rs.getString("ordem_servico"),
                        rs.getString("atividade_observacoes"),
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
                        rs.getString("observacoes")
                ),
                rdoId
        );
    }

    private LocalTime toLocalTime(Time time) {
        if (time == null) {
            return null;
        }

        return time.toLocalTime();
    }

    private LocalDateTime toLocalDateTime(java.sql.Timestamp timestamp) {
        if (timestamp == null) {
            return null;
        }

        return timestamp.toLocalDateTime();
    }

    private record RdoCabecalho(
            String id,
            String obraId,
            String programacaoId,
            String numeroRdo,
            LocalDate dataRdo,
            String previousRdoId,
            Long creationContextVersion,
            String clientMutationId,
            String apontadorColaboradorId,
            String diaSemana,
            String cliente,
            String contrato,
            String rodovia,
            String cidade,
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
            String fiscalizacaoCampo
    ) {
    }
}
