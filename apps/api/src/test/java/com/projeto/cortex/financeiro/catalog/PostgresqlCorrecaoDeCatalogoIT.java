package com.projeto.cortex.financeiro.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.projeto.cortex.obras.ObraOperabilityGuard;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Corrigir o que foi digitado errado, contra um PostgreSQL de verdade.
 *
 * <p>O catálogo só sabia acrescentar. Um serviço cadastrado com o nome trocado
 * não tinha conserto — sobrava excluir e cadastrar de novo, o que troca o
 * identificador que os RDOs, os preços e as medições já citam — e um preço com
 * um zero a mais só podia ser trocado por substituição, que grava no histórico
 * uma revisão contratual que nunca aconteceu.
 *
 * <p>A fronteira entre corrigir e reescrever o passado mora no banco, num
 * gatilho, e não no serviço: entre ler "ninguém usou este preço ainda" e gravar
 * a correção há uma janela em que outro aparelho pode ter validado a execução
 * que o usa. Estes testes exercitam justamente essa fronteira, que nenhum teste
 * com repositório dublê alcança.
 */
@Testcontainers(disabledWithoutDocker = true)
class PostgresqlCorrecaoDeCatalogoIT {

    private static final Instant NOW = Instant.parse("2026-08-10T12:00:00Z");
    private static final LocalDate VIGENCIA = LocalDate.of(2026, 1, 1);

    @Container
    private static final PostgreSQLContainer<?> DATABASE =
            new PostgreSQLContainer<>("postgres:18")
                    .withDatabaseName("cortex_catalog_correction_it");

    private static JdbcTemplate jdbc;
    private static TransactionTemplate transactions;

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
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                DATABASE.getJdbcUrl(), DATABASE.getUsername(), DATABASE.getPassword()
        );
        jdbc = new JdbcTemplate(dataSource);
        transactions = new TransactionTemplate(
                new DataSourceTransactionManager(dataSource)
        );
    }

    /*
     * O ponto do pedido: consertar o nome sem trocar o identificador. Excluir e
     * recadastrar resolveria a tela e quebraria tudo o que já aponta para o
     * serviço.
     */
    @Test
    void corrigeOCadastroSemTrocarOIdentificador() {
        String obra = insertWorksite("CORRIGE-NOME");
        String ator = insertActor("Dono do catálogo");
        ServicePriceCatalogService servico = service();
        ServiceCatalogEntry criado = inTx(() -> servico.createService(
                obra, ator, new CreateServiceCommand(
                        mutation(), "FREASGEM." + sufixo(), "Freasgem", null
                )
        ));

        ServiceCatalogEntry corrigido = inTx(() -> servico.atualizarServico(
                obra, ator, criado.id(), new UpdateServiceCommand(
                        mutation(), "fresagem." + sufixo(), "Fresagem",
                        "Fresagem de revestimento asfáltico"
                )
        ));

        assertThat(corrigido.id()).isEqualTo(criado.id());
        assertThat(corrigido.name()).isEqualTo("Fresagem");
        assertThat(corrigido.code()).startsWith("FRESAGEM.");
        assertThat(corrigido.status()).isEqualTo("ACTIVE");
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM catalogo_servico WHERE id = ?",
                Integer.class, criado.id()
        )).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                """
                SELECT count(*) FROM service_catalog_mutation
                WHERE entity_id = ? AND operation_type = 'SERVICE_UPDATED'
                """,
                Integer.class, criado.id()
        )).isEqualTo(1);
    }

    /* Dois serviços vivos não podem dividir um código, e corrigir não é uma
       porta lateral para isso. */
    @Test
    void recusaOCodigoQueJaPertenceAOutroServicoVivo() {
        String obra = insertWorksite("CODIGO-OCUPADO");
        String ator = insertActor("Dono do código");
        ServicePriceCatalogService servico = service();
        String ocupado = "OCUPADO." + sufixo();
        inTx(() -> servico.createService(
                obra, ator, new CreateServiceCommand(
                        mutation(), ocupado, "Serviço ocupado", null
                )
        ));
        ServiceCatalogEntry outro = inTx(() -> servico.createService(
                obra, ator, new CreateServiceCommand(
                        mutation(), "LIVRE." + sufixo(), "Serviço livre", null
                )
        ));

        assertThatThrownBy(() -> inTx(() -> servico.atualizarServico(
                obra, ator, outro.id(), new UpdateServiceCommand(
                        mutation(), ocupado, "Serviço livre", null
                )
        ))).isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("SERVICE_CATALOG_CODE_EXISTS");
    }

    @Test
    void corrigeOPrecoNoLugarSemCriarOutraVersao() {
        String obra = insertWorksite("CORRIGE-PRECO");
        String ator = insertActor("Dono do preço");
        ServicePriceCatalogService servico = service();
        ServiceCatalogEntry catalogo = inTx(() -> servico.createService(
                obra, ator, new CreateServiceCommand(
                        mutation(), "PRECO." + sufixo(), "Serviço com preço", null
                )
        ));
        ServicePriceVersion preco = inTx(() -> servico.createPrice(
                obra, ator, catalogo.id(),
                precoCommand("5000.0000", VIGENCIA, null)
        ));

        ServicePriceVersion corrigido = inTx(() -> servico.atualizarPreco(
                obra, ator, preco.id(), new UpdateServicePriceCommand(
                        mutation(), new BigDecimal("50.0000"),
                        new BigDecimal("1200.000"), VIGENCIA, null,
                        "CONTRATO_MEDIDO"
                )
        ));

        assertThat(corrigido.id()).isEqualTo(preco.id());
        assertThat(corrigido.version()).isEqualTo(preco.version());
        assertThat(corrigido.unit()).isEqualTo(preco.unit());
        assertThat(corrigido.unitPrice()).isEqualByComparingTo("50.0000");
        assertThat(corrigido.contractedQuantity()).isEqualByComparingTo("1200.000");
        assertThat(corrigido.status()).isEqualTo("ACTIVE");
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM service_price_version WHERE service_id = ?",
                Integer.class, catalogo.id()
        )).isEqualTo(1);
    }

    /*
     * A fronteira que só o banco pode guardar. A partir do momento em que a
     * execução copiou o valor para dentro de si, o preço deixa de ser cadastro e
     * passa a ser a prova do quanto aquela execução vale.
     */
    @Test
    void recusaACorrecaoDepoisQueUmaExecucaoCitouOPreco() {
        String obra = insertWorksite("PRECO-USADO");
        String ator = insertActor("Dono do preço usado");
        ServicePriceCatalogService servico = service();
        ServiceCatalogEntry catalogo = inTx(() -> servico.createService(
                obra, ator, new CreateServiceCommand(
                        mutation(), "USADO." + sufixo(), "Serviço executado", null
                )
        ));
        ServicePriceVersion preco = inTx(() -> servico.createPrice(
                obra, ator, catalogo.id(),
                precoCommand("125.0000", VIGENCIA, null)
        ));
        insertAcceptedExecution(obra, catalogo.id(), preco.id());

        assertThatThrownBy(() -> inTx(() -> servico.atualizarPreco(
                obra, ator, preco.id(), new UpdateServicePriceCommand(
                        mutation(), new BigDecimal("12.5000"),
                        new BigDecimal("800.000"), VIGENCIA, null,
                        "CONTRATO_MEDIDO"
                )
        ))).isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("SERVICE_PRICE_ALREADY_USED");

        assertThat(jdbc.queryForObject(
                "SELECT valor_unitario FROM service_price_version WHERE id = ?",
                BigDecimal.class, preco.id()
        )).isEqualByComparingTo("125.0000");
    }

    /* Versão substituída é elo de uma corrente: corrigi-la deslocaria a
       vigência do sucessor sem que ninguém tivesse pedido. */
    @Test
    void recusaACorrecaoDaVersaoJaSubstituida() {
        String obra = insertWorksite("PRECO-SUBSTITUIDO");
        String ator = insertActor("Dono da substituição");
        ServicePriceCatalogService servico = service();
        ServiceCatalogEntry catalogo = inTx(() -> servico.createService(
                obra, ator, new CreateServiceCommand(
                        mutation(), "SUBST." + sufixo(), "Serviço revisado", null
                )
        ));
        ServicePriceVersion primeira = inTx(() -> servico.createPrice(
                obra, ator, catalogo.id(),
                precoCommand("125.0000", VIGENCIA, null)
        ));
        inTx(() -> servico.supersedePrice(
                obra, ator, primeira.id(), new SupersedeServicePriceCommand(
                        mutation(), new BigDecimal("130.0000"),
                        new BigDecimal("800.000"), VIGENCIA.plusMonths(6),
                        null, "ADITIVO_01"
                )
        ));

        assertThatThrownBy(() -> inTx(() -> servico.atualizarPreco(
                obra, ator, primeira.id(), new UpdateServicePriceCommand(
                        mutation(), new BigDecimal("12.5000"),
                        new BigDecimal("800.000"), VIGENCIA, null,
                        "CONTRATO_MEDIDO"
                )
        ))).isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("SERVICE_PRICE_ALREADY_TERMINATED");
    }

    /*
     * A validação de vigência só existia no INSERT, porque até aqui a tabela era
     * somente-acrescentar. Sem a guarda no UPDATE, corrigir seria a porta
     * lateral para dois preços válidos no mesmo dia.
     */
    @Test
    void recusaACorrecaoQueSobrepoeAVigenciaDeOutraVersao() {
        String obra = insertWorksite("VIGENCIA-SOBREPOSTA");
        String ator = insertActor("Dono da vigência");
        ServicePriceCatalogService servico = service();
        ServiceCatalogEntry catalogo = inTx(() -> servico.createService(
                obra, ator, new CreateServiceCommand(
                        mutation(), "VIG." + sufixo(), "Serviço com duas faixas",
                        null
                )
        ));
        ServicePriceVersion primeira = inTx(() -> servico.createPrice(
                obra, ator, catalogo.id(),
                precoCommand("125.0000", VIGENCIA, VIGENCIA.plusMonths(2))
        ));
        inTx(() -> servico.createPrice(
                obra, ator, catalogo.id(),
                precoCommand(
                        "130.0000", VIGENCIA.plusMonths(3), VIGENCIA.plusMonths(6)
                )
        ));

        assertThatThrownBy(() -> inTx(() -> servico.atualizarPreco(
                obra, ator, primeira.id(), new UpdateServicePriceCommand(
                        mutation(), new BigDecimal("125.0000"),
                        new BigDecimal("800.000"), VIGENCIA,
                        VIGENCIA.plusMonths(4), "CONTRATO_MEDIDO"
                )
        ))).isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("SERVICE_PRICE_VALIDITY_OVERLAP");
    }

    /*
     * A fila offline reenvia o mesmo pedido quando a rede vacila. O segundo
     * envio devolve o resultado do primeiro; e o recibo confere o conteúdo, para
     * que duas correções diferentes com o mesmo identificador sejam recusadas em
     * vez de se sobrescreverem.
     */
    @Test
    void repeteACorrecaoPeloReciboEmVezDeAplicarDuasVezes() {
        String obra = insertWorksite("RECIBO-CORRECAO");
        String ator = insertActor("Dono do recibo");
        ServicePriceCatalogService servico = service();
        ServiceCatalogEntry catalogo = inTx(() -> servico.createService(
                obra, ator, new CreateServiceCommand(
                        mutation(), "RECIBO." + sufixo(), "Serviço do recibo", null
                )
        ));
        String mesmaMutacao = mutation();
        String codigoCorrigido = "CORRIGIDO." + sufixo();

        ServiceCatalogEntry primeira = inTx(() -> servico.atualizarServico(
                obra, ator, catalogo.id(), new UpdateServiceCommand(
                        mesmaMutacao, codigoCorrigido, "Serviço corrigido", null
                )
        ));
        ServiceCatalogEntry repetida = inTx(() -> servico.atualizarServico(
                obra, ator, catalogo.id(), new UpdateServiceCommand(
                        mesmaMutacao, codigoCorrigido, "Serviço corrigido", null
                )
        ));

        assertThat(repetida.name()).isEqualTo(primeira.name());
        assertThat(jdbc.queryForObject(
                """
                SELECT count(*) FROM service_catalog_mutation
                WHERE ator_id = ? AND client_mutation_id = ?
                """,
                Integer.class, ator, mesmaMutacao
        )).isEqualTo(1);

        assertThatThrownBy(() -> inTx(() -> servico.atualizarServico(
                obra, ator, catalogo.id(), new UpdateServiceCommand(
                        mesmaMutacao, codigoCorrigido, "Outro nome qualquer", null
                )
        ))).isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("SERVICE_CATALOG_IDEMPOTENCY_CONFLICT");
    }

    /*
     * Uma execução aceita de verdade: é ela que congela o valor unitário e o
     * total, e é por existir que a correção deixa de ser possível. Os gatilhos
     * de receita exigem preço vigente na data, valor idêntico ao do cadastro,
     * total conferido e o evento canônico completo — montar menos que isso não
     * provaria nada, porque o banco recusaria a própria montagem.
     */
    private static void insertAcceptedExecution(
            String obra,
            String servicoId,
            String precoId
    ) {
        String rdo = id();
        String execucao = id();
        String evidencia = id();
        String evento = id();
        LocalDate data = VIGENCIA.plusDays(30);
        jdbc.update(
                """
                INSERT INTO rdo (id, obra_id, numero_rdo, data_rdo)
                VALUES (?, ?, ?, ?)
                """,
                rdo, obra, "RDO-" + rdo.substring(0, 8), data
        );
        jdbc.update("""
                INSERT INTO cortex_evento_operacional (
                    id, commit_seq, tipo_entidade, entidade_id, obra_id, rdo_id,
                    tipo_evento, fonte, origem, sync_status, schema_version,
                    entidades_relacionadas_json, estado_novo_json,
                    payload_json, ocorrido_em
                ) VALUES (?,
                          (
                              SELECT COALESCE(max(commit_seq), 0) + 1
                              FROM cortex_evento_operacional
                          ),
                          'RDO_EXECUTION', ?, ?, ?,
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
                              'status', 'ACCEPTED',
                              'rdoId', ?,
                              'obraId', ?,
                              'serviceId', ?,
                              'priceVersionId', ?,
                              'revenueEvidenceId', ?,
                              'acceptedQuantity', 10.000,
                              'unit', 'M2',
                              'unitPrice', 125.0000,
                              'currency', 'BRL',
                              'revenue', 1250.00
                          ),
                          now())
                """, evento, execucao, obra, rdo,
                rdo, obra, servicoId, precoId, evidencia,
                evidencia,
                rdo, obra, servicoId, precoId, evidencia);
        jdbc.update("""
                INSERT INTO execucao_servico_rdo (
                    id, rdo_id, obra_id, servico_nome, service_id,
                    price_version_id, quantidade_executada, unidade_medida,
                    data_execucao, status_validacao, estado_receita, retrabalho,
                    producao_rejeitada, fonte, chave_execucao,
                    unit_price_snapshot, currency, revenue_amount,
                    revenue_coverage_code, revenue_evidence_id,
                    revenue_event_id, accepted_at
                ) VALUES (?, ?, ?, 'Serviço executado', ?, ?, 10.000, 'M2', ?,
                          'VALIDADA', 'RECEITA_MEDIDA', FALSE, FALSE,
                          'CORRECAO_IT', ?, 125.0000, 'BRL', 1250.00,
                          'ACCEPTED_EXACT', ?, ?, now())
                """, execucao, rdo, obra, servicoId, precoId, data,
                execucao.replace("-", "").repeat(2).substring(0, 64),
                evidencia, evento);
    }

    private static ServicePriceCatalogService service() {
        return new ServicePriceCatalogService(
                new PostgresqlServicePriceCatalogRepository(jdbc),
                org.mockito.Mockito.mock(ServiceCatalogOntologyPublisher.class),
                org.mockito.Mockito.mock(ObraOperabilityGuard.class),
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
    }

    private static CreateServicePriceCommand precoCommand(
            String valor,
            LocalDate de,
            LocalDate ate
    ) {
        return new CreateServicePriceCommand(
                mutation(), "M2", "BRL", new BigDecimal(valor),
                new BigDecimal("800.000"), de, ate, "CONTRATO_MEDIDO"
        );
    }

    private static String insertWorksite(String sufixo) {
        String id = id();
        jdbc.update(
                "INSERT INTO obra (id, codigo_contrato, nome) VALUES (?, ?, ?)",
                id, "CTR-" + sufixo + "-" + id.substring(0, 8), "Obra " + sufixo
        );
        return id;
    }

    private static String insertActor(String nome) {
        String id = id();
        jdbc.update("""
                INSERT INTO colaborador (
                    id, banco_origem, tabela_origem, pk_origem, nome, papel_acesso
                ) VALUES (?, 'fixture', 'colaborador', ?, ?, 'ALFA')
                """, id, id, nome);
        return id;
    }

    private static <T> T inTx(java.util.concurrent.Callable<T> callback) {
        return transactions.execute(status -> {
            try {
                return callback.call();
            } catch (RuntimeException exception) {
                throw exception;
            } catch (Exception exception) {
                throw new IllegalStateException(exception);
            }
        });
    }

    private static String mutation() {
        return "mut-" + id();
    }

    private static String sufixo() {
        return id().substring(0, 8).toUpperCase(java.util.Locale.ROOT);
    }

    private static String id() {
        return UUID.randomUUID().toString();
    }
}
