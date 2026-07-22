package com.projeto.cortex.pdor;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class PdorSnapshotRepositoryTest {

    @Test
    void shouldInsertJsonColumnsAsValidatedParameters() {
        CapturingJdbcTemplate jdbcTemplate = new CapturingJdbcTemplate();
        ObjectMapper objectMapper = new ObjectMapper();
        PdorSnapshotRepository repository =
                new PdorSnapshotRepository(jdbcTemplate, objectMapper);

        repository.insert(snapshot(objectMapper));

        assertThat(jdbcTemplate.sql)
                .contains("INSERT INTO pdor_snapshot")
                .contains("CAST(? AS JSONB)");
        assertThat(jdbcTemplate.args).hasSize(49);
        assertThat(jdbcTemplate.sql.chars().filter(character -> character == '?').count())
                .isEqualTo(jdbcTemplate.args.length);
        assertThat(jdbcTemplate.args[12].toString()).contains("contractValue");
        assertThat(jdbcTemplate.args[13].toString()).contains("availability");
        assertThat(jdbcTemplate.args[23].toString()).contains("warning");
        assertThat(jdbcTemplate.args[47].toString()).contains("LOW_DATA_QUALITY");
    }

    private static PdorSnapshot snapshot(ObjectMapper objectMapper) {
        return new PdorSnapshot(
                "snapshot-1",
                "obra-1",
                "CW38386",
                LocalDateTime.of(2026, 6, 22, 10, 0),
                LocalDate.of(2026, 6, 8),
                "PDOR-0.2.0",
                "PDOR-ASSUMPTIONS-0.2.0",
                PdorExecutionStatus.SUCCESS,
                PdorTriggerType.MANUAL,
                null,
                "a".repeat(64),
                objectMapper.createObjectNode()
                        .put("contractValue", "1000000.00"),
                objectMapper.createObjectNode()
                        .putObject("contractValue")
                        .put("availability", "DIRECT"),
                objectMapper.createArrayNode()
                        .add("warning"),
                "ISOLATED_ENGINE",
                "NOT_CALIBRATED",
                "PRODUCTION",
                "LOW",
                new BigDecimal("900000.00"),
                new BigDecimal("950000.00"),
                new BigDecimal("1000000.00"),
                new BigDecimal("1050000.00"),
                new BigDecimal("940000.00"),
                new BigDecimal("960000.00"),
                new BigDecimal("970000.00"),
                new BigDecimal("950000.00"),
                new BigDecimal("1.200000"),
                new BigDecimal("1.100000"),
                new BigDecimal("0.100000"),
                new BigDecimal("0.050000"),
                new BigDecimal("0.020000"),
                new BigDecimal("0.100000"),
                new BigDecimal("0.800000"),
                true,
                2_000,
                objectMapper.createArrayNode()
                        .add(objectMapper.createObjectNode()
                                .put("code", "LOW_DATA_QUALITY")),
                null,
                LocalDateTime.of(2026, 6, 22, 10, 0)
        );
    }

    private static final class CapturingJdbcTemplate extends JdbcTemplate {
        private String sql;
        private Object[] args;

        @Override
        public int update(String sql, Object... args) {
            this.sql = sql;
            this.args = args;
            return 1;
        }
    }
}
