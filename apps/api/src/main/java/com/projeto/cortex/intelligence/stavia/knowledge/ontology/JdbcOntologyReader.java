package com.projeto.cortex.intelligence.stavia.knowledge.ontology;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.List;

@Repository
public class JdbcOntologyReader
        implements OntologyReader {

    private final JdbcTemplate jdbcTemplate;

    public JdbcOntologyReader(
            JdbcTemplate jdbcTemplate
    ) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public List<OntologyRelation> findByWorksiteGraph(
            String worksiteId,
            int maximumDepth,
            int maximumRelations
    ) {
        if (worksiteId == null || worksiteId.isBlank()) {
            throw new IllegalArgumentException(
                    "A obra deve ser informada."
            );
        }

        if (maximumDepth < 0 || maximumDepth > 10) {
            throw new IllegalArgumentException(
                    "A profundidade deve estar entre 0 e 10."
            );
        }

        if (
                maximumRelations < 1
                || maximumRelations > 1000
        ) {
            throw new IllegalArgumentException(
                    "O limite de relações deve estar entre 1 e 1000."
            );
        }

        return jdbcTemplate.query(
                """
                WITH RECURSIVE related_nodes AS (
                    SELECT
                        'OBRA' AS entity_type,
                        ? AS entity_id,
                        0 AS depth,
                        CAST(
                            CONCAT('|OBRA:', ?, '|')
                            AS CHAR(4000)
                        ) AS traversal_path

                    UNION ALL

                    SELECT
                        CASE
                            WHEN relation.origem_tipo =
                                 node.entity_type
                             AND relation.origem_id =
                                 node.entity_id
                                THEN relation.destino_tipo
                            ELSE relation.origem_tipo
                        END AS entity_type,

                        CASE
                            WHEN relation.origem_tipo =
                                 node.entity_type
                             AND relation.origem_id =
                                 node.entity_id
                                THEN relation.destino_id
                            ELSE relation.origem_id
                        END AS entity_id,

                        node.depth + 1,

                        CONCAT(
                            node.traversal_path,
                            CASE
                                WHEN relation.origem_tipo =
                                     node.entity_type
                                 AND relation.origem_id =
                                     node.entity_id
                                    THEN relation.destino_tipo
                                ELSE relation.origem_tipo
                            END,
                            ':',
                            CASE
                                WHEN relation.origem_tipo =
                                     node.entity_type
                                 AND relation.origem_id =
                                     node.entity_id
                                    THEN relation.destino_id
                                ELSE relation.origem_id
                            END,
                            '|'
                        )
                    FROM related_nodes node
                    JOIN cortex_relacao relation
                      ON relation.ativa = 1
                     AND (
                            (
                                relation.origem_tipo =
                                    node.entity_type
                                AND relation.origem_id =
                                    node.entity_id
                            )
                            OR
                            (
                                relation.destino_tipo =
                                    node.entity_type
                                AND relation.destino_id =
                                    node.entity_id
                            )
                         )
                    WHERE node.depth < ?
                      AND LOCATE(
                            CONCAT(
                                '|',
                                CASE
                                    WHEN relation.origem_tipo =
                                         node.entity_type
                                     AND relation.origem_id =
                                         node.entity_id
                                        THEN relation.destino_tipo
                                    ELSE relation.origem_tipo
                                END,
                                ':',
                                CASE
                                    WHEN relation.origem_tipo =
                                         node.entity_type
                                     AND relation.origem_id =
                                         node.entity_id
                                        THEN relation.destino_id
                                    ELSE relation.origem_id
                                END,
                                '|'
                            ),
                            node.traversal_path
                      ) = 0
                ),
                unique_nodes AS (
                    SELECT DISTINCT
                        entity_type,
                        entity_id
                    FROM related_nodes
                )
                SELECT DISTINCT
                    relation.id AS relation_id,

                    relation.origem_tipo,
                    relation.origem_id,
                    origin.codigo_externo
                        AS origin_external_code,
                    origin.nome AS origin_name,
                    origin.status AS origin_status,

                    relation.tipo_relacao,

                    relation.destino_tipo,
                    relation.destino_id,
                    destination.codigo_externo
                        AS destination_external_code,
                    destination.nome AS destination_name,
                    destination.status AS destination_status,

                    relation.fonte,
                    relation.observacoes,
                    relation.atualizado_em
                FROM cortex_relacao relation
                JOIN unique_nodes origin_node
                  ON origin_node.entity_type =
                        relation.origem_tipo
                 AND origin_node.entity_id =
                        relation.origem_id
                JOIN unique_nodes destination_node
                  ON destination_node.entity_type =
                        relation.destino_tipo
                 AND destination_node.entity_id =
                        relation.destino_id
                JOIN cortex_objeto origin
                  ON origin.tipo_entidade =
                        relation.origem_tipo
                 AND origin.entidade_id =
                        relation.origem_id
                JOIN cortex_objeto destination
                  ON destination.tipo_entidade =
                        relation.destino_tipo
                 AND destination.entidade_id =
                        relation.destino_id
                WHERE relation.ativa = 1
                ORDER BY
                    relation.atualizado_em DESC,
                    relation.id
                LIMIT ?
                """,
                (resultSet, rowNumber) -> {
                    Timestamp updatedAt =
                            resultSet.getTimestamp(
                                    "atualizado_em"
                            );

                    return new OntologyRelation(
                            resultSet.getString(
                                    "relation_id"
                            ),
                            resultSet.getString(
                                    "origem_tipo"
                            ),
                            resultSet.getString(
                                    "origem_id"
                            ),
                            resultSet.getString(
                                    "origin_external_code"
                            ),
                            resultSet.getString(
                                    "origin_name"
                            ),
                            resultSet.getString(
                                    "origin_status"
                            ),
                            resultSet.getString(
                                    "tipo_relacao"
                            ),
                            resultSet.getString(
                                    "destino_tipo"
                            ),
                            resultSet.getString(
                                    "destino_id"
                            ),
                            resultSet.getString(
                                    "destination_external_code"
                            ),
                            resultSet.getString(
                                    "destination_name"
                            ),
                            resultSet.getString(
                                    "destination_status"
                            ),
                            resultSet.getString(
                                    "fonte"
                            ),
                            resultSet.getString(
                                    "observacoes"
                            ),
                            updatedAt.toInstant()
                    );
                },
                worksiteId.trim(),
                worksiteId.trim(),
                maximumDepth,
                maximumRelations
        );
    }
}
