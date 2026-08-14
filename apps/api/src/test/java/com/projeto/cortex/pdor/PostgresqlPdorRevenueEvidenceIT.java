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

        assertThat(input.sourceValues().contractValue())
                .isEqualByComparingTo("12500.00");
        assertThat(input.sourceValues().measuredRevenue())
                .isEqualByComparingTo("250.00");
        assertThat(input.sourceValues().validatedRevenue())
                .isEqualByComparingTo("250.00");
        assertThat(input.sourceValues().actualExecutedQuantity())
                .isEqualTo(2.0d);
        assertThat(input.inputs().get("remainingContractedQuantity"))
                .isEqualTo(new BigDecimal("98.000"));
        assertThat(input.inputs().get("quantityMetric"))
                .isEqualTo("SERVICE_CATALOG_CONTRACT");
        assertThat(input.origins().get("contractValue").source())
                .contains("service_price_version.quantidade_contratada")
                .doesNotContain("fallback");
        assertThat(input.origins().get("totalPlannedQuantity").source())
                .isEqualTo("service_price_version.quantidade_contratada");
        assertThat(input.evidenceReferences())
                .extracting(PdorEvidenceReference::entityType)
                .contains("SERVICE_PRICE_VERSION")
                .doesNotContain("ITEM_CONTRATUAL");
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

    /*
     * O caso do primeiro RDO: contrato cadastrado, produção apontada e
     * nenhuma medição fechada ainda.
     *
     * Antes, receita medida ausente era lacuna obrigatória e o PDOR devolvia
     * vazio — justamente no começo da obra, quando a projeção mais serve. A
     * régua da receita não mudou; o que mudou é que zero medido passou a ser
     * lido como fato, e a produção apontada sustenta o avanço físico.
     */
    @Test
    void projetaComProducaoApontadaQuandoNenhumaReceitaFoiMedidaAinda() {
        Obra obra = Obra.criar(
                "PDOR-APONTADA", null, null, "Obra PDOR apontada", null, null,
                null, null, null, "ATIVA", "TEST", null, null
        );
        Fixture fixture = fixture(obra);
        apontamentoSemReceita(fixture, "30.000");
        apontamentoSemReceita(fixture, "12.000");

        PdorInputBundle input = new RealPdorInputLoader(jdbc)
                .load(obra, REFERENCE_DATE);

        assertThat(input.missingRequiredFields()).isEmpty();
        assertThat(input.canCalculate()).isTrue();
        assertThat(input.sourceValues().measuredRevenue())
                .isEqualByComparingTo("0");
        assertThat(input.sourceValues().validatedRevenue())
                .isEqualByComparingTo("0");
        assertThat(input.revenueCoverageCode())
                .isEqualTo("NO_ACCEPTED_EVIDENCE");
        assertThat(input.sourceValues().actualExecutedQuantity())
                .isEqualTo(42.0d);
        assertThat((BigDecimal) input.inputs().get("reportedExecutedQuantity"))
                .isEqualByComparingTo("42.000");
        assertThat(input.origins().get("actualExecutedQuantity").source())
                .contains("apontada");
        assertThat(input.origins().get("measuredRevenue").availability())
                .isEqualTo(PdorDataAvailability.DERIVED);
    }

    /*
     * Produção rejeitada e retrabalho não entram: o que foi recusado não foi
     * entregue, e refazer não é avançar.
     */
    @Test
    void producaoApontadaIgnoraRetrabalhoEProducaoRejeitada() {
        Obra obra = Obra.criar(
                "PDOR-APONTADA-FILTRO", null, null, "Obra PDOR filtro", null,
                null, null, null, null, "ATIVA", "TEST", null, null
        );
        Fixture fixture = fixture(obra);
        apontamentoSemReceita(fixture, "10.000");
        apontamento(fixture, "7.000", true, false, false);
        apontamento(fixture, "5.000", false, true, false);
        apontamento(fixture, "3.000", false, false, true);

        PdorInputBundle input = new RealPdorInputLoader(jdbc)
                .load(obra, REFERENCE_DATE);

        assertThat((BigDecimal) input.inputs().get("reportedExecutedQuantity"))
                .isEqualByComparingTo("10.000");
    }

    /*
     * Apagar o RDO tira do PDOR o que ele apontou.
     *
     * Cancelar marca `rdo.cancelado_em` e não toca nas linhas de execução —
     * elas seguem com `cancelada = FALSE`, porque ninguém cancelou a linha,
     * cancelou-se o documento inteiro. Sem atravessar o RDO, a produção do dia
     * apagado continuava somando, e a projeção seguia contando um trabalho que
     * a obra já tinha desfeito.
     */
    @Test
    void oRdoApagadoDeixaDeContarNoPdor() {
        Obra obra = Obra.criar(
                "PDOR-RDO-APAGADO", null, null, "Obra PDOR apagado", null,
                null, null, null, null, "ATIVA", "TEST", null, null
        );
        Fixture fixture = fixture(obra);
        apontamentoSemReceita(fixture, "40.000");

        PdorInputBundle antes = new RealPdorInputLoader(jdbc)
                .load(obra, REFERENCE_DATE);
        assertThat((BigDecimal) antes.inputs().get("reportedExecutedQuantity"))
                .isEqualByComparingTo("40.000");

        jdbc.update("""
                UPDATE rdo
                SET status = 'CANCELADA', cancelado_em = CURRENT_TIMESTAMP(6)
                WHERE id = ?
                """, fixture.rdoId());

        PdorInputBundle depois = new RealPdorInputLoader(jdbc)
                .load(obra, REFERENCE_DATE);

        assertThat(depois.inputs().get("reportedExecutedQuantity")).isNull();
        assertThat(depois.inputs().get("actualExecutedQuantity")).isNull();
        assertThat(depois.evidenceReferences())
                .extracting(PdorEvidenceReference::entityType)
                .doesNotContain("EXECUCAO_SERVICO_RDO");
    }

    /*
     * A ontologia guarda tudo; a evidência do cálculo, não.
     *
     * `cortex_evento_operacional` é registro histórico e não tem chave
     * estrangeira para `rdo`: apagado o RDO, tudo o que ele causou continua
     * lá, e tem de continuar — é assim que se responde amanhã por que o
     * relatório do dia 12 sumiu. Só que a leitura do PDOR varria a obra
     * inteira e trazia esses eventos como evidência do número de hoje. A tela
     * então dizia "o cálculo leu os registros vivos da obra: … 50 eventos
     * operacionais", contando como vivo o rastro de um RDO já apagado.
     *
     * E o pior deles era o próprio PDOR: cada execução grava o seu evento, a
     * execução seguinte o encontrava e o citava. A projeção se apoiava na
     * projeção anterior, e a versão dos dados mudava a cada execução mesmo com
     * a obra parada — nenhum recálculo era idempotente.
     */
    @Test
    void aEvidenciaOntologicaSoTrazEventoDeRdoVivoENuncaOProprioPdor() {
        Obra obra = Obra.criar(
                "PDOR-ONTOLOGIA-VIVA", null, null, "Obra PDOR ontologia",
                null, null, null, null, null, "ATIVA", "TEST", null, null
        );
        Fixture fixture = fixture(obra);
        String doRdo = evento(
                fixture, 951L, "RDO", fixture.rdoId(), fixture.rdoId(),
                "RDO_CRIADO", "RDO_API"
        );
        String daObra = evento(
                fixture, 952L, "SERVICE_PRICE_VERSION", fixture.priceId(), null,
                "SERVICE_PRICE_VERSION_PUBLICADA", "CORTEX_FINANCEIRO"
        );
        String doPdor = evento(
                fixture, 953L, "PDOR", id(), null, "PDOR_CALCULADO", "PDOR"
        );

        List<String> vivos = eventosLidos(
                new RealPdorInputLoader(jdbc).load(obra, REFERENCE_DATE)
        );

        assertThat(vivos).contains(doRdo, daObra).doesNotContain(doPdor);

        jdbc.update("""
                UPDATE rdo
                SET status = 'CANCELADA', cancelado_em = CURRENT_TIMESTAMP(6)
                WHERE id = ?
                """, fixture.rdoId());

        List<String> apagado = eventosLidos(
                new RealPdorInputLoader(jdbc).load(obra, REFERENCE_DATE)
        );

        // O evento do RDO apagado sai; o da obra, que não depende de RDO
        // nenhum, fica; o do próprio PDOR nunca esteve lá.
        assertThat(apagado).contains(daObra).doesNotContain(doRdo, doPdor);
    }

    private static List<String> eventosLidos(PdorInputBundle input) {
        return input.evidenceReferences().stream()
                .filter(reference ->
                        "EVENTO_OPERACIONAL".equals(reference.entityType()))
                .map(PdorEvidenceReference::entityId)
                .toList();
    }

    /**
     * Um evento da obra na data de referência. A hora é explícita porque a
     * evidência corta em {@code referência + 1 dia}: {@code now()} num
     * contêiner que roda depois dessa data cairia fora da janela e o teste
     * passaria sem provar nada.
     */
    private static String evento(
            Fixture fixture,
            long commitSequence,
            String tipoEntidade,
            String entidadeId,
            String rdoId,
            String tipoEvento,
            String fonte
    ) {
        String eventId = id();
        jdbc.update("""
                INSERT INTO cortex_evento_operacional (
                    id, commit_seq, tipo_entidade, entidade_id, obra_id, rdo_id,
                    tipo_evento, fonte, origem, sync_status, schema_version,
                    payload_json, ocorrido_em
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'ONLINE', 'SYNCED', 1,
                          '{}'::jsonb, ?)
                """, eventId, commitSequence, tipoEntidade, entidadeId,
                fixture.obraId(), rdoId, tipoEvento, fonte,
                REFERENCE_DATE.atTime(8, 0));
        return eventId;
    }

    /*
     * O teto do contrato só se sustenta em cadastro vivo.
     *
     * O valor contratual soma quantidade_contratada × valor_unitario das
     * versões vigentes de serviços ACTIVE. Excluir o serviço no catálogo — ou
     * cancelar a versão de preço — tem de derrubar o teto no recálculo
     * seguinte. Sem isso, a obra que tirou o serviço do contrato continuava
     * exibindo como previsão o teto formado por ele, e "recalcular" devolvia o
     * mesmo número.
     */
    @Test
    void oServicoExcluidoEOPrecoCanceladoDeixamDeFormarOTetoDoContrato() {
        Obra obra = Obra.criar(
                "PDOR-CATALOGO-VIVO", null, null, "Obra PDOR catálogo vivo",
                null, null, null, null, null, "ATIVA", "TEST", null, null
        );
        Fixture fixture = fixture(obra);

        PdorInputBundle vivo = new RealPdorInputLoader(jdbc)
                .load(obra, REFERENCE_DATE);
        assertThat(vivo.sourceValues().contractValue())
                .isEqualByComparingTo("12500.00");

        // É o que ServicePriceCatalogService.excluirServico grava — inclusive
        // quem e quando: o banco recusa EXCLUIDO sem os dois, de propósito.
        String atorDaExclusao = id();
        jdbc.update("""
                INSERT INTO colaborador (
                    id, banco_origem, tabela_origem, pk_origem, nome, papel_acesso
                ) VALUES (?, 'pdor-it', 'colaborador', ?, 'PDOR excluidor', 'ALFA')
                """, atorDaExclusao, atorDaExclusao);
        jdbc.update("""
                UPDATE catalogo_servico
                SET status = 'EXCLUIDO',
                    excluido_em = CURRENT_TIMESTAMP(6),
                    excluido_por = ?,
                    commit_revision = cortex_next_service_catalog_revision()
                WHERE id = ?
                """, atorDaExclusao, fixture.serviceId());

        PdorInputBundle excluido = new RealPdorInputLoader(jdbc)
                .load(obra, REFERENCE_DATE);
        assertThat(excluido.inputs().get("contractValue")).isNull();
        assertThat(excluido.missingRequiredFields()).contains("contractValue");
        assertThat(excluido.evidenceReferences())
                .extracting(PdorEvidenceReference::entityType)
                .doesNotContain("SERVICE_PRICE_VERSION");

        // Restaurado, o teto volta — a exclusão é estado, não perda.
        jdbc.update("""
                UPDATE catalogo_servico
                SET status = 'ACTIVE',
                    excluido_em = NULL,
                    excluido_por = NULL,
                    commit_revision = cortex_next_service_catalog_revision()
                WHERE id = ?
                """, fixture.serviceId());
        assertThat(new RealPdorInputLoader(jdbc)
                .load(obra, REFERENCE_DATE)
                .sourceValues()
                .contractValue())
                .isEqualByComparingTo("12500.00");

        // Cancelar a versão de preço a partir da referência encerra a
        // vigência efetiva na véspera: o teto cai pelo mesmo caminho que o
        // catálogo usa em produção, a service_price_version_cancellation.
        jdbc.update("""
                INSERT INTO service_price_version_cancellation (
                    id, price_version_id, obra_id, vigencia_cancelamento,
                    motivo, criado_por
                ) VALUES (?, ?, ?, ?, 'Preço cancelado no teste.', ?)
                """, id(), fixture.priceId(), fixture.obraId(),
                REFERENCE_DATE, atorDaExclusao);

        PdorInputBundle cancelado = new RealPdorInputLoader(jdbc)
                .load(obra, REFERENCE_DATE);
        assertThat(cancelado.inputs().get("contractValue")).isNull();
        assertThat(cancelado.missingRequiredFields()).contains("contractValue");
    }

    private static void apontamentoSemReceita(
            Fixture fixture,
            String quantity
    ) {
        apontamento(fixture, quantity, false, false, false);
    }

    private static void apontamento(
            Fixture fixture,
            String quantity,
            boolean retrabalho,
            boolean producaoRejeitada,
            boolean cancelada
    ) {
        String executionId = id();
        jdbc.update("""
                INSERT INTO execucao_servico_rdo (
                    id, rdo_id, obra_id, servico_nome, item_contratual_id,
                    service_id, quantidade_executada, unidade_medida,
                    data_execucao, status_validacao, estado_receita,
                    retrabalho, producao_rejeitada, cancelada, fonte,
                    chave_execucao, revenue_coverage_code
                ) VALUES (?, ?, ?, 'PDOR service', NULL, ?, ?, 'M2', ?,
                          'REGISTRADA', 'PRODUCAO_REGISTRADA', ?, ?, ?,
                          'PDOR_IT', ?, 'UNPRICED_REGISTERED')
                """, executionId, fixture.rdoId(), fixture.obraId(),
                fixture.serviceId(), new BigDecimal(quantity), REFERENCE_DATE,
                retrabalho, producaoRejeitada, cancelada, key(executionId));
    }

    private static Fixture fixture(Obra obra) {
        String actorId = id();
        String rdoId = id();
        String serviceId = id();
        String priceId = id();
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
        /*
         * O código do serviço é único no catálogo inteiro, e não por obra: a
         * unicidade é sobre `lower(codigo)`. Um código fixo aqui só funciona
         * enquanto a classe tiver um único teste — o segundo a montar o cenário
         * esbarra no primeiro, porque o contêiner é o mesmo.
         */
        jdbc.update("""
                INSERT INTO catalogo_servico (
                    id, codigo, nome, status, obra_autorizadora_id, criado_por
                ) VALUES (?, ?, 'PDOR service', 'ACTIVE', ?, ?)
                """,
                serviceId,
                "PDOR.SERVICE." + obra.getCodigoContrato(),
                obra.getId(),
                actorId);
        jdbc.update("""
                INSERT INTO service_price_version (
                    id, obra_id, service_id, unidade, moeda, versao,
                    valor_unitario, quantidade_contratada,
                    vigencia_inicio, fonte, criado_por
                ) VALUES (
                    ?, ?, ?, 'M2', 'BRL', 1, 125.0000, 100.000,
                    ?, 'PDOR_IT', ?
                )
                """, priceId, obra.getId(), serviceId,
                REFERENCE_DATE.minusDays(10), actorId);
        return new Fixture(
                obra.getId(), rdoId, serviceId, priceId
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
                ) VALUES (?, ?, ?, 'PDOR service', NULL, ?, ?, ?, 'M2', ?,
                          'VALIDADA', 'RECEITA_MEDIDA', FALSE, FALSE, 'PDOR_IT',
                          ?, 125.0000, 'BRL', ?, 'ACCEPTED_EXACT', ?, ?, now(),
                          999999.99, 888888.88)
                """, executionId, fixture.rdoId(), fixture.obraId(),
                fixture.serviceId(), fixture.priceId(),
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
            String priceId
    ) {
    }

    private record Accepted(
            String executionId,
            String evidenceId,
            String eventId
    ) {
    }
}
