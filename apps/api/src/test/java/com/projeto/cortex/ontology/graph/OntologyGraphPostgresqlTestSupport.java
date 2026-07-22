package com.projeto.cortex.ontology.graph;

import org.springframework.jdbc.core.JdbcTemplate;

final class OntologyGraphPostgresqlTestSupport {

    private OntologyGraphPostgresqlTestSupport() {
    }

    static void insertCanonicalSource(
            JdbcTemplate jdbc,
            long commitSequence,
            String commitId
    ) {
        jdbc.update(
                """
                INSERT INTO cortex_evento_operacional (
                    id, commit_seq, tipo_entidade, entidade_id, tipo_evento,
                    entidades_relacionadas_json, payload_json
                ) VALUES (?, ?, 'RDO', ?, 'GRAPH_TEST_EVENT', '[]'::jsonb, '{}'::jsonb)
                ON CONFLICT (commit_seq) DO NOTHING
                """,
                commitId,
                commitSequence,
                "graph-test-" + commitSequence
        );
    }
}
