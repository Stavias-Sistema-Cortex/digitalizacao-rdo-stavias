package com.projeto.cortex.pdor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.projeto.cortex.obras.Obra;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers(disabledWithoutDocker = true)
class PostgresqlPdorRevenueEvidenceIT {

    private static final LocalDate REFERENCE_DATE = LocalDate.of(2026, 7, 22);

    @Container
    private static final PostgreSQLContainer<?> DATABASE =
            new PostgreSQLContainer<>("postgres:18")
                    .withDatabaseName("cortex_pdor_revenue_it");

    private static JdbcTemplate jdbc;

    @BeforeAll
    static void migrate() {
        Flyway.configure()
                .dataSource(
                        DATABASE.getJdbcUrl(), DATABASE.getUsername(),
                        DATABASE.getPassword()
                )
                .locations("classpath:db/migration-postgresql")
                .load()
                .migrate();
        jdbc = new JdbcTemplate(new DriverManagerDataSource(
                DATABASE.getJdbcUrl(), DATABASE.getUsername(), DATABASE.getPassword()
        ));
    }

    @Test
    void loadsOnlyCanonicalAcceptedEvidenceAndPersistsImmutableProjectionMetadata() {
        Obra obra = Obra.criar(
                "PDOR-V54", null, null, "Obra PDOR V54", null, null,
                null, null, null, "ATIVA", "TEST", null, null
        );
        Fixture fixture = fixture(obra);
        Accepted valid = acceptedExecution(fixture, "2.000", "250.00", 901L);
        assertThatThrownBy(() -> jdbc.update("""
                UPDATE cortex_evento_operacional
                SET payload_json = jsonb_set(
                    payload_json, '{revenue}', '999999.99'::jsonb
                )
                WHERE id = ?
                """, valid.eventId()))
                .isInstanceOf(DataAccessException.class)
                .rootCause()
                .hasMessageContaining("RDO_REVENUE_EVENT_IMMUTABLE");
        jdbc.update("""
                UPDATE cortex_evento_commit_sequence
                SET ultima_commit_seq = 902
                WHERE id = 1
                """);

        PdorInputBundle input = new RealPdorInputLoader(jdbc)
                .load(obra, REFERENCE_DATE);

        assertThat(input.sourceValues().measuredRevenue())
                .isEqualByComparingTo("250.00");
        assertThat(input.sourceValues().validatedRevenue())
                .isEqualByComparingTo("250.00");
        assertThat(input.sourceValues().actualExecutedQuantity())
                .isEqualTo(2.0d);
        assertThat(input.inputs().get("remainingContractedQuantity"))
                .isEqualTo(new BigDecimal("98.000"));
        assertThat(input.revenueEvidenceIds()).containsExactly(valid.evidenceId());
        assertThat(input.evidenceHighWaterMark()).isEqualTo(902L);
        assertThat(input.revenueCoverageCode())
                .isEqualTo("COMPLETE_ACCEPTED_EXACT");

        ObjectMapper mapper = new ObjectMapper();
        PdorSnapshotRepository repository = new PdorSnapshotRepository(jdbc, mapper);
        PdorSnapshot first = snapshot(
                mapper, obra.getId(), valid.evidenceId(), 902L,
                "COMPLETE_ACCEPTED_EXACT", 1
        );
        repository.replaceCurrent(first);
        PdorSnapshot second = snapshot(
                mapper, obra.getId(), valid.evidenceId(), 902L,
                "COMPLETE_ACCEPTED_EXACT", 2
        );
        repository.replaceCurrent(second);

        PdorSnapshot storedCurrent = repository.findCurrentByObraId(obra.getId())
                .orElseThrow();
        PdorSnapshot storedPrevious = repository.findByIdempotencyKey(
                first.idempotencyKey()
        ).orElseThrow();
        assertThat(storedCurrent.id()).isEqualTo(second.id());
        assertThat(storedCurrent.evidenceIds()).containsExactly(valid.evidenceId());
        assertThat(storedCurrent.evidenceHighWaterMark()).isEqualTo(902L);
        assertThat(storedCurrent.assumptions().path("iterations").asInt())
                .isEqualTo(10_000);
        assertThat(storedCurrent.executedAtUtc()).isEqualTo(
                Instant.parse("2026-07-22T12:00:02Z")
        );
        assertThat(storedCurrent.current()).isTrue();
        assertThat(storedCurrent.stale()).isFalse();
        assertThat(storedPrevious.current()).isFalse();
        assertThat(storedPrevious.stale()).isTrue();
        assertThat(jdbc.queryForObject("""
                SELECT count(*)
                FROM pdor_snapshot
                WHERE obra_id = ?
                  AND is_current = true
                """, Integer.class, obra.getId())).isOne();

        assertThatThrownBy(() -> jdbc.update(
                "UPDATE pdor_snapshot SET coverage_code = 'LEGACY_UNKNOWN' WHERE id = ?",
                second.id()
        ))
                .isInstanceOf(DataAccessException.class)
                .rootCause()
                .hasMessageContaining("PDOR_REVENUE_SNAPSHOT_IMMUTABLE");
        assertThatThrownBy(() -> jdbc.update(
                "DELETE FROM pdor_snapshot WHERE id = ?", first.id()
        ))
                .isInstanceOf(DataAccessException.class)
                .rootCause()
                .hasMessageContaining("PDOR_REVENUE_SNAPSHOT_IMMUTABLE");

        String correlationId = id();
        jdbc.update("""
                INSERT INTO pdor_calculation_failure (
                    correlation_id, obra_id, previous_snapshot_id, error_code,
                    attempted_at_utc, evidence_high_water_mark, trigger_type,
                    initiated_by
                ) VALUES (?, ?, ?, 'PDOR_CALCULATION_FAILED', now(), 902,
                          'API', 'pdor-it')
                """, correlationId, obra.getId(), second.id());
        assertThatThrownBy(() -> jdbc.update(
                "DELETE FROM pdor_calculation_failure WHERE correlation_id = ?",
                correlationId
        ))
                .isInstanceOf(DataAccessException.class)
                .rootCause()
                .hasMessageContaining("PDOR_CALCULATION_FAILURE_IMMUTABLE");
    }

    private static Fixture fixture(Obra obra) {
        String actorId = id();
        String rdoId = id();
        String serviceId = id();
        String priceId = id();
        String itemId = id();
        jdbc.update(
                "INSERT INTO obra (id, codigo_contrato, nome) VALUES (?, ?, ?)",
                obra.getId(), obra.getCodigoContrato(), obra.getNome()
        );
        jdbc.update("""
                INSERT INTO colaborador (
                    id, banco_origem, tabela_origem, pk_origem, nome, papel_acesso
                ) VALUES (?, 'pdor-it', 'colaborador', ?, 'PDOR actor', 'ALFA')
                """, actorId, actorId);
        jdbc.update(
                "INSERT INTO rdo (id, obra_id, numero_rdo, data_rdo) VALUES (?, ?, 'RDO-PDOR', ?)",
                rdoId, obra.getId(), REFERENCE_DATE
        );
        jdbc.update("""
                INSERT INTO catalogo_servico (
                    id, codigo, nome, status, obra_autorizadora_id, criado_por
                ) VALUES (?, 'PDOR.SERVICE', 'PDOR service', 'ACTIVE', ?, ?)
                """, serviceId, obra.getId(), actorId);
        jdbc.update("""
                INSERT INTO service_price_version (
                    id, obra_id, service_id, unidade, moeda, versao,
                    valor_unitario, vigencia_inicio, fonte, criado_por
                ) VALUES (?, ?, ?, 'M2', 'BRL', 1, 125.0000, ?, 'PDOR_IT', ?)
                """, priceId, obra.getId(), serviceId,
                REFERENCE_DATE.minusDays(10), actorId);
        jdbc.update("""
                INSERT INTO item_contratual (
                    id, obra_id, contrato, codigo_item, descricao,
                    unidade_medida, quantidade_contratada, preco_unitario,
                    valor_total, status
                ) VALUES (?, ?, 'PDOR', 'PDOR.SERVICE', 'PDOR service',
                          'M2', 100, 125, 12500, 'ATIVO')
                """, itemId, obra.getId());
        return new Fixture(
                obra.getId(), rdoId, serviceId, priceId, itemId
        );
    }

    private static Accepted acceptedExecution(
            Fixture fixture,
            String quantity,
            String revenue,
            long commitSequence
    ) {
        String executionId = id();
        String evidenceId = id();
        String eventId = id();
        jdbc.update("""
                INSERT INTO cortex_evento_operacional (
                    id, commit_seq, tipo_entidade, entidade_id, obra_id, rdo_id,
                    tipo_evento, fonte, origem, sync_status, schema_version,
                    entidades_relacionadas_json, estado_novo_json,
                    payload_json, ocorrido_em
                ) VALUES (?, ?, 'RDO_EXECUTION', ?, ?, ?,
                          'RDO_SERVICE_EXECUTED', 'CORTEX_FINANCEIRO', 'ONLINE',
                          'SYNCED', 1,
                          jsonb_build_array(
                              jsonb_build_object('tipo', 'RDO', 'id', ?),
                              jsonb_build_object('tipo', 'WORKSITE', 'id', ?),
                              jsonb_build_object('tipo', 'SERVICE', 'id', ?),
                              jsonb_build_object(
                                  'tipo', 'SERVICE_PRICE_VERSION', 'id', ?
                              ),
                              jsonb_build_object(
                                  'tipo', 'REVENUE_EVIDENCE', 'id', ?
                              )
                          ),
                          jsonb_build_object(
                              'status', 'ACCEPTED',
                              'revenueEvidenceId', ?
                          ),
                          jsonb_build_object(
                              'schemaVersion', 1,
                              'rdoId', ?,
                              'obraId', ?,
                              'serviceId', ?,
                              'priceVersionId', ?,
                              'revenueEvidenceId', ?,
                              'acceptedQuantity', ?::numeric,
                              'unit', 'M2',
                              'unitPrice', 125.0000,
                              'currency', 'BRL',
                              'revenue', ?::numeric,
                              'status', 'ACCEPTED'
                          ),
                          now())
                """, eventId, commitSequence, executionId, fixture.obraId(),
                fixture.rdoId(), fixture.rdoId(), fixture.obraId(),
                fixture.serviceId(), fixture.priceId(), evidenceId, evidenceId,
                fixture.rdoId(), fixture.obraId(), fixture.serviceId(),
                fixture.priceId(), evidenceId, quantity, revenue);
        jdbc.update("""
                INSERT INTO execucao_servico_rdo (
                    id, rdo_id, obra_id, servico_nome, item_contratual_id,
                    service_id, price_version_id, quantidade_executada,
                    unidade_medida, data_execucao, status_validacao,
                    estado_receita, retrabalho, producao_rejeitada, fonte,
                    chave_execucao, unit_price_snapshot, currency,
                    revenue_amount, revenue_coverage_code,
                    revenue_evidence_id, revenue_event_id, accepted_at,
                    receita_operacional_estimativa, custo_realizado
                ) VALUES (?, ?, ?, 'PDOR service', ?, ?, ?, ?, 'M2', ?,
                          'VALIDADA', 'RECEITA_MEDIDA', FALSE, FALSE, 'PDOR_IT',
                          ?, 125.0000, 'BRL', ?, 'ACCEPTED_EXACT', ?, ?, now(),
                          999999.99, 888888.88)
                """, executionId, fixture.rdoId(), fixture.obraId(),
                fixture.itemId(), fixture.serviceId(), fixture.priceId(),
                new BigDecimal(quantity), REFERENCE_DATE, key(executionId),
                new BigDecimal(revenue), evidenceId, eventId);
        return new Accepted(executionId, evidenceId, eventId);
    }

    private static PdorSnapshot snapshot(
            ObjectMapper mapper,
            String obraId,
            String evidenceId,
            long highWaterMark,
            String coverage,
            int sequence
    ) {
        LocalDateTime executedAt = LocalDateTime.of(
                2026, 7, 22, 12, 0, sequence
        );
        PdorSnapshot base = new PdorSnapshot(
                id(), obraId, "PDOR-V54", executedAt, REFERENCE_DATE,
                "PDOR-REVENUE-1", "PDOR-ASSUMPTIONS-1",
                PdorExecutionStatus.SUCCESS, PdorTriggerType.API,
                null, key("snapshot-" + sequence),
                mapper.createObjectNode(), mapper.createObjectNode(),
                mapper.createArrayNode(), "SIMULATION", "CALIBRATED",
                "PRODUCTION", "LOW",
                BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE,
                BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE,
                BigDecimal.ONE, BigDecimal.ONE,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ONE, true, 10_000,
                mapper.createArrayNode(), null, executedAt
        );
        return base.withRevenueMetadata(
                "PDOR-REVENUE-1",
                List.of(evidenceId),
                highWaterMark,
                coverage,
                mapper.createObjectNode().put("iterations", 10_000),
                executedAt.toInstant(ZoneOffset.UTC),
                false,
                true
        );
    }

    private static String key(String seed) {
        return "0".repeat(56)
                + String.format("%08x", seed.hashCode());
    }

    private static String id() {
        return UUID.randomUUID().toString();
    }

    private record Fixture(
            String obraId,
            String rdoId,
            String serviceId,
            String priceId,
            String itemId
    ) {
    }

    private record Accepted(
            String executionId,
            String evidenceId,
            String eventId
    ) {
    }
}
