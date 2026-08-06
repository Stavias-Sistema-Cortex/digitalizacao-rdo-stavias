package com.projeto.cortex.rdos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.projeto.cortex.auth.CurrentUserService;

/**
 * Apagar RDO contra PostgreSQL de verdade.
 *
 * <p>O que precisa ser provado aqui é o grafo, não o CRUD. São três amarras que
 * bloqueiam o DELETE e que só aparecem contra banco real: a auto-referência de
 * {@code rdo.previous_rdo_id}, a auto-referência de
 * {@code rdo_mao_obra.origem_item_id} e o laço circular entre {@code rdo} e o
 * recibo do contexto de criação.
 *
 * <p>A recusa por medição já virada em dinheiro fica no teste unitário: plantar
 * uma evidência de receita de verdade exigiria serviço, versão de preço vigente
 * e evento — fixture fundo demais para provar algo que acontece antes de
 * qualquer DELETE.
 */
@Testcontainers(disabledWithoutDocker = true)
class RdoDeletionIT {

    @Container
    private static final PostgreSQLContainer<?> DATABASE =
            new PostgreSQLContainer<>("postgres:18")
                    .withDatabaseName("cortex_rdo_deletion_it");

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

    /**
     * O caso que o console de banco erra: o RDO carrega mão de obra herdada,
     * que aponta para a linha equivalente do RDO anterior. Sem desligar essa
     * auto-referência o DELETE trava na própria tabela.
     */
    @Test
    void apagaRdoComMaoDeObraHerdadaDoAnterior() {
        String obraId = inserirObra("heranca");
        String ontem = inserirRdo(obraId, "RDO-0001", LocalDate.of(2026, 7, 20), "RASCUNHO", null);
        String hoje = inserirRdo(obraId, "RDO-0002", LocalDate.of(2026, 7, 21), "RASCUNHO", ontem);
        String itemDeOntem = inserirMaoDeObra(ontem, "ADAO LEITE", null);
        inserirMaoDeObra(hoje, "ADAO LEITE", itemDeOntem);

        RdoDeletionResponse apagado = servico("alfa").apagar(hoje);

        assertThat(apagado.rdoId()).isEqualTo(hoje);
        assertThat(existeRdo(hoje)).isFalse();
        // O de ontem fica: apagar um não arrasta o outro.
        assertThat(existeRdo(ontem)).isTrue();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM rdo_mao_obra WHERE rdo_id = ?",
                Integer.class, hoje
        )).isZero();
    }

    /**
     * A ordem importa e a mensagem tem que dizer qual é. Apagar o de ontem
     * primeiro deixaria o de hoje declarando uma origem que não existe mais.
     */
    @Test
    void recusaApagarRdoQueServeDeBaseParaOutro() {
        String obraId = inserirObra("base");
        String ontem = inserirRdo(obraId, "RDO-0010", LocalDate.of(2026, 7, 20), "RASCUNHO", null);
        inserirRdo(obraId, "RDO-0011", LocalDate.of(2026, 7, 21), "RASCUNHO", ontem);

        assertThatThrownBy(() -> servico("alfa").apagar(ontem))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("é a base de")
                .hasMessageContaining("RDO-0011");

        assertThat(existeRdo(ontem)).isTrue();
    }

    /** O laço circular: o RDO aponta para o recibo e o recibo aponta de volta. */
    @Test
    void apagaRdoAmarradoAoReciboDoContextoDeCriacao() {
        String obraId = inserirObra("recibo");
        String rdoId = inserirRdo(obraId, "RDO-0020", LocalDate.of(2026, 7, 22), "RASCUNHO", null);
        long receipt = inserirSnapshot(obraId, LocalDate.of(2026, 7, 22));
        jdbc.update(
                "UPDATE rdo SET creation_context_version = ? WHERE id = ?",
                receipt, rdoId
        );

        servico("alfa").apagar(rdoId);

        assertThat(existeRdo(rdoId)).isFalse();
    }

    /**
     * Rascunho é papel de rascunho: quem monta desfaz. Enviado é registro
     * entregue, e desfazer entrega é decisão de quem responde pela obra.
     */
    @Test
    void rascunhoQualquerUmDaObraApagaEEnviadoExigeAlfa() {
        String obraId = inserirObra("papeis");
        String rascunho = inserirRdo(obraId, "RDO-0030", LocalDate.of(2026, 7, 23), "RASCUNHO", null);
        String enviado = inserirRdo(obraId, "RDO-0031", LocalDate.of(2026, 7, 24), "ENVIADO", null);

        RdoDeletionService semSerAlfa = servicoQueRecusaAlfa();
        semSerAlfa.apagar(rascunho);
        assertThat(existeRdo(rascunho)).isFalse();

        assertThatThrownBy(() -> servicoQueRecusaAlfa().apagar(enviado))
                .isInstanceOf(ResponseStatusException.class);
        assertThat(existeRdo(enviado)).isTrue();

        servico("alfa").apagar(enviado);
        assertThat(existeRdo(enviado)).isFalse();
    }

    @Test
    void recusaRdoInexistente() {
        assertThatThrownBy(() -> servico("alfa").apagar(UUID.randomUUID().toString()))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("não encontrado");
    }

    // ---------------------------------------------------------------

    private RdoDeletionService servico(String actorId) {
        CurrentUserService usuario = mock(CurrentUserService.class);
        when(usuario.requireUserId()).thenReturn(actorId);
        return new RdoDeletionService(
                jdbc, usuario, mock(RdoMemoryPublisher.class)
        );
    }

    private RdoDeletionService servicoQueRecusaAlfa() {
        CurrentUserService usuario = mock(CurrentUserService.class);
        when(usuario.requireUserId()).thenReturn("beta");
        doThrow(new ResponseStatusException(HttpStatus.FORBIDDEN, "Apenas ALFA."))
                .when(usuario).requireAlfa();
        return new RdoDeletionService(
                jdbc, usuario, mock(RdoMemoryPublisher.class)
        );
    }

    private boolean existeRdo(String rdoId) {
        Integer total = jdbc.queryForObject(
                "SELECT count(*) FROM rdo WHERE id = ?", Integer.class, rdoId
        );
        return total != null && total > 0;
    }

    private String inserirObra(String sufixo) {
        String obraId = UUID.randomUUID().toString();
        transactions.executeWithoutResult(status -> jdbc.update(
                """
                INSERT INTO obra (id, codigo_contrato, codigo_cw, nome)
                VALUES (?, ?, ?, ?)
                """,
                obraId,
                "CTR-" + sufixo + "-" + obraId.substring(0, 8),
                "CW-" + sufixo + "-" + obraId.substring(0, 8),
                "Obra " + sufixo
        ));
        return obraId;
    }

    private String inserirRdo(
            String obraId,
            String numero,
            LocalDate data,
            String status,
            String anteriorId
    ) {
        String rdoId = UUID.randomUUID().toString();
        transactions.executeWithoutResult(state -> jdbc.update(
                """
                INSERT INTO rdo (id, obra_id, numero_rdo, data_rdo, status, previous_rdo_id)
                VALUES (?, ?, ?, ?, ?, ?)
                """,
                rdoId, obraId, numero, data, status, anteriorId
        ));
        return rdoId;
    }

    private String inserirMaoDeObra(String rdoId, String nome, String origemItemId) {
        String itemId = UUID.randomUUID().toString();
        transactions.executeWithoutResult(state -> jdbc.update(
                """
                INSERT INTO rdo_mao_obra (id, rdo_id, nome_colaborador, origem_item_id)
                VALUES (?, ?, ?, ?)
                """,
                itemId, rdoId, nome, origemItemId
        ));
        return itemId;
    }

    private long inserirSnapshot(String obraId, LocalDate data) {
        return transactions.execute(state -> {
            jdbc.update(
                    """
                    INSERT INTO rdo_creation_context_snapshot (
                        snapshot_id, obra_id, selected_date, source_version,
                        payload_hash, coverage_json, generated_at, stale_after
                    ) VALUES (?, ?, ?, 1, repeat('a', 64), '{}'::jsonb, now(), now() + interval '1 day')
                    """,
                    UUID.randomUUID().toString(), obraId, data
            );
            return jdbc.queryForObject(
                    """
                    SELECT receipt_version FROM rdo_creation_context_snapshot
                    WHERE obra_id = ? ORDER BY receipt_version DESC LIMIT 1
                    """,
                    Long.class, obraId
            );
        });
    }
}
