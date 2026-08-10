package com.projeto.cortex.rdos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.projeto.cortex.memory.CortexOperationalMemoryService;

/**
 * Uma linha pela metade não pode custar o RDO inteiro.
 *
 * <p>A recusa de uma linha de serviço é terminal: a fila offline não reenvia um
 * 400. Então a linha em branco clicada por engano, a que veio do desenho no
 * mapa sem quantidade, e a que tem o nome do serviço digitado sem escolher no
 * catálogo — todas derrubavam junto o dia inteiro de apontamento, com as outras
 * linhas, as pessoas e a frota. O apontador via "Sincronização parada" e nada
 * na tela dizia qual campo era o culpado.
 *
 * <p>É contra banco real porque o que se prova aqui é o que a tabela aceita:
 * {@code service_id} anulável desde a V52, {@code unidade_medida} anulável
 * desde a V73, e {@code servico_nome} NOT NULL — que é o que obriga a linha sem
 * catálogo a carregar algum nome.
 */
@Testcontainers(disabledWithoutDocker = true)
class RdoLinhaIncompletaNaoDerrubaORdoIT {

    @Container
    private static final PostgreSQLContainer<?> DATABASE =
            new PostgreSQLContainer<>("postgres:18")
                    .withDatabaseName("cortex_rdo_linha_incompleta_it");

    private static JdbcTemplate jdbc;
    private static TransactionTemplate transactions;

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
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                DATABASE.getJdbcUrl(), DATABASE.getUsername(), DATABASE.getPassword()
        );
        jdbc = new JdbcTemplate(dataSource);
        transactions = new TransactionTemplate(
                new DataSourceTransactionManager(dataSource)
        );
    }

    private RdoOperationalDetailService servico() {
        return new RdoOperationalDetailService(
                jdbc, mock(CortexOperationalMemoryService.class)
        );
    }

    private RdoCreateRequest.ServicoExecutadoItem linha(
            String servicoNome,
            BigDecimal quantidade,
            String unidade
    ) {
        return new RdoCreateRequest.ServicoExecutadoItem(
                UUID.randomUUID().toString(), null, null, servicoNome, null,
                quantidade, unidade, null, null, null, null, "REGISTRADA",
                false, false, null, null, null, null, null
        );
    }

    private List<RdoResponse.ServicoExecutadoItem> gravar(
            String rdoId,
            String obraId,
            LocalDate data,
            List<RdoCreateRequest.ServicoExecutadoItem> linhas
    ) {
        return transactions.execute(status -> servico().substituirDetalhes(
                rdoId, obraId, null, data, "DIURNO", linhas, List.of()
        )).servicosExecutados();
    }

    /**
     * O caso que o desenho do mapa produz: nome sim, número não.
     */
    @Test
    void gravaALinhaQueTemNomeENaoTemQuantidade() {
        String obraId = inserirObra("sem-quantidade");
        LocalDate data = LocalDate.of(2026, 8, 10);
        String rdoId = inserirRdo(obraId, "RDO-0001", data);

        List<RdoResponse.ServicoExecutadoItem> gravadas = gravar(
                rdoId, obraId, data,
                List.of(linha("Fresagem funcional", null, null))
        );

        assertThat(gravadas).hasSize(1);
        assertThat(gravadas.getFirst().servicoNome()).isEqualTo("Fresagem funcional");
        // Quantidade ausente é zero — "aconteceu, não medi" — e não recusa.
        assertThat(gravadas.getFirst().quantidadeExecutada())
                .isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(quantidadeDeLinhas(rdoId)).isEqualTo(1);
    }

    /**
     * Serviço digitado sem escolher no catálogo continua sendo trabalho real.
     * Ele entra como produção sem receita, que é o que ele é.
     */
    @Test
    void gravaALinhaSemServicoDoCatalogo() {
        String obraId = inserirObra("sem-catalogo");
        LocalDate data = LocalDate.of(2026, 8, 11);
        String rdoId = inserirRdo(obraId, "RDO-0002", data);

        gravar(
                rdoId, obraId, data,
                List.of(linha("Limpeza de bueiro", new BigDecimal("12.500"), "UN"))
        );

        assertThat(jdbc.queryForObject(
                "SELECT service_id FROM execucao_servico_rdo WHERE rdo_id = ?",
                String.class, rdoId
        )).isNull();
        assertThat(jdbc.queryForObject(
                "SELECT revenue_amount FROM execucao_servico_rdo WHERE rdo_id = ?",
                BigDecimal.class, rdoId
        )).isNull();
    }

    /** Unidade ausente é falta de unidade, não uma unidade inventada. */
    @Test
    void gravaALinhaSemUnidade() {
        String obraId = inserirObra("sem-unidade");
        LocalDate data = LocalDate.of(2026, 8, 12);
        String rdoId = inserirRdo(obraId, "RDO-0003", data);

        gravar(
                rdoId, obraId, data,
                List.of(linha("Pintura de faixa", new BigDecimal("3.000"), null))
        );

        assertThat(jdbc.queryForObject(
                "SELECT unidade_medida FROM execucao_servico_rdo WHERE rdo_id = ?",
                String.class, rdoId
        )).isNull();
    }

    /**
     * A linha que não afirma nada some sem levar as outras junto — que é o
     * ponto inteiro deste arquivo.
     */
    @Test
    void ignoraALinhaEmBrancoSemPerderAsOutras() {
        String obraId = inserirObra("em-branco");
        LocalDate data = LocalDate.of(2026, 8, 13);
        String rdoId = inserirRdo(obraId, "RDO-0004", data);

        List<RdoResponse.ServicoExecutadoItem> gravadas = gravar(
                rdoId, obraId, data,
                List.of(
                        linha(null, null, null),
                        linha("Fresagem funcional", new BigDecimal("800.000"), "M2")
                )
        );

        assertThat(gravadas).hasSize(1);
        assertThat(gravadas.getFirst().servicoNome()).isEqualTo("Fresagem funcional");
    }

    /**
     * Quantidade sem nome ainda é uma medida que alguém digitou. Perdê-la seria
     * pior do que gravá-la incompleta, e a tabela exige algum nome.
     */
    @Test
    void gravaAMedidaQueChegouSemNome() {
        String obraId = inserirObra("so-numero");
        LocalDate data = LocalDate.of(2026, 8, 14);
        String rdoId = inserirRdo(obraId, "RDO-0005", data);

        List<RdoResponse.ServicoExecutadoItem> gravadas = gravar(
                rdoId, obraId, data,
                List.of(linha(null, new BigDecimal("45.000"), "M"))
        );

        assertThat(gravadas).hasSize(1);
        assertThat(gravadas.getFirst().servicoNome()).isEqualTo("Serviço não identificado");
        assertThat(gravadas.getFirst().quantidadeExecutada())
                .isEqualByComparingTo(new BigDecimal("45.000"));
    }

    /** Negativa não é ausência: aí é engano, e a recusa continua. */
    @Test
    void continuaRecusandoQuantidadeNegativa() {
        String obraId = inserirObra("negativa");
        LocalDate data = LocalDate.of(2026, 8, 15);
        String rdoId = inserirRdo(obraId, "RDO-0006", data);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> gravar(
                rdoId, obraId, data,
                List.of(linha("Fresagem", new BigDecimal("-1.000"), "M2"))
        )).hasMessageContaining("RDO_EXECUTION_QUANTITY_INVALID");
    }

    private int quantidadeDeLinhas(String rdoId) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM execucao_servico_rdo WHERE rdo_id = ?",
                Integer.class, rdoId
        );
    }

    private String inserirObra(String sufixo) {
        String id = UUID.randomUUID().toString();
        jdbc.update(
                """
                INSERT INTO obra (id, codigo_contrato, nome, status)
                VALUES (?, ?, ?, 'ATIVA')
                """,
                id, "CTR-" + id, "Obra " + sufixo
        );
        return id;
    }

    private String inserirRdo(String obraId, String numero, LocalDate data) {
        String id = UUID.randomUUID().toString();
        jdbc.update(
                """
                INSERT INTO rdo (id, obra_id, numero_rdo, data_rdo, status_rdo)
                VALUES (?, ?, ?, ?, 'RASCUNHO')
                """,
                id, obraId, numero, java.sql.Date.valueOf(data)
        );
        return id;
    }
}
