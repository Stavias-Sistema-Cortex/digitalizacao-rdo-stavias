package com.projeto.cortex.intelligence.stavia.knowledge.rdo;

import java.sql.Date;
import java.sql.Time;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcRdoKnowledgeReader implements RdoKnowledgeReader {

    private final JdbcTemplate jdbcTemplate;

    public JdbcRdoKnowledgeReader(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public List<RdoKnowledgeRecord> findByWorksiteId(
            String worksiteId
    ) {
        if (worksiteId == null || worksiteId.isBlank()) {
            throw new IllegalArgumentException(
                    "A obra deve ser informada."
            );
        }

        return jdbcTemplate.query(
                """
                SELECT
                    r.id,
                    r.obra_id,
                    COALESCE(
                        NULLIF(o.codigo_cw, ''),
                        NULLIF(o.codigo_contrato, ''),
                        NULLIF(o.codigo_interno, ''),
                        o.id
                    ) AS codigo_obra,
                    r.programacao_id,
                    p.chave_negocio AS programacao_chave,
                    p.data_programacao AS programacao_data,
                    p.servico AS programacao_servico,
                    p.status AS programacao_status,
                    p.fechamento AS programacao_fechamento,
                    p.encarregado AS programacao_encarregado,
                    p.periodo AS programacao_periodo,
                    p.faixa AS programacao_faixa,
                    p.km_inicial AS programacao_km_inicial,
                    p.km_final AS programacao_km_final,
                    p.extensao_m AS programacao_extensao_m,
                    p.area_m2 AS programacao_area_m2,
                    p.volume_m3 AS programacao_volume_m3,
                    p.tonelada_massa AS programacao_tonelada_massa,
                    p.tipo_cap AS programacao_tipo_cap,
                    p.cap AS programacao_cap,
                    r.numero_rdo,
                    r.data_rdo,
                    r.cliente,
                    r.cidade,
                    r.contrato,
                    r.rodovia,
                    r.uf,
                    r.km_inicial_programado,
                    r.km_final_programado,
                    r.km_inicial_interditado,
                    r.km_final_interditado,
                    r.turno,
                    r.hora_inicio,
                    r.hora_fim,
                    r.condicao_manha,
                    r.condicao_tarde,
                    r.condicao_noite,
                    r.pluviometria_mm,
                    r.status,
                    r.observacoes,
                    r.preenchido_por,
                    r.apontador_rdo,
                    r.encarregado_obra,
                    r.fiscalizacao_campo,
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
                JOIN obra o
                  ON o.id = r.obra_id
                LEFT JOIN programacao_operacional p
                  ON p.id = r.programacao_id
                 AND p.cancelado_em IS NULL
                WHERE r.obra_id = ?
                  AND r.cancelado_em IS NULL
                ORDER BY r.data_rdo DESC, r.criado_em DESC, r.id
                """,
                (resultSet, rowNumber) ->
                        new RdoKnowledgeRecord(
                                resultSet.getString("id"),
                                resultSet.getString("obra_id"),
                                resultSet.getString("codigo_obra"),
                                resultSet.getString(
                                        "programacao_id"
                                ),
                                resultSet.getString(
                                        "programacao_chave"
                                ),
                                toLocalDate(
                                        resultSet.getDate(
                                                "programacao_data"
                                        )
                                ),
                                resultSet.getString(
                                        "programacao_servico"
                                ),
                                resultSet.getString(
                                        "programacao_status"
                                ),
                                resultSet.getString(
                                        "programacao_fechamento"
                                ),
                                resultSet.getString(
                                        "programacao_encarregado"
                                ),
                                resultSet.getString(
                                        "programacao_periodo"
                                ),
                                resultSet.getString(
                                        "programacao_faixa"
                                ),
                                resultSet.getString(
                                        "programacao_km_inicial"
                                ),
                                resultSet.getString(
                                        "programacao_km_final"
                                ),
                                resultSet.getBigDecimal(
                                        "programacao_extensao_m"
                                ),
                                resultSet.getBigDecimal(
                                        "programacao_area_m2"
                                ),
                                resultSet.getBigDecimal(
                                        "programacao_volume_m3"
                                ),
                                resultSet.getBigDecimal(
                                        "programacao_tonelada_massa"
                                ),
                                resultSet.getString(
                                        "programacao_tipo_cap"
                                ),
                                resultSet.getBigDecimal(
                                        "programacao_cap"
                                ),
                                resultSet.getString("numero_rdo"),
                                toLocalDate(
                                        resultSet.getDate("data_rdo")
                                ),
                                resultSet.getString("cliente"),
                                resultSet.getString("cidade"),
                                resultSet.getString("contrato"),
                                resultSet.getString("rodovia"),
                                resultSet.getString("uf"),
                                resultSet.getString(
                                        "km_inicial_programado"
                                ),
                                resultSet.getString(
                                        "km_final_programado"
                                ),
                                resultSet.getString(
                                        "km_inicial_interditado"
                                ),
                                resultSet.getString(
                                        "km_final_interditado"
                                ),
                                resultSet.getString("turno"),
                                toLocalTime(
                                        resultSet.getTime("hora_inicio")
                                ),
                                toLocalTime(
                                        resultSet.getTime("hora_fim")
                                ),
                                resultSet.getString("condicao_manha"),
                                resultSet.getString("condicao_tarde"),
                                resultSet.getString("condicao_noite"),
                                resultSet.getBigDecimal(
                                        "pluviometria_mm"
                                ),
                                resultSet.getString("status"),
                                resultSet.getString("observacoes"),
                                resultSet.getString("preenchido_por"),
                                resultSet.getString("apontador_rdo"),
                                resultSet.getString("encarregado_obra"),
                                resultSet.getString(
                                        "fiscalizacao_campo"
                                ),
                                resultSet.getInt("total_mao_obra"),
                                resultSet.getInt("total_equipamentos"),
                                resultSet.getInt("total_materiais"),
                                resultSet.getInt(
                                        "total_controles_geometricos"
                                ),
                                toLocalDateTime(
                                        resultSet.getTimestamp(
                                                "criado_em"
                                        )
                                ),
                                toLocalDateTime(
                                        resultSet.getTimestamp(
                                                "atualizado_em"
                                        )
                                )
                        ),
                worksiteId.trim()
        );
    }

    private LocalDate toLocalDate(Date date) {
        return date == null ? null : date.toLocalDate();
    }

    private LocalDateTime toLocalDateTime(Timestamp timestamp) {
        return timestamp == null
                ? null
                : timestamp.toLocalDateTime();
    }

    private LocalTime toLocalTime(Time time) {
        return time == null ? null : time.toLocalTime();
    }
}
