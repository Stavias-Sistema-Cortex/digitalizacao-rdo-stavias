package com.projeto.cortex.pdor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
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
 * "Não dá para calcular" precisa caber na posição de atual.
 *
 * <p>Apagar o último RDO que sustentava a projeção deixa a obra sem entrada
 * suficiente. O snapshot que registra isso tem de ocupar a posição de atual —
 * senão a projeção velha, calculada com o RDO ainda vivo, continua sendo
 * apresentada como se ainda valesse.
 *
 * <p>Havia um CHECK dizendo que só SUCCESS podia ser atual. Publicar o snapshot
 * de dados insuficientes passou a violá-lo: o cálculo morria com erro do banco,
 * nada novo era gravado, e a leitura caía de volta no snapshot vencido. Este
 * teste prende a regra nova contra um PostgreSQL de verdade, porque ela mora
 * numa restrição do banco e nenhum teste de unidade a alcança.
 */
@Testcontainers(disabledWithoutDocker = true)
class PostgresqlPdorSemDadosAtualIT {

    private static final LocalDate REFERENCE_DATE = LocalDate.of(2026, 8, 11);
    private static final LocalDateTime EXECUTED_AT =
            LocalDateTime.of(2026, 8, 11, 14, 39);

    @Container
    private static final PostgreSQLContainer<?> DATABASE =
            new PostgreSQLContainer<>("postgres:18")
                    .withDatabaseName("cortex_pdor_sem_dados_it");

    private static JdbcTemplate jdbc;
    private static PdorSnapshotRepository repository;

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
        repository = new PdorSnapshotRepository(jdbc, new ObjectMapper());
    }

    @Test
    void semDadosSubstituiAProjecaoQueDeixouDeSeSustentar() {
        String obraId = obra("sem-dados");
        String comORdo = id();
        String semORdo = id();

        repository.replaceCurrent(snapshot(
                comORdo, obraId, PdorExecutionStatus.SUCCESS, null
        ));

        assertThatCode(() -> repository.replaceCurrent(snapshot(
                semORdo,
                obraId,
                PdorExecutionStatus.INSUFFICIENT_DATA,
                "Dados insuficientes para calcular o PDOR."
        ))).doesNotThrowAnyException();

        assertThat(currentSnapshotId(obraId)).isEqualTo(semORdo);
        assertThat(flag(comORdo, "is_current")).isFalse();
        assertThat(flag(comORdo, "is_stale")).isTrue();
        // O histórico continua lá, apenas vencido.
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM pdor_snapshot WHERE obra_id = ?",
                Integer.class,
                obraId
        )).isEqualTo(2);
    }

    /*
     * Falha não é projeção: ela não descreve a obra, descreve o cálculo que não
     * saiu. Tem tabela própria, com correlação e horário da tentativa, e
     * continua proibida de ocupar a posição de atual.
     */
    @Test
    void falhaContinuaProibidaDeOcuparAPosicaoDeAtual() {
        String obraId = obra("falha");

        assertThatThrownBy(() -> repository.replaceCurrent(snapshot(
                id(), obraId, PdorExecutionStatus.FAILED, "estourou"
        )))
                .hasMessageContaining("chk_pdor_current_success");
    }

    private static String obra(String suffix) {
        String obraId = id();
        jdbc.update(
                """
                INSERT INTO obra (id, codigo_contrato, nome)
                VALUES (?, ?, ?)
                """,
                obraId,
                "PDOR-SD-" + suffix,
                "Obra PDOR " + suffix
        );
        return obraId;
    }

    private static String currentSnapshotId(String obraId) {
        return jdbc.queryForObject(
                """
                SELECT id FROM pdor_snapshot
                WHERE obra_id = ? AND is_current
                """,
                String.class,
                obraId
        );
    }

    private static boolean flag(String snapshotId, String column) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT " + column + " FROM pdor_snapshot WHERE id = ?",
                Boolean.class,
                snapshotId
        ));
    }

    private static PdorSnapshot snapshot(
            String snapshotId,
            String obraId,
            PdorExecutionStatus status,
            String executionError
    ) {
        ObjectMapper mapper = new ObjectMapper();
        boolean calculated = status == PdorExecutionStatus.SUCCESS;
        PdorSnapshot base = new PdorSnapshot(
                snapshotId,
                obraId,
                "PDOR-SD",
                EXECUTED_AT,
                REFERENCE_DATE,
                "PDOR-REVENUE-1",
                "PDOR-ASSUMPTIONS-1",
                status,
                PdorTriggerType.MANUAL,
                null,
                snapshotId.replace("-", "") + "0".repeat(32),
                mapper.createObjectNode(),
                mapper.createObjectNode(),
                mapper.createArrayNode(),
                calculated ? "SIMULATION" : null,
                calculated ? "CALIBRATED" : null,
                calculated ? "PRODUCTION" : null,
                calculated ? "LOW" : null,
                calculated ? BigDecimal.ONE : null,
                calculated ? BigDecimal.ONE : null,
                calculated ? BigDecimal.ONE : null,
                calculated ? BigDecimal.ONE : null,
                calculated ? BigDecimal.ONE : null,
                calculated ? BigDecimal.ONE : null,
                calculated ? BigDecimal.ONE : null,
                calculated ? BigDecimal.ONE : null,
                calculated ? BigDecimal.ONE : null,
                calculated ? BigDecimal.ONE : null,
                calculated ? BigDecimal.ZERO : null,
                calculated ? BigDecimal.ZERO : null,
                calculated ? BigDecimal.ZERO : null,
                calculated ? BigDecimal.ZERO : null,
                calculated ? BigDecimal.ONE : null,
                calculated ? Boolean.TRUE : null,
                calculated ? 10_000 : null,
                mapper.createArrayNode(),
                executionError,
                EXECUTED_AT
        );
        return base.withRevenueMetadata(
                "PDOR-REVENUE-1",
                List.of(),
                40L,
                "NO_ACCEPTED_EVIDENCE",
                mapper.createObjectNode(),
                EXECUTED_AT.toInstant(ZoneOffset.UTC),
                false,
                true
        );
    }

    private static String id() {
        return UUID.randomUUID().toString();
    }
}
