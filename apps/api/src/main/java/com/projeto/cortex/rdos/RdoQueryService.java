package com.projeto.cortex.rdos;

import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.sql.Time;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

@Service
public class RdoQueryService {

    private final JdbcTemplate jdbcTemplate;

    public RdoQueryService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public RdoResponse buscarPorId(String id) {
        RdoCabecalho cabecalho = buscarCabecalho(id);

        return new RdoResponse(
                cabecalho.id(),
                cabecalho.obraId(),
                cabecalho.programacaoId(),
                cabecalho.numeroRdo(),
                cabecalho.dataRdo(),
                cabecalho.diaSemana(),
                cabecalho.cliente(),
                cabecalho.contrato(),
                cabecalho.rodovia(),
                cabecalho.cidade(),
                cabecalho.uf(),
                cabecalho.turno(),
                cabecalho.horaInicio(),
                cabecalho.horaFim(),
                cabecalho.status(),
                cabecalho.observacoes(),
                listarMaoObra(id),
                listarEquipamentos(id),
                listarMateriais(id),
                listarControlesGeometricos(id)
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
                        dia_semana,
                        cliente,
                        contrato,
                        rodovia,
                        cidade,
                        uf,
                        turno,
                        hora_inicio,
                        hora_fim,
                        status,
                        observacoes
                    FROM rdo
                    WHERE id = ?
                    """,
                    (rs, rowNum) -> new RdoCabecalho(
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
                            toLocalTime(rs.getTime("hora_inicio")),
                            toLocalTime(rs.getTime("hora_fim")),
                            rs.getString("status"),
                            rs.getString("observacoes")
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
                    quantidade
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
                        rs.getBigDecimal("quantidade")
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
                    quantidade
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
                        rs.getBigDecimal("quantidade")
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
                    quantidade_sobra
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
                        rs.getBigDecimal("quantidade_sobra")
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
                    comprimento_m,
                    largura_m,
                    espessura_media_cm,
                    area_m2,
                    volume_m3,
                    densidade,
                    massa_tonelada
                FROM rdo_controle_geometrico
                WHERE rdo_id = ?
                ORDER BY subtrecho, id
                """,
                (rs, rowNum) -> new RdoResponse.ControleGeometricoItem(
                        rs.getString("id"),
                        rs.getString("subtrecho"),
                        rs.getBigDecimal("comprimento_m"),
                        rs.getBigDecimal("largura_m"),
                        rs.getBigDecimal("espessura_media_cm"),
                        rs.getBigDecimal("area_m2"),
                        rs.getBigDecimal("volume_m3"),
                        rs.getBigDecimal("densidade"),
                        rs.getBigDecimal("massa_tonelada")
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
            String diaSemana,
            String cliente,
            String contrato,
            String rodovia,
            String cidade,
            String uf,
            String turno,
            LocalTime horaInicio,
            LocalTime horaFim,
            String status,
            String observacoes
    ) {
    }
}
