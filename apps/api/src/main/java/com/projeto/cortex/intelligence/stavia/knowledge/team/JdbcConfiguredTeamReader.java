package com.projeto.cortex.intelligence.stavia.knowledge.team;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

import static com.projeto.cortex.intelligence.stavia.knowledge.JdbcRecordMappers.toLocalDateTime;

@Component
public class JdbcConfiguredTeamReader implements ConfiguredTeamReader {

    private final JdbcTemplate jdbcTemplate;

    public JdbcConfiguredTeamReader(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public List<ConfiguredTeamRecord> findCurrentByWorksiteId(String worksiteId) {
        return jdbcTemplate.query(
                """
                SELECT
                    equipe.id AS equipe_id,
                    equipe.nome AS equipe_nome,
                    participacao.obra_id,
                    membro.id AS membro_id,
                    membro.colaborador_id,
                    colaborador.nome AS colaborador_nome,
                    membro.funcao_operacional_id,
                    funcao.nome AS funcao_nome,
                    COALESCE(membro.responsavel, 0) AS responsavel,
                    membro.atribuido_por,
                    atribuidor.nome AS atribuidor_nome,
                    membro.inicio_em AS atribuido_em,
                    GREATEST(equipe.atualizado_em, participacao.atualizado_em,
                        COALESCE(membro.atualizado_em, equipe.atualizado_em)) AS atualizado_em
                FROM equipe
                JOIN equipe_obra participacao
                  ON participacao.equipe_id = equipe.id
                 AND participacao.obra_id = ?
                 AND participacao.status = 'ATIVO'
                 AND participacao.fim_em IS NULL
                LEFT JOIN equipe_membro membro
                  ON membro.equipe_id = equipe.id
                 AND membro.status = 'ATIVO'
                 AND membro.fim_em IS NULL
                LEFT JOIN colaborador ON colaborador.id = membro.colaborador_id
                LEFT JOIN funcao_operacional funcao
                  ON funcao.id = membro.funcao_operacional_id
                LEFT JOIN colaborador atribuidor
                  ON atribuidor.id = membro.atribuido_por
                WHERE equipe.status = 'ATIVA'
                  AND equipe.fim_validade_em IS NULL
                ORDER BY equipe.nome, membro.responsavel DESC,
                         colaborador.nome, membro.id
                LIMIT 200
                """,
                (rs, rowNum) -> new ConfiguredTeamRecord(
                        rs.getString("equipe_id"),
                        rs.getString("equipe_nome"),
                        rs.getString("obra_id"),
                        rs.getString("membro_id"),
                        rs.getString("colaborador_id"),
                        rs.getString("colaborador_nome"),
                        rs.getString("funcao_operacional_id"),
                        rs.getString("funcao_nome"),
                        rs.getBoolean("responsavel"),
                        rs.getString("atribuido_por"),
                        rs.getString("atribuidor_nome"),
                        toLocalDateTime(rs.getTimestamp("atribuido_em")),
                        toLocalDateTime(rs.getTimestamp("atualizado_em"))
                ),
                worksiteId
        );
    }
}
