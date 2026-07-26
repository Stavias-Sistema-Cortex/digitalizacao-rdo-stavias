package com.projeto.cortex.pdor;

import com.projeto.cortex.obras.Obra;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class RealPdorInputLoaderMysqlIntegrationTest {

    private PdorMysqlTestDatabase database;
    private JdbcTemplate jdbcTemplate;
    private RealPdorInputLoader loader;

    @BeforeEach
    void setUp() {
        assumeTrue(
                PdorMysqlTestDatabase.isAvailable(),
                "MySQL local do compose não está disponível em 127.0.0.1:3307"
        );

        database = PdorMysqlTestDatabase.create("loader");
        database.migrate();
        jdbcTemplate = new JdbcTemplate(database.dataSource());
        loader = new RealPdorInputLoader(jdbcTemplate);
    }

    @AfterEach
    void tearDown() {
        if (database != null) {
            database.drop();
        }
    }

    @Test
    void shouldLoadOperationalInputsFromRealMysqlSchema() {
        Obra obra = Obra.criar(
                "CW-LOADER",
                "CW-LOADER",
                "Teste Loader",
                "Obra teste loader",
                "Intervias",
                null,
                "Leme",
                "SP",
                "SP 330",
                "ATIVA",
                "TESTE",
                null,
                null
        );
        insertObra(obra);

        insertProgramacao(obra, LocalDate.of(2026, 6, 1), "100.000", "10.000", "20.000");
        insertProgramacao(obra, LocalDate.of(2026, 6, 5), "200.000", "20.000", "40.000");
        insertProgramacao(obra, LocalDate.of(2026, 6, 10), "300.000", "30.000", "60.000");
        insertProgramacao(obra, LocalDate.of(2026, 6, 12), null, null, null);

        String rdo1 = insertRdo(
                obra,
                LocalDate.of(2026, 6, 1),
                "Interferência operacional registrada."
        );
        String rdo2 = insertRdo(obra, LocalDate.of(2026, 6, 5), null);
        String rdoDepoisReferencia = insertRdo(obra, LocalDate.of(2026, 6, 11), null);

        insertControleGeometrico(rdo1, "80.000", "8.000", "20.000");
        insertControleGeometrico(rdo2, "170.000", "17.000", "42.500");
        insertControleGeometrico(rdoDepoisReferencia, "999.000", "99.000", "999.000");

        insertMaterial(rdo1, "50.000", "55.000");
        insertMaterial(rdo2, "60.000", "63.000");
        insertMaterial(rdoDepoisReferencia, "999.000", "999.000");

        insertEquipamento(rdo1, "08:00:00", "10:00:00", "2.000");
        insertEquipamento(rdo2, "07:30:00", "09:00:00", "1.000");
        insertEquipamento(rdoDepoisReferencia, "07:00:00", "17:00:00", "10.000");

        insertSync(obra.getId(), rdo1);

        PdorInputBundle bundle = loader.load(obra, LocalDate.of(2026, 6, 10));

        assertThat(bundle.referenceDate()).isEqualTo(LocalDate.of(2026, 6, 10));
        assertThat(bundle.inputs().get("programacaoRows")).isEqualTo(4);
        assertThat(bundle.inputs().get("scheduleStartDate")).isEqualTo(LocalDate.of(2026, 6, 1));
        assertThat(bundle.inputs().get("scheduleEndDate")).isEqualTo(LocalDate.of(2026, 6, 12));
        assertThat(bundle.inputs().get("quantityMetric")).isEqualTo("AREA_M2");

        assertDecimalInput(bundle, "totalPlannedQuantity", "600.000");
        assertDecimalInput(bundle, "plannedExecutedQuantity", "600.000");
        assertDecimalInput(bundle, "actualExecutedQuantity", "250.000");

        assertThat(bundle.inputs().get("rdoRows")).isEqualTo(2);
        assertThat(bundle.inputs().get("criticalOccurrences")).isEqualTo(1);
        assertThat(bundle.inputs().get("delayedRdos")).isEqualTo(1);

        assertDecimalInput(bundle, "expectedMaterialConsumption", "110.000");
        assertDecimalInput(bundle, "actualMaterialConsumption", "118.000");
        assertDecimalInput(bundle, "plannedEquipmentHours30d", "5.500");

        assertThat(bundle.inputs().get("pendingSyncEvents")).isEqualTo(1);
        assertThat(((Number) bundle.inputs().get("hoursSinceLastSync")).intValue())
                .isBetween(2, 4);

        assertThat(bundle.inputs().get("contractValue")).isNull();
        assertThat(bundle.inputs().get("measuredRevenue")).isNull();
        assertThat(bundle.inputs().get("validatedRevenue")).isNull();
        assertThat(bundle.inputs().get("equipmentDowntimeHours30d")).isNull();
        assertThat(bundle.missingRequiredFields())
                .containsExactly("contractValue", "measuredRevenue", "validatedRevenue");
        assertThat(bundle.origins().get("contractValue").availability())
                .isEqualTo(PdorDataAvailability.ABSENT);
        assertThat(bundle.origins().get("plannedEquipmentHours30d").availability())
                .isEqualTo(PdorDataAvailability.AMBIGUOUS);

        assertThat(bundle.warnings())
                .anySatisfy(warning -> assertThat(warning).contains("Valor contratual ausente"))
                .anySatisfy(warning -> assertThat(warning).contains("Há 1 linhas de programação"))
                .anySatisfy(warning -> assertThat(warning).contains("Horas de equipamento dos RDOs"))
                .anySatisfy(warning -> assertThat(warning).contains("Observações de RDO"));
        assertThat(bundle.warnings())
                .noneSatisfy(warning -> assertThat(warning).contains("Nenhum RDO associado"))
                .noneSatisfy(warning -> assertThat(warning).contains("Dados de material ausentes"));

        assertThat(bundle.inputs().get("actualExecutedQuantity"))
                .isNotEqualTo(BigDecimal.ZERO);
        assertThat(bundle.inputs().get("actualMaterialConsumption"))
                .isNotEqualTo(BigDecimal.ZERO);
        assertThat(bundle.inputs().get("plannedEquipmentHours30d"))
                .isNotEqualTo(BigDecimal.ZERO);
    }

    @Test
    void shouldLoadFinancialInputsFromContractAndExecutedServices() {
        Obra obra = Obra.criar(
                "CW-FIN",
                "CW-FIN",
                "Teste Financeiro",
                "Obra teste financeiro",
                "Intervias",
                null,
                "Leme",
                "SP",
                "SP 330",
                "ATIVA",
                "TESTE",
                null,
                null
        );
        insertObra(obra);

        String rdoId =
                insertRdo(
                        obra,
                        LocalDate.of(2026, 6, 20),
                        "Produção financeira registrada."
                );
        String itemId =
                insertItemContratual(
                        obra,
                        "PAV-001",
                        "Pavimentação",
                        "m2",
                        "1000.000",
                        "25.0000",
                        "25000.00"
                );

        insertExecucaoServico(
                rdoId,
                obra,
                itemId,
                LocalDate.of(2026, 6, 20),
                "100.000",
                "m2",
                "2500.00",
                "1700.00"
        );

        PdorInputBundle bundle =
                loader.load(obra, LocalDate.of(2026, 6, 20));

        assertThat(bundle.canCalculate()).isTrue();
        assertThat(bundle.missingRequiredFields()).isEmpty();
        assertThat(bundle.inputs().get("quantityMetric"))
                .isEqualTo("ITEM_CONTRATUAL_LEGACY");

        assertDecimalInput(bundle, "contractValue", "25000.00");
        assertDecimalInput(bundle, "measuredRevenue", "2500.00");
        assertDecimalInput(bundle, "validatedRevenue", "0");
        assertDecimalInput(bundle, "totalPlannedQuantity", "1000.000");
        assertDecimalInput(bundle, "plannedExecutedQuantity", "1000.000");
        assertDecimalInput(bundle, "actualExecutedQuantity", "100.000");

        assertThat(bundle.origins().get("contractValue").availability())
                .isEqualTo(PdorDataAvailability.DIRECT);
        assertThat(bundle.origins().get("measuredRevenue").availability())
                .isEqualTo(PdorDataAvailability.DIRECT);
        assertThat(bundle.origins().get("validatedRevenue").availability())
                .isEqualTo(PdorDataAvailability.DIRECT);
        assertThat(bundle.warnings())
                .anySatisfy(warning ->
                        assertThat(warning).contains(
                                "fallback histórico item_contratual"
                        ));
        assertThat(bundle.evidenceReferences())
                .extracting(PdorEvidenceReference::entityType)
                .contains("RDO", "ITEM_CONTRATUAL", "EXECUCAO_SERVICO_RDO");
    }

    @Test
    void shouldExposeRealTeamGeospatialAndWeatherContextWithoutInventingCapacity() {
        Obra obra = Obra.criar(
                "CW-CONTEXT",
                "CW-CONTEXT",
                "Teste Contexto",
                "Obra teste contexto",
                "Intervias",
                null,
                "Leme",
                "SP",
                "SP 330",
                "ATIVA",
                "TESTE",
                null,
                null
        );
        insertObra(obra);

        String rdoId = insertRdo(obra, LocalDate.of(2026, 6, 20), null);
        jdbcTemplate.update(
                """
                UPDATE rdo
                SET condicao_manha = 'Chuva leve', pluviometria_mm = 12.500
                WHERE id = ?
                """,
                rdoId
        );

        String colaboradorId = insertColaborador();
        insertTeamContext(obra, colaboradorId);
        insertGeometry(obra, colaboradorId);

        PdorInputBundle bundle = loader.load(obra, LocalDate.of(2026, 6, 20));

        assertThat(bundle.inputs())
                .containsEntry("activeTeamCount", 1)
                .containsEntry("activeTeamMemberCount", 1)
                .containsEntry("activeTeamFunctionCount", 1)
                .containsEntry("responsibleTeamMemberCount", 1)
                .containsEntry("activeGeospatialFeatureCount", 1)
                .containsEntry("weatherObservationCount", 1);
        assertDecimalInput(bundle, "rainfallMmObserved", "12.500");
        assertThat(bundle.inputs().get("laborCapacityHours")).isNull();
        assertThat(bundle.origins().get("laborCapacityHours").availability())
                .isEqualTo(PdorDataAvailability.ABSENT);
        assertThat(bundle.warnings())
                .anySatisfy(warning -> assertThat(warning)
                        .contains("quantidade de membros não foi convertida em capacidade"));
        assertThat(bundle.evidenceReferences())
                .extracting(PdorEvidenceReference::entityType)
                .contains("RDO", "EQUIPE", "EQUIPE_MEMBRO", "OBRA_GEOMETRIA");
    }

    private void assertDecimalInput(
            PdorInputBundle bundle,
            String field,
            String expected
    ) {
        assertThat((BigDecimal) bundle.inputs().get(field))
                .isEqualByComparingTo(expected);
    }

    private void insertObra(Obra obra) {
        jdbcTemplate.update(
                """
                INSERT INTO obra (
                    id, codigo_contrato, codigo_cw, codigo_interno, nome,
                    cliente, cidade, uf, rodovia, status, fonte_criacao
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                obra.getId(),
                obra.getCodigoContrato(),
                obra.getCodigoCw(),
                obra.getCodigoInterno(),
                obra.getNome(),
                obra.getCliente(),
                obra.getCidade(),
                obra.getUf(),
                obra.getRodovia(),
                obra.getStatus(),
                obra.getFonteCriacao()
        );
    }

    private void insertProgramacao(
            Obra obra,
            LocalDate data,
            String areaM2,
            String volumeM3,
            String extensaoM
    ) {
        jdbcTemplate.update(
                """
                INSERT INTO programacao_operacional (
                    id, obra_id, codigo_contrato_origem, obra_nome_origem,
                    data_programacao, equipe, servico, tipo_servico, extensao_m,
                    largura_m, espessura_cm, area_m2, volume_m3, status,
                    fonte_criacao, chave_negocio
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                uuid(),
                obra.getId(),
                obra.getCodigoContrato(),
                obra.getNome(),
                data,
                "Equipe teste",
                "Fresagem e Reposição",
                "Produção",
                decimal(extensaoM),
                areaM2 == null ? null : new BigDecimal("3.600"),
                volumeM3 == null ? null : new BigDecimal("7.000"),
                decimal(areaM2),
                decimal(volumeM3),
                "PLANEJADA",
                "TESTE",
                uuid()
        );
    }

    private String insertRdo(
            Obra obra,
            LocalDate data,
            String observacoes
    ) {
        String id = uuid();
        jdbcTemplate.update(
                """
                INSERT INTO rdo (
                    id, obra_id, numero_rdo, data_rdo, status, observacoes
                ) VALUES (?, ?, ?, ?, ?, ?)
                """,
                id,
                obra.getId(),
                "RDO-" + data,
                data,
                "ENVIADO",
                observacoes
        );
        return id;
    }

    private void insertControleGeometrico(
            String rdoId,
            String areaM2,
            String volumeM3,
            String massaTonelada
    ) {
        jdbcTemplate.update(
                """
                INSERT INTO rdo_controle_geometrico (
                    id, rdo_id, area_m2, volume_m3, massa_tonelada
                ) VALUES (?, ?, ?, ?, ?)
                """,
                uuid(),
                rdoId,
                new BigDecimal(areaM2),
                new BigDecimal(volumeM3),
                new BigDecimal(massaTonelada)
        );
    }

    private void insertMaterial(
            String rdoId,
            String quantidadePrevista,
            String quantidadeAplicada
    ) {
        jdbcTemplate.update(
                """
                INSERT INTO rdo_material (
                    id, rdo_id, material_nome, unidade,
                    quantidade_prevista, quantidade_aplicada
                ) VALUES (?, ?, ?, ?, ?, ?)
                """,
                uuid(),
                rdoId,
                "CBUQ teste",
                "t",
                new BigDecimal(quantidadePrevista),
                new BigDecimal(quantidadeAplicada)
        );
    }

    private void insertEquipamento(
            String rdoId,
            String horaInicio,
            String horaFim,
            String quantidade
    ) {
        jdbcTemplate.update(
                """
                INSERT INTO rdo_equipamento (
                    id, rdo_id, descricao, quantidade, hora_inicio, hora_fim
                ) VALUES (?, ?, ?, ?, ?, ?)
                """,
                uuid(),
                rdoId,
                "Fresadora teste",
                new BigDecimal(quantidade),
                horaInicio,
                horaFim
        );
    }

    private void insertSync(String obraId, String rdoId) {
        String dispositivoId = uuid();
        jdbcTemplate.update(
                """
                INSERT INTO sync_dispositivo (id, nome, tipo)
                VALUES (?, ?, ?)
                """,
                dispositivoId,
                "Tablet teste",
                "TABLET"
        );
        jdbcTemplate.update(
                """
                INSERT INTO sync_mutacao_cliente (
                    id, dispositivo_id, client_mutation_id, entidade_tipo,
                    entidade_id, operacao, payload_json, status, recebida_em
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?,
                    DATE_SUB(CURRENT_TIMESTAMP(6), INTERVAL 5 HOUR))
                """,
                uuid(),
                dispositivoId,
                "mutacao-obra",
                "OBRA",
                obraId,
                "ATUALIZAR_OBRA",
                "{}",
                "PENDENTE"
        );
        jdbcTemplate.update(
                """
                INSERT INTO sync_mutacao_cliente (
                    id, dispositivo_id, client_mutation_id, entidade_tipo,
                    entidade_id, operacao, payload_json, status, recebida_em,
                    aplicada_em
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?,
                    DATE_SUB(CURRENT_TIMESTAMP(6), INTERVAL 4 HOUR),
                    DATE_SUB(CURRENT_TIMESTAMP(6), INTERVAL 3 HOUR))
                """,
                uuid(),
                dispositivoId,
                "mutacao-rdo",
                "RDO",
                rdoId,
                "ENVIAR_RDO",
                "{}",
                "APLICADA"
        );
    }

    private String insertItemContratual(
            Obra obra,
            String codigoItem,
            String descricao,
            String unidade,
            String quantidade,
            String precoUnitario,
            String valorTotal
    ) {
        String id = uuid();
        jdbcTemplate.update(
                """
                INSERT INTO item_contratual (
                    id, obra_id, contrato, codigo_item, descricao,
                    unidade_medida, quantidade_contratada, preco_unitario,
                    valor_total, status, fonte
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                id,
                obra.getId(),
                obra.getCodigoContrato(),
                codigoItem,
                descricao,
                unidade,
                new BigDecimal(quantidade),
                new BigDecimal(precoUnitario),
                new BigDecimal(valorTotal),
                "ATIVO",
                "TESTE"
        );
        return id;
    }

    private String insertColaborador() {
        String id = uuid();
        jdbcTemplate.update(
                """
                INSERT INTO colaborador (
                    id, banco_origem, tabela_origem, pk_origem, nome,
                    nome_perfil, papel_acesso, ativo
                ) VALUES (?, 'TESTE', 'TESTE', ?, 'Pessoa teste', 'OPERACIONAL', 'BETA', 1)
                """,
                id,
                id
        );
        return id;
    }

    private void insertTeamContext(Obra obra, String colaboradorId) {
        String functionId = uuid();
        String teamId = uuid();
        jdbcTemplate.update(
                """
                INSERT INTO funcao_operacional (
                    id, codigo, nome, criado_por, atualizado_por
                ) VALUES (?, ?, 'Encarregado', ?, ?)
                """,
                functionId,
                "ENCARREGADO_" + functionId,
                colaboradorId,
                colaboradorId
        );
        jdbcTemplate.update(
                """
                INSERT INTO equipe (
                    id, obra_principal_id, nome, status, inicio_validade_em,
                    criado_por, atualizado_por
                ) VALUES (?, ?, 'Equipe contexto', 'ATIVA', ?, ?, ?)
                """,
                teamId,
                obra.getId(),
                LocalDateTime.of(2026, 1, 1, 0, 0),
                colaboradorId,
                colaboradorId
        );
        jdbcTemplate.update(
                """
                INSERT INTO equipe_obra (
                    id, equipe_id, obra_id, status, inicio_em, atribuido_por
                ) VALUES (?, ?, ?, 'ATIVO', ?, ?)
                """,
                uuid(),
                teamId,
                obra.getId(),
                LocalDateTime.of(2026, 1, 1, 0, 0),
                colaboradorId
        );
        jdbcTemplate.update(
                """
                INSERT INTO equipe_membro (
                    id, equipe_id, colaborador_id, funcao_operacional_id,
                    responsavel, status, inicio_em, atribuido_por, atualizado_por
                ) VALUES (?, ?, ?, ?, 1, 'ATIVO', ?, ?, ?)
                """,
                uuid(),
                teamId,
                colaboradorId,
                functionId,
                LocalDateTime.of(2026, 1, 1, 0, 0),
                colaboradorId,
                colaboradorId
        );
    }

    private void insertGeometry(Obra obra, String colaboradorId) {
        jdbcTemplate.update(
                """
                INSERT INTO obra_geometria (
                    id, obra_id, categoria, tipo_geometria, geometria_json,
                    propriedades_json, fonte, status, valido_desde,
                    versao_linha, criado_por, atualizado_por, criado_em, atualizado_em
                ) VALUES (?, ?, 'LOCALIZACAO_OBRA', 'POINT', ?, '{}', 'TESTE',
                    'ATIVA', ?, 0, ?, ?, ?, ?)
                """,
                uuid(),
                obra.getId(),
                "{\"type\":\"Point\",\"coordinates\":[-47.39,-22.18]}",
                LocalDateTime.of(2026, 1, 1, 0, 0),
                colaboradorId,
                colaboradorId,
                LocalDateTime.of(2026, 1, 1, 0, 0),
                LocalDateTime.of(2026, 1, 1, 0, 0)
        );
    }

    private void insertExecucaoServico(
            String rdoId,
            Obra obra,
            String itemId,
            LocalDate data,
            String quantidade,
            String unidade,
            String receita,
            String custo
    ) {
        jdbcTemplate.update(
                """
                INSERT INTO execucao_servico_rdo (
                    id, rdo_id, obra_id, servico_nome, item_contratual_id,
                    quantidade_executada, unidade_medida, data_execucao,
                    status_validacao, estado_receita,
                    receita_operacional_estimativa, custo_realizado,
                    fonte, chave_execucao
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                uuid(),
                rdoId,
                obra.getId(),
                "Pavimentação",
                itemId,
                new BigDecimal(quantidade),
                unidade,
                data,
                "REGISTRADA",
                "RECEITA_ESTIMADA",
                new BigDecimal(receita),
                new BigDecimal(custo),
                "TESTE",
                uuid()
        );
    }

    private BigDecimal decimal(String value) {
        return value == null ? null : new BigDecimal(value);
    }

    private String uuid() {
        return UUID.randomUUID().toString();
    }
}
