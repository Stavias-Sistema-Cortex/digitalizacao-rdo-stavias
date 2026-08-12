package com.projeto.cortex.rdos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Dois aparelhos offline criam o RDO do mesmo dia; o servidor não deixa colidir.
 *
 * <p>Cada aparelho sugere o mesmo número localmente — os dois estão olhando o
 * mesmo passado. Quem decide é a alocação no servidor: um {@code UPDATE ...
 * RETURNING} sobre {@code rdo_number_sequence}, que trava a linha da obra e
 * entrega números distintos por construção. Este teste executa exatamente esse
 * SQL (o mesmo de {@code RdoService#alocarNumero}), em threads concorrentes,
 * contra um PostgreSQL de verdade — que é o único lugar onde a promessa de
 * atomicidade pode ser conferida.
 *
 * <p>A rede de segurança de baixo também é afirmada: o índice único parcial de
 * {@code (obra_id, numero_sequencial)} recusa a duplicata mesmo que algum
 * caminho futuro tente gravar por fora da alocação.
 */
@Testcontainers(disabledWithoutDocker = true)
class PostgresqlNumeracaoDeDoisAparelhosIT {

    @Container
    private static final PostgreSQLContainer<?> DATABASE =
            new PostgreSQLContainer<>("postgres:18")
                    .withDatabaseName("cortex_numeracao_it");

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

    /** O mesmo par de comandos de {@code RdoService#alocarNumero}. */
    private long alocar(String obraId) {
        jdbc.update(
                """
                INSERT INTO rdo_number_sequence (obra_id, next_value)
                VALUES (?, 1)
                ON CONFLICT (obra_id) DO UPDATE
                SET next_value = GREATEST(
                        rdo_number_sequence.next_value,
                        EXCLUDED.next_value
                    ),
                    updated_at = now()
                """,
                obraId
        );
        Long allocated = jdbc.queryForObject(
                """
                UPDATE rdo_number_sequence
                SET next_value = next_value + 1,
                    updated_at = now()
                WHERE obra_id = ?
                RETURNING next_value - 1
                """,
                Long.class,
                obraId
        );
        return allocated == null ? 1L : allocated;
    }

    @Test
    void dezAparelhosSimultaneosRecebemDezNumerosDistintosESemBuraco()
            throws Exception {
        String obraId = obra("concorrencia");
        int aparelhos = 10;
        CountDownLatch largada = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(aparelhos);
        try {
            List<Future<Long>> alocacoes = executor.invokeAll(
                    java.util.stream.IntStream.range(0, aparelhos)
                            .<Callable<Long>>mapToObj(ignorado -> () -> {
                                largada.await();
                                return alocar(obraId);
                            })
                            .toList()
            );
            largada.countDown();

            Set<Long> numeros = new HashSet<>();
            for (Future<Long> alocacao : alocacoes) {
                numeros.add(alocacao.get());
            }
            // Dez pedidos, dez números — nenhum repetido, nenhum pulado.
            assertThat(numeros)
                    .hasSize(aparelhos)
                    .containsExactlyInAnyOrderElementsOf(
                            java.util.stream.LongStream
                                    .rangeClosed(1, aparelhos)
                                    .boxed()
                                    .toList()
                    );
        } finally {
            executor.shutdownNow();
        }
    }

    /** A trava de baixo: o banco recusa a duplicata gravada por fora. */
    @Test
    void oIndiceUnicoRecusaNumeroSequencialRepetidoNaMesmaObra() {
        String obraId = obra("indice");
        inserirRdo(obraId, "RDO-0001", 1L);

        assertThatThrownBy(() -> inserirRdo(obraId, "RDO-0001-B", 1L))
                .isInstanceOf(DuplicateKeyException.class);
    }

    /**
     * O importado empurra a sequência: depois de um RDO histórico de número
     * alto, a alocação continua dali — é a semente por MAX que faz a criação
     * nunca colidir com o passado importado.
     */
    @Test
    void aSequenciaContinuaDepoisDoNumeroImportado() {
        String obraId = obra("importado");
        inserirRdo(obraId, "RDO-0100", null);

        Long semente = jdbc.queryForObject(
                """
                SELECT COALESCE(
                    MAX(substring(numero_rdo FROM '^RDO-([0-9]{1,18})$')::bigint) + 1,
                    1
                )
                FROM rdo
                WHERE obra_id = ?
                  AND numero_rdo ~ '^RDO-[0-9]{1,18}$'
                """,
                Long.class,
                obraId
        );

        assertThat(semente).isEqualTo(101L);
    }

    private static String obra(String suffix) {
        String obraId = UUID.randomUUID().toString();
        jdbc.update(
                "INSERT INTO obra (id, codigo_contrato, nome) VALUES (?, ?, ?)",
                obraId,
                "NUM-" + suffix,
                "Obra " + suffix
        );
        return obraId;
    }

    private static void inserirRdo(
            String obraId,
            String numero,
            Long sequencial
    ) {
        jdbc.update(
                """
                INSERT INTO rdo (id, obra_id, numero_rdo, numero_sequencial,
                                 data_rdo, status)
                VALUES (?, ?, ?, ?, DATE '2026-08-12', 'RASCUNHO')
                """,
                UUID.randomUUID().toString(),
                obraId,
                numero,
                sequencial
        );
    }
}
