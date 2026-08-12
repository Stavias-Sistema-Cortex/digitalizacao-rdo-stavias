package com.projeto.cortex.obras.mapa;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
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
 * O RDO é a hierarquia: quando ele declara quilômetro, o traçado manual cede.
 *
 * <p>O desenho feito à mão vencia a linha derivada, e um RDO corrigido pelo
 * quilômetro continuava mostrando o traçado antigo. A decisão do dono do
 * sistema inverteu isso por inteiro — e a gravação do ceder é SQL sobre
 * {@code obra_geometria}, verificável só contra um PostgreSQL de verdade.
 */
@Testcontainers(disabledWithoutDocker = true)
class PostgresqlDesenhoCedeAoRdoIT {

    @Container
    private static final PostgreSQLContainer<?> DATABASE =
            new PostgreSQLContainer<>("postgres:18")
                    .withDatabaseName("cortex_desenho_cede_it");

    private static JdbcTemplate jdbc;
    private static DesenhoDoTrechoNoMapa desenho;
    private static String colaboradorId;

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
        desenho = new DesenhoDoTrechoNoMapa(jdbc);
        colaboradorId = id();
        jdbc.update(
                """
                INSERT INTO colaborador (
                    id, banco_origem, tabela_origem, pk_origem, nome
                ) VALUES (?, 'teste', 'colaborador', ?, 'Apontador')
                """,
                colaboradorId,
                colaboradorId
        );
    }

    @Test
    void oTracadoManualDoRdoCedeEODeOutroRdoFica() {
        String obraId = obra("cede");
        String rdoQueDeclarou = id();
        String outroRdo = id();
        String tracadoDoRdo = trecho(obraId, rdoQueDeclarou);
        String tracadoDoOutro = trecho(obraId, outroRdo);

        int cederam = desenho.cederAoRdo(rdoQueDeclarou);

        assertThat(cederam).isEqualTo(1);
        assertThat(vigente(tracadoDoRdo)).isFalse();
        assertThat(status(tracadoDoRdo)).isEqualTo("ENCERRADA");
        // O desenho do vizinho não é assunto deste RDO.
        assertThat(vigente(tracadoDoOutro)).isTrue();
    }

    /** Ceder de novo não é mudança: só o vigente cede. */
    @Test
    void cederEIdempotente() {
        String obraId = obra("idempotente");
        String rdoId = id();
        trecho(obraId, rdoId);

        assertThat(desenho.cederAoRdo(rdoId)).isEqualTo(1);
        assertThat(desenho.cederAoRdo(rdoId)).isZero();
    }

    /** Sem desenho não há o que ceder — o caso comum de todo RDO. */
    @Test
    void rdoSemDesenhoNaoTemOQueCeder() {
        assertThat(desenho.cederAoRdo(id())).isZero();
    }

    private static String obra(String suffix) {
        String obraId = id();
        jdbc.update(
                "INSERT INTO obra (id, codigo_contrato, nome) VALUES (?, ?, ?)",
                obraId,
                "CEDE-" + suffix,
                "Obra " + suffix
        );
        return obraId;
    }

    private static String trecho(String obraId, String rdoId) {
        String trechoId = id();
        jdbc.update(
                """
                INSERT INTO obra_geometria (
                    id, obra_id, categoria, tipo_geometria,
                    objeto_tipo, objeto_id,
                    geometria_json, propriedades_json,
                    fonte, status, valido_desde,
                    criado_por, atualizado_por, criado_em, atualizado_em
                ) VALUES (
                    ?, ?, 'TRECHO', 'LINESTRING',
                    'RDO', ?,
                    CAST(? AS jsonb), CAST('{}' AS jsonb),
                    'GESTAO_MAPA', 'ATIVA', ?,
                    ?, ?, ?, ?
                )
                """,
                trechoId,
                obraId,
                rdoId,
                "{\"type\":\"LineString\",\"coordinates\":[[-47.4,-22.0],[-47.3,-21.9]]}",
                LocalDateTime.now(),
                colaboradorId,
                colaboradorId,
                LocalDateTime.now(),
                LocalDateTime.now()
        );
        return trechoId;
    }

    private static boolean vigente(String trechoId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT valido_ate IS NULL FROM obra_geometria WHERE id = ?",
                Boolean.class,
                trechoId
        ));
    }

    private static String status(String trechoId) {
        return jdbc.queryForObject(
                "SELECT status FROM obra_geometria WHERE id = ?",
                String.class,
                trechoId
        );
    }

    private static String id() {
        return UUID.randomUUID().toString();
    }
}
