package com.projeto.cortex.intelligence.stavia.knowledge.geospatial;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

import static com.projeto.cortex.intelligence.stavia.knowledge.JdbcRecordMappers.toLocalDateTime;

@Component
public class JdbcGeospatialKnowledgeReader implements GeospatialKnowledgeReader {

    private final JdbcTemplate jdbcTemplate;

    public JdbcGeospatialKnowledgeReader(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public List<GeospatialKnowledgeRecord> findCurrentByWorksiteId(String worksiteId) {
        return jdbcTemplate.query(
                """
                SELECT
                    id, obra_id, categoria, tipo_geometria, objeto_tipo,
                    objeto_id, propriedades_json, fonte, valido_desde, atualizado_em
                FROM obra_geometria
                WHERE obra_id = ?
                  AND status = 'ATIVA'
                  AND valido_ate IS NULL
                ORDER BY categoria, valido_desde DESC, id
                LIMIT 100
                """,
                (rs, rowNum) -> new GeospatialKnowledgeRecord(
                        rs.getString("id"),
                        rs.getString("obra_id"),
                        rs.getString("categoria"),
                        rs.getString("tipo_geometria"),
                        rs.getString("objeto_tipo"),
                        rs.getString("objeto_id"),
                        rs.getString("propriedades_json"),
                        rs.getString("fonte"),
                        toLocalDateTime(rs.getTimestamp("valido_desde")),
                        toLocalDateTime(rs.getTimestamp("atualizado_em"))
                ),
                worksiteId
        );
    }
}
