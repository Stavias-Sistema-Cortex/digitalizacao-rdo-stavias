package com.projeto.cortex.obras.trecho;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * O desenho no mapa é o apontamento visto por outro ângulo.
 *
 * <p>Três das quatro fontes do trecho descendem do RDO e somem quando ele é
 * cancelado. A quarta — a geometria desenhada — filtrava obra, categoria e
 * status e mais nada: nenhum vínculo, nenhuma noção de cancelamento. O desenho
 * sobrevivia ao apontamento que representava, e como a rodovia era digitada à
 * mão sem nada que a amarrasse à obra, um trecho de outra rodovia podia ficar
 * na tela indefinidamente.
 *
 * <p>Este teste exercita o ciclo pelo SQL de verdade, porque era exatamente uma
 * consulta que estava errada: o texto parecia certo e só o banco diria.
 */
@Testcontainers(disabledWithoutDocker = true)
class PostgresqlTrechoRdoBindingIT {

    @Container
    private static final PostgreSQLContainer<?> DATABASE =
            new PostgreSQLContainer<>("postgres:18")
                    .withDatabaseName("cortex_trecho_binding_it");

    private static JdbcTemplate jdbc;

    @BeforeAll
    static void migrate() {
        Flyway.configure()
                .dataSource(
                        DATABASE.getJdbcUrl(),
                        DATABASE.getUsername(),
                        DATABASE.getPassword()
                )
                .locations("classpath:db/migration-postgresql")
                .load()
                .migrate();
        jdbc = new JdbcTemplate(new DriverManagerDataSource(
                DATABASE.getJdbcUrl(),
                DATABASE.getUsername(),
                DATABASE.getPassword()
        ));
    }

    private String colaborador() {
        String id = UUID.randomUUID().toString();
        jdbc.update(
                """
                INSERT INTO colaborador (
                    id, banco_origem, tabela_origem, pk_origem, nome,
                    papel_acesso, ativo
                ) VALUES (?, 'trecho-it', 'colaborador', ?, 'Apontador',
                          'BETA', TRUE)
                """,
                id,
                id
        );
        return id;
    }

    private String obra() {
        String id = UUID.randomUUID().toString();
        jdbc.update(
                "INSERT INTO obra (id, codigo_contrato, nome) VALUES (?, ?, 'Obra do trecho')",
                id,
                "TRE-" + id
        );
        return id;
    }

    private String rdo(String obraId) {
        String id = UUID.randomUUID().toString();
        jdbc.update(
                """
                INSERT INTO rdo (
                    id, obra_id, numero_rdo, data_rdo, status
                ) VALUES (?, ?, ?, ?, 'RASCUNHO')
                """,
                id,
                obraId,
                "RDO-" + id.substring(0, 4),
                LocalDate.parse("2026-08-04")
        );
        return id;
    }

    private String geometriaDeTrecho(
            String obraId,
            String rdoId,
            String autorId
    ) {
        String id = UUID.randomUUID().toString();
        jdbc.update(
                """
                INSERT INTO obra_geometria (
                    id, obra_id, categoria, objeto_tipo, objeto_id,
                    tipo_geometria, geometria_json, propriedades_json, fonte,
                    status, valido_desde, criado_por, atualizado_por,
                    criado_em, atualizado_em
                ) VALUES (
                    ?, ?, 'TRECHO', ?, ?, 'LINESTRING',
                    '{"type":"LineString","coordinates":[[-47.9,-15.8],[-47.8,-15.7]]}'::jsonb,
                    '{"nome":"Fresagem","kmInicial":"10","kmFinal":"12"}'::jsonb,
                    'MAPA', 'ATIVA', CURRENT_TIMESTAMP(6), ?, ?,
                    CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6)
                )
                """,
                id,
                obraId,
                rdoId == null ? null : "RDO",
                rdoId,
                autorId,
                autorId
        );
        return id;
    }

    /** A consulta que o trecho usa, exercitada como o serviço a executa. */
    private int segmentosDesenhados(String obraId) {
        Integer total = jdbc.queryForObject(
                """
                SELECT COUNT(*)
                  FROM obra_geometria g
                  JOIN rdo r
                    ON r.id = g.objeto_id
                   AND r.obra_id = g.obra_id
                 WHERE g.obra_id = ?
                   AND g.categoria = 'TRECHO'
                   AND g.status = 'ATIVA'
                   AND g.objeto_tipo = 'RDO'
                   AND r.cancelado_em IS NULL
                """,
                Integer.class,
                obraId
        );
        return total == null ? 0 : total;
    }

    /** O caso relatado: apagar o RDO tem de apagar o desenho junto. */
    @Test
    void cancelarORdoTiraODesenhoDoTrecho() {
        String autor = colaborador();
        String obraId = obra();
        String rdoId = rdo(obraId);
        geometriaDeTrecho(obraId, rdoId, autor);

        assertThat(segmentosDesenhados(obraId)).isEqualTo(1);

        jdbc.update(
                """
                UPDATE rdo SET status = 'CANCELADA',
                               cancelado_em = CURRENT_TIMESTAMP(6)
                WHERE id = ?
                """,
                rdoId
        );

        assertThat(segmentosDesenhados(obraId)).isZero();
    }

    /**
     * Desenho sem apontamento não descreve execução nenhuma. É a linha que
     * sobrevivia para sempre, e a migração fechou as que já existiam.
     */
    @Test
    void desenhoSemVinculoNaoEntraNoTrecho() {
        String autor = colaborador();
        String obraId = obra();
        geometriaDeTrecho(obraId, null, autor);

        assertThat(segmentosDesenhados(obraId)).isZero();
    }

    /**
     * O desenho pertence à obra do seu RDO. Sem esta condição, uma geometria
     * apontando para RDO de outra obra apareceria no trecho errado — que é
     * exatamente a forma da rodovia divergente que apareceu na tela.
     */
    @Test
    void desenhoNaoAtravessaDeUmaObraParaOutra() {
        String autor = colaborador();
        String obraA = obra();
        String obraB = obra();
        String rdoDaOutraObra = rdo(obraB);

        String id = UUID.randomUUID().toString();
        jdbc.update(
                """
                INSERT INTO obra_geometria (
                    id, obra_id, categoria, objeto_tipo, objeto_id,
                    tipo_geometria, geometria_json, propriedades_json, fonte,
                    status, valido_desde, criado_por, atualizado_por,
                    criado_em, atualizado_em
                ) VALUES (
                    ?, ?, 'TRECHO', 'RDO', ?, 'LINESTRING',
                    '{"type":"LineString","coordinates":[[-47.9,-15.8],[-47.8,-15.7]]}'::jsonb,
                    '{"nome":"Fresagem","kmInicial":"10","kmFinal":"12"}'::jsonb,
                    'MAPA', 'ATIVA', CURRENT_TIMESTAMP(6), ?, ?,
                    CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6)
                )
                """,
                id,
                obraA,
                rdoDaOutraObra,
                autor,
                autor
        );

        assertThat(segmentosDesenhados(obraA)).isZero();
    }
}
