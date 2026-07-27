package com.projeto.cortex.intelligence.stavia.knowledge.occurrence;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

import static com.projeto.cortex.intelligence.stavia.knowledge.JdbcRecordMappers.toLocalDate;
import static com.projeto.cortex.intelligence.stavia.knowledge.JdbcRecordMappers.toLocalDateTime;

@Component
public class JdbcOccurrenceReader
        implements OccurrenceReader {

    static final String FIND_BY_WORKSITE_SQL = """
            WITH rdos AS (
                SELECT
                    id,
                    obra_id,
                    numero_rdo,
                    data_rdo,
                    status,
                    observacoes,
                    atualizado_em
                FROM rdo
                WHERE obra_id = ?
                  AND cancelado_em IS NULL
            )
            SELECT
                occurrence_id,
                rdo_id,
                obra_id,
                numero_rdo,
                data_rdo,
                status,
                observations,
                atualizado_em
            FROM (
                SELECT
                    CONCAT(r.id, ':rdo') AS occurrence_id,
                    r.id AS rdo_id,
                    r.obra_id,
                    r.numero_rdo,
                    r.data_rdo,
                    r.status,
                    r.observacoes AS observations,
                    r.atualizado_em
                FROM rdos r

                UNION ALL

                SELECT
                    CONCAT(r.id, ':material:', material.id) AS occurrence_id,
                    r.id AS rdo_id,
                    r.obra_id,
                    r.numero_rdo,
                    r.data_rdo,
                    r.status,
                    CONCAT(
                        'Material ',
                        COALESCE(NULLIF(material.material_nome, ''), 'sem nome'),
                        ': ',
                        material.observacoes
                    ) AS observations,
                    COALESCE(material.atualizado_em, r.atualizado_em) AS atualizado_em
                FROM rdo_material material
                JOIN rdos r ON r.id = material.rdo_id

                UNION ALL

                SELECT
                    CONCAT(r.id, ':mao-obra:', mao_obra.id) AS occurrence_id,
                    r.id AS rdo_id,
                    r.obra_id,
                    r.numero_rdo,
                    r.data_rdo,
                    r.status,
                    CONCAT(
                        'Mão de obra ',
                        COALESCE(
                            NULLIF(mao_obra.nome_colaborador, ''),
                            NULLIF(mao_obra.cargo, ''),
                            'sem identificação'
                        ),
                        ': ',
                        mao_obra.observacoes
                    ) AS observations,
                    COALESCE(mao_obra.atualizado_em, r.atualizado_em) AS atualizado_em
                FROM rdo_mao_obra mao_obra
                JOIN rdos r ON r.id = mao_obra.rdo_id

                UNION ALL

                SELECT
                    CONCAT(r.id, ':equipamento:', equipamento.id) AS occurrence_id,
                    r.id AS rdo_id,
                    r.obra_id,
                    r.numero_rdo,
                    r.data_rdo,
                    r.status,
                    CONCAT(
                        'Equipamento ',
                        COALESCE(
                            NULLIF(equipamento.prefixo, ''),
                            NULLIF(equipamento.descricao, ''),
                            NULLIF(equipamento.tipo_equipamento, ''),
                            'sem identificação'
                        ),
                        ': ',
                        equipamento.observacoes
                    ) AS observations,
                    COALESCE(equipamento.atualizado_em, r.atualizado_em) AS atualizado_em
                FROM rdo_equipamento equipamento
                JOIN rdos r ON r.id = equipamento.rdo_id

                UNION ALL

                SELECT
                    CONCAT(r.id, ':controle:', controle.id, ':observacoes') AS occurrence_id,
                    r.id AS rdo_id,
                    r.obra_id,
                    r.numero_rdo,
                    r.data_rdo,
                    r.status,
                    CONCAT(
                        'Controle geométrico ',
                        COALESCE(
                            NULLIF(controle.numero, ''),
                            NULLIF(controle.subtrecho, ''),
                            'sem identificação'
                        ),
                        ': ',
                        controle.observacoes
                    ) AS observations,
                    COALESCE(controle.atualizado_em, r.atualizado_em) AS atualizado_em
                FROM rdo_controle_geometrico controle
                JOIN rdos r ON r.id = controle.rdo_id

                UNION ALL

                SELECT
                    CONCAT(r.id, ':controle:', controle.id, ':atividade') AS occurrence_id,
                    r.id AS rdo_id,
                    r.obra_id,
                    r.numero_rdo,
                    r.data_rdo,
                    r.status,
                    CONCAT(
                        'Atividade do controle geométrico ',
                        COALESCE(
                            NULLIF(controle.numero, ''),
                            NULLIF(controle.subtrecho, ''),
                            'sem identificação'
                        ),
                        ': ',
                        controle.atividade_observacoes
                    ) AS observations,
                    COALESCE(controle.atualizado_em, r.atualizado_em) AS atualizado_em
                FROM rdo_controle_geometrico controle
                JOIN rdos r ON r.id = controle.rdo_id

                UNION ALL

                SELECT
                    CONCAT(r.id, ':servico:', servico.id) AS occurrence_id,
                    r.id AS rdo_id,
                    r.obra_id,
                    r.numero_rdo,
                    r.data_rdo,
                    r.status,
                    CONCAT(
                        'Serviço ',
                        COALESCE(NULLIF(servico.servico_nome, ''), 'sem nome'),
                        ': ',
                        servico.observacoes
                    ) AS observations,
                    COALESCE(servico.atualizado_em, r.atualizado_em) AS atualizado_em
                FROM execucao_servico_rdo servico
                JOIN rdos r ON r.id = servico.rdo_id
                WHERE servico.cancelada = 0

                UNION ALL

                SELECT
                    CONCAT(r.id, ':alocacao:', alocacao.id) AS occurrence_id,
                    r.id AS rdo_id,
                    r.obra_id,
                    r.numero_rdo,
                    r.data_rdo,
                    r.status,
                    CONCAT(
                        'Alocação ',
                        COALESCE(
                            NULLIF(alocacao.equipe, ''),
                            NULLIF(alocacao.funcao, ''),
                            NULLIF(alocacao.servico_nome, ''),
                            'sem identificação'
                        ),
                        ': ',
                        alocacao.observacoes
                    ) AS observations,
                    COALESCE(alocacao.atualizado_em, r.atualizado_em) AS atualizado_em
                FROM alocacao_colaborador alocacao
                JOIN rdos r ON r.id = alocacao.rdo_id

                UNION ALL

                SELECT
                    CONCAT(r.id, ':frequencia:', ocorrencia.id) AS occurrence_id,
                    r.id AS rdo_id,
                    r.obra_id,
                    r.numero_rdo,
                    r.data_rdo,
                    r.status,
                    CONCAT(
                        'Ocorrência de frequência ',
                        COALESCE(NULLIF(ocorrencia.tipo, ''), 'sem tipo'),
                        ': ',
                        ocorrencia.justificativa
                    ) AS observations,
                    COALESCE(ocorrencia.criado_em, r.atualizado_em) AS atualizado_em
                FROM ocorrencia_frequencia ocorrencia
                JOIN rdos r ON r.id = ocorrencia.rdo_id
            ) occurrence_notes
            WHERE observations IS NOT NULL
              AND TRIM(observations) <> ''
            ORDER BY
                data_rdo DESC,
                numero_rdo,
                occurrence_id
            """;

    private final JdbcTemplate jdbcTemplate;

    public JdbcOccurrenceReader(
            JdbcTemplate jdbcTemplate
    ) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public List<OccurrenceRecord> findByWorksiteId(
            String worksiteId
    ) {
        if (
                worksiteId == null
                || worksiteId.isBlank()
        ) {
            return List.of();
        }

        return jdbcTemplate.query(
                FIND_BY_WORKSITE_SQL,
                (resultSet, rowNumber) ->
                        new OccurrenceRecord(
                                resultSet.getString("rdo_id"),
                                resultSet.getString("obra_id"),
                                resultSet.getString("numero_rdo"),
                                toLocalDate(
                                        resultSet.getDate(
                                                "data_rdo"
                                        )
                                ),
                                resultSet.getString("status"),
                                resultSet.getString("observations"),
                                toLocalDateTime(
                                        resultSet.getTimestamp(
                                                "atualizado_em"
                                        )
                                ),
                                resultSet.getString("occurrence_id")
                        ),
                worksiteId.trim()
        );
    }
}
