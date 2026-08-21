package com.projeto.cortex.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.projeto.cortex.memory.CortexOperationalMemoryService;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Muitas obras, muitos RDOs, todos subindo ao mesmo tempo — o servidor não
 * pode perder, duplicar nem pular nada.
 *
 * <p>O coração da sincronização é o log de eventos com {@code commit_seq}
 * alocado por um {@code UPDATE ... RETURNING} numa linha única, dentro da
 * mesma transação que grava o evento. Essa construção é o que garante duas
 * promessas de que o pull por cursor depende:</p>
 *
 * <ul>
 *   <li><strong>sem buraco:</strong> quem segura o {@code commit_seq} N trava a
 *   linha até o próprio commit, então nenhuma transação consegue alocar N+1 e
 *   ficar visível antes de N existir. O conjunto visível de eventos é sempre um
 *   prefixo completo — e é por isso que {@code WHERE commit_seq > cursor} nunca
 *   perde um evento que commitou "atrasado";</li>
 *   <li><strong>sem duplicata:</strong> a mutação repetida — o reenvio após um
 *   timeout, a mesma fila subindo por dois dispositivos do mesmo dono — morre
 *   nos índices únicos de {@code sync_mutacao_cliente}, esperando o commit de
 *   quem chegou primeiro, e o domínio aplica uma vez só.</li>
 * </ul>
 *
 * <p>Promessa de atomicidade só se confere contra um PostgreSQL de verdade,
 * em threads reais. Roda no Testcontainers quando há Docker; sem Docker, um
 * cluster local pode ser apontado por {@code CORTEX_SYNC_IT_JDBC_URL} (com
 * {@code _USER} e {@code _PASSWORD}) — é como esta simulação roda em máquinas
 * de desenvolvimento sem daemon.</p>
 */
@Timeout(value = 5, unit = TimeUnit.MINUTES)
class PostgresqlSincronizacaoConcorrenteIT {

    private static PostgreSQLContainer<?> container;
    private static DriverManagerDataSource dataSource;
    private static JdbcTemplate jdbc;
    private static TransactionTemplate transacao;
    private static CortexOperationalMemoryService memoria;

    @BeforeAll
    static void boot() {
        String externalUrl = System.getenv("CORTEX_SYNC_IT_JDBC_URL");
        String url;
        String user;
        String password;
        if (externalUrl != null && !externalUrl.isBlank()) {
            url = externalUrl;
            user = System.getenv().getOrDefault("CORTEX_SYNC_IT_JDBC_USER", "postgres");
            password = System.getenv().getOrDefault("CORTEX_SYNC_IT_JDBC_PASSWORD", "postgres");
        } else {
            assumeTrue(
                    dockerDisponivel(),
                    "Sem Docker e sem CORTEX_SYNC_IT_JDBC_URL: simulação pulada."
            );
            container = new PostgreSQLContainer<>("postgres:18")
                    .withDatabaseName("cortex_sync_concorrente_it");
            container.start();
            url = container.getJdbcUrl();
            user = container.getUsername();
            password = container.getPassword();
        }

        Flyway.configure()
                .dataSource(url, user, password)
                .locations("classpath:db/migration-postgresql")
                .load()
                .migrate();

        dataSource = new DriverManagerDataSource(url, user, password);
        jdbc = new JdbcTemplate(dataSource);
        transacao = new TransactionTemplate(
                new DataSourceTransactionManager(dataSource)
        );
        // O serviço real, com as mesmas instruções SQL da produção. O
        // TransactionTemplate reproduz o @Transactional dos chamadores — sem
        // ele, cada comando commitaria sozinho e o teste estaria conferindo
        // uma semântica que não é a do servidor.
        memoria = new CortexOperationalMemoryService(
                jdbc,
                new com.fasterxml.jackson.databind.ObjectMapper(),
                event -> { }
        );
    }

    @AfterAll
    static void stop() {
        if (container != null) {
            container.stop();
        }
    }

    private static boolean dockerDisponivel() {
        try {
            return DockerClientFactory.instance().isDockerAvailable();
        } catch (RuntimeException unavailable) {
            return false;
        }
    }

    /**
     * Rajada multi-obra: 6 obras, 4 apontadores por obra, 5 eventos cada —
     * 120 transações reais disputando a mesma sequência.
     *
     * <p>É o retrato do fim de dia com várias frentes ativas. As promessas
     * conferidas: nenhum evento perdido, nenhum {@code commit_seq} repetido ou
     * pulado, e a versão de cada entidade em {@code cortex_estado_entidade}
     * contando exatamente os eventos dela — o upsert {@code ON CONFLICT ...
     * versao_entidade + 1} não pode perder incremento sob disputa.</p>
     */
    @Test
    void rajadaMultiObraNaoPerdeNemDuplicaNemPulaEvento() throws Exception {
        long antes = maxCommitSeq();
        int obras = 6;
        int escritoresPorObra = 4;
        int eventosPorEscritor = 5;
        int total = obras * escritoresPorObra * eventosPorEscritor;

        List<String> obraIds = new ArrayList<>();
        for (int i = 0; i < obras; i++) {
            obraIds.add(criarObra("rajada-" + i));
        }

        CountDownLatch largada = new CountDownLatch(1);
        ExecutorService executor =
                Executors.newFixedThreadPool(obras * escritoresPorObra);
        try {
            List<Future<?>> escritores = new ArrayList<>();
            for (String obraId : obraIds) {
                for (int e = 0; e < escritoresPorObra; e++) {
                    String rdoId = UUID.randomUUID().toString();
                    escritores.add(executor.submit(() -> {
                        largada.await();
                        for (int n = 0; n < eventosPorEscritor; n++) {
                            int seq = n;
                            transacao.executeWithoutResult(status ->
                                    memoria.registrarEvento(
                                            "RDO",
                                            rdoId,
                                            "RDO_SIMULADO",
                                            "SIMULACAO_IT",
                                            obraId,
                                            Map.of("iteracao", seq)
                                    )
                            );
                        }
                        return null;
                    }));
                }
            }
            largada.countDown();
            for (Future<?> escritor : escritores) {
                escritor.get();
            }
        } finally {
            executor.shutdownNow();
        }

        List<Long> commitSeqs = jdbc.queryForList(
                """
                SELECT commit_seq FROM cortex_evento_operacional
                WHERE commit_seq > ? ORDER BY commit_seq
                """,
                Long.class,
                antes
        );
        assertThat(commitSeqs).hasSize(total);
        // Contíguo e sem repetição: exatamente (antes, antes + total].
        assertThat(new HashSet<>(commitSeqs)).hasSize(total);
        assertThat(commitSeqs.get(0)).isEqualTo(antes + 1);
        assertThat(commitSeqs.get(total - 1)).isEqualTo(antes + total);

        // Cada RDO simulado: versão da entidade == quantidade de eventos, e o
        // último evento apontado é mesmo o maior.
        List<Map<String, Object>> estados = jdbc.queryForList(
                """
                SELECT ee.entidade_id,
                       ee.versao_entidade,
                       ee.ultimo_evento_seq,
                       contagem.eventos,
                       contagem.maior_seq
                FROM cortex_estado_entidade ee
                JOIN (
                    SELECT entidade_id,
                           COUNT(*) AS eventos,
                           MAX(sequencia) AS maior_seq
                    FROM cortex_evento_operacional
                    WHERE fonte = 'SIMULACAO_IT' AND commit_seq > ?
                    GROUP BY entidade_id
                ) contagem ON contagem.entidade_id = ee.entidade_id
                WHERE ee.tipo_entidade = 'RDO'
                """,
                antes
        );
        assertThat(estados).hasSize(obras * escritoresPorObra);
        for (Map<String, Object> estado : estados) {
            assertThat((Number) estado.get("versao_entidade"))
                    .as("versão da entidade %s", estado.get("entidade_id"))
                    .extracting(Number::longValue)
                    .isEqualTo(((Number) estado.get("eventos")).longValue());
            assertThat((Number) estado.get("ultimo_evento_seq"))
                    .extracting(Number::longValue)
                    .isEqualTo(((Number) estado.get("maior_seq")).longValue());
        }
    }

    /**
     * Um aparelho puxando por cursor durante a rajada nunca vê buraco.
     *
     * <p>É a mesma consulta do {@code SyncService#pull} — {@code WHERE
     * commit_seq > cursor ORDER BY commit_seq LIMIT n} — executada em paralelo
     * com escritores reais. Se qualquer transação com sequência menor pudesse
     * commitar depois de uma maior ficar visível, o leitor avançaria o cursor
     * por cima dela e o evento nunca chegaria àquele aparelho. Cada janela
     * lida precisa começar exatamente em {@code cursor + 1} — prefixo
     * completo, sem exceção.</p>
     */
    @Test
    void leitorPorCursorDuranteARajadaNuncaVeBuraco() throws Exception {
        long antes = maxCommitSeq();
        int escritores = 8;
        int eventosPorEscritor = 12;
        int total = escritores * eventosPorEscritor;
        String obraId = criarObra("cursor");

        CountDownLatch largada = new CountDownLatch(1);
        AtomicBoolean escrevendo = new AtomicBoolean(true);
        ConcurrentLinkedQueue<String> violacoes = new ConcurrentLinkedQueue<>();
        List<Long> vistos = new ArrayList<>();
        ExecutorService executor = Executors.newFixedThreadPool(escritores + 1);
        try {
            List<Future<?>> tarefas = new ArrayList<>();
            for (int e = 0; e < escritores; e++) {
                String tarefaId = UUID.randomUUID().toString();
                tarefas.add(executor.submit(() -> {
                    largada.await();
                    for (int n = 0; n < eventosPorEscritor; n++) {
                        int seq = n;
                        transacao.executeWithoutResult(status ->
                                memoria.registrarEvento(
                                        "TAREFA",
                                        tarefaId,
                                        "TAREFA_SIMULADA",
                                        "SIMULACAO_CURSOR_IT",
                                        obraId,
                                        Map.of("iteracao", seq)
                                )
                        );
                    }
                    return null;
                }));
            }

            Future<?> leitor = executor.submit(() -> {
                largada.await();
                long cursor = antes;
                while (!Thread.currentThread().isInterrupted()) {
                    List<Long> janela = jdbc.queryForList(
                            """
                            SELECT commit_seq FROM cortex_evento_operacional
                            WHERE commit_seq > ?
                            ORDER BY commit_seq
                            LIMIT 7
                            """,
                            Long.class,
                            cursor
                    );
                    for (Long visto : janela) {
                        if (visto != cursor + 1) {
                            violacoes.add(
                                    "cursor " + cursor + " pulou para " + visto
                            );
                        }
                        cursor = visto;
                        vistos.add(visto);
                    }
                    if (janela.isEmpty() && !escrevendo.get()) {
                        // Uma última varredura já aconteceu com tudo commitado.
                        if (cursor >= antes + total) {
                            return null;
                        }
                    }
                }
                return null;
            });

            largada.countDown();
            for (Future<?> tarefa : tarefas) {
                tarefa.get();
            }
            escrevendo.set(false);
            leitor.get();
        } finally {
            executor.shutdownNow();
        }

        assertThat(violacoes)
                .as("toda janela do pull deve começar em cursor + 1")
                .isEmpty();
        assertThat(vistos).hasSize(total);
        assertThat(vistos.get(total - 1)).isEqualTo(antes + total);
    }

    /**
     * A mesma mutação chegando duas vezes ao mesmo tempo aplica o domínio uma
     * vez só.
     *
     * <p>É o reenvio clássico: o aparelho esgota o timeout enquanto o servidor
     * ainda aplica, e reenvia — ou a fila do mesmo dono sobe por dois
     * dispositivos. O servidor grava a linha PENDENTE antes de tocar o domínio
     * ({@code processarMutacaoAplicavel}), e é essa ordem que este teste
     * afirma: o segundo INSERT espera o commit do primeiro no índice único
     * {@code uq_sync_mutation_owner_client_v13} e morre em
     * {@code DuplicateKeyException} sem ter aplicado nada.</p>
     */
    @Test
    void duplicataConcorrenteDoMesmoDonoAplicaODominioUmaVezSo()
            throws Exception {
        String ownerId = criarColaborador();
        String dispositivoA = criarDispositivo(ownerId);
        String dispositivoB = criarDispositivo(ownerId);
        String obraId = criarObra("duplicata");
        String clientMutationId = "cmid-" + UUID.randomUUID();
        jdbc.execute(
                """
                CREATE TABLE IF NOT EXISTS it_aplicacao_dominio (
                    client_mutation_id text NOT NULL
                )
                """
        );

        CountDownLatch largada = new CountDownLatch(1);
        AtomicInteger duplicatas = new AtomicInteger();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            List<Future<?>> tentativas = new ArrayList<>();
            for (String dispositivo : List.of(dispositivoA, dispositivoB)) {
                tentativas.add(executor.submit(() -> {
                    largada.await();
                    try {
                        transacao.executeWithoutResult(status -> {
                            // O espelho de inserirMutacaoCanonicaPendente: a
                            // linha PENDENTE nasce antes do domínio.
                            jdbc.update(
                                    """
                                    INSERT INTO sync_mutacao_cliente (
                                        id, dispositivo_id, proprietario_id,
                                        client_mutation_id, entidade_tipo,
                                        entidade_id, operacao, payload_json,
                                        schema_version, obra_id,
                                        operacao_canonica, status
                                    ) VALUES (
                                        ?, ?, ?, ?, 'RDO', ?, 'CRIAR_RDO',
                                        '{}'::jsonb, 13, ?, 'CREATE',
                                        'PENDENTE'
                                    )
                                    """,
                                    UUID.randomUUID().toString(),
                                    dispositivo,
                                    ownerId,
                                    clientMutationId,
                                    UUID.randomUUID().toString(),
                                    obraId
                            );
                            // A aplicação do domínio, ainda na mesma
                            // transação — como no servidor.
                            pausa(40);
                            jdbc.update(
                                    "INSERT INTO it_aplicacao_dominio VALUES (?)",
                                    clientMutationId
                            );
                        });
                    } catch (DuplicateKeyException esperada) {
                        duplicatas.incrementAndGet();
                    }
                    return null;
                }));
            }
            largada.countDown();
            for (Future<?> tentativa : tentativas) {
                tentativa.get();
            }
        } finally {
            executor.shutdownNow();
        }

        assertThat(duplicatas.get())
                .as("exatamente um dos dois pushes deve morrer no índice único")
                .isEqualTo(1);
        Integer aplicacoes = jdbc.queryForObject(
                "SELECT COUNT(*) FROM it_aplicacao_dominio WHERE client_mutation_id = ?",
                Integer.class,
                clientMutationId
        );
        assertThat(aplicacoes).isEqualTo(1);
    }

    /**
     * A importação em lote reservando faixa não abre buraco para os eventos
     * individuais que disputam a mesma sequência.
     *
     * <p>{@code registrarEventosEmLote} reserva N sequências num único
     * {@code UPDATE ... RETURNING}; escritores individuais alocam de um em um.
     * Os dois caminhos seguram a mesma linha até o commit, então o resultado
     * precisa continuar contíguo — se a reserva vazasse a trava antes do
     * commit, o pull poderia pular a faixa inteira do lote.</p>
     */
    @Test
    void loteEIndividuaisIntercaladosMantemASequenciaContigua()
            throws Exception {
        long antes = maxCommitSeq();
        String obraId = criarObra("lote");
        int individuais = 20;
        int tamanhoDoLote = 30;

        CountDownLatch largada = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(individuais + 1);
        try {
            List<Future<?>> tarefas = new ArrayList<>();
            tarefas.add(executor.submit(() -> {
                largada.await();
                List<CortexOperationalMemoryService.EventoEmLote> lote =
                        new ArrayList<>();
                for (int i = 0; i < tamanhoDoLote; i++) {
                    lote.add(new CortexOperationalMemoryService.EventoEmLote(
                            "OBRA",
                            UUID.randomUUID().toString(),
                            "IMPORTACAO_SIMULADA",
                            "SIMULACAO_LOTE_IT",
                            Map.of("indice", i)
                    ));
                }
                transacao.executeWithoutResult(status ->
                        memoria.registrarEventosEmLote(lote)
                );
                return null;
            }));
            for (int i = 0; i < individuais; i++) {
                String rdoId = UUID.randomUUID().toString();
                tarefas.add(executor.submit(() -> {
                    largada.await();
                    transacao.executeWithoutResult(status ->
                            memoria.registrarEvento(
                                    "RDO",
                                    rdoId,
                                    "RDO_SIMULADO",
                                    "SIMULACAO_LOTE_IT",
                                    obraId,
                                    Map.of()
                            )
                    );
                    return null;
                }));
            }
            largada.countDown();
            for (Future<?> tarefa : tarefas) {
                tarefa.get();
            }
        } finally {
            executor.shutdownNow();
        }

        List<Long> commitSeqs = jdbc.queryForList(
                """
                SELECT commit_seq FROM cortex_evento_operacional
                WHERE commit_seq > ? ORDER BY commit_seq
                """,
                Long.class,
                antes
        );
        int total = individuais + tamanhoDoLote;
        assertThat(commitSeqs).hasSize(total);
        assertThat(commitSeqs.get(0)).isEqualTo(antes + 1);
        assertThat(commitSeqs.get(total - 1)).isEqualTo(antes + total);
    }

    /**
     * A transação abandonada segurando a sequência não congela o sistema.
     *
     * <p>É o pior caso da serialização por linha única: um cliente morre no
     * meio do push já com a trava de {@code commit_seq} na mão, e todo evento
     * de toda obra passa a esperar por um defunto. A produção arma
     * {@code idle_in_transaction_session_timeout} em cada conexão do pool
     * (application-postgresql.yml); aqui o mesmo mecanismo é armado curto e a
     * promessa conferida de ponta a ponta: o PostgreSQL derruba a sessão
     * ociosa, desfaz a alocação dela e o escritor vivo termina o trabalho —
     * sem buraco, porque o rollback devolve o contador junto com a trava.</p>
     */
    @Test
    void transacaoAbandonadaComATravaDaSequenciaEDerrubadaEOsVivosSeguem()
            throws Exception {
        long antes = maxCommitSeq();
        String obraId = criarObra("abandono");

        try (java.sql.Connection abandonada = dataSource.getConnection()) {
            abandonada.setAutoCommit(false);
            try (java.sql.Statement guarda = abandonada.createStatement()) {
                guarda.execute(
                        "SET idle_in_transaction_session_timeout = '1500ms'"
                );
                // A alocação real: pega a trava da linha única e "morre".
                guarda.execute(
                        """
                        UPDATE cortex_evento_commit_sequence
                        SET ultima_commit_seq = ultima_commit_seq + 1
                        WHERE id = 1
                        """
                );
            }

            ExecutorService executor = Executors.newSingleThreadExecutor();
            try {
                Future<Long> vivo = executor.submit(() ->
                        transacao.execute(status ->
                                memoria.registrarEvento(
                                        "RDO",
                                        UUID.randomUUID().toString(),
                                        "RDO_SIMULADO",
                                        "SIMULACAO_ABANDONO_IT",
                                        obraId,
                                        Map.of()
                                )
                        )
                );
                // O escritor vivo só destrava quando o servidor derrubar a
                // sessão abandonada — bem antes deste teto de espera.
                Long commitSeq = vivo.get(30, TimeUnit.SECONDS);
                assertThat(commitSeq)
                        .as("o rollback do abandono devolve o contador")
                        .isEqualTo(antes + 1);
            } finally {
                executor.shutdownNow();
            }

            // A sessão abandonada foi mesmo encerrada pelo servidor.
            assertThat(abandonada.isValid(2)).isFalse();
        }
    }

    private static long maxCommitSeq() {
        Long max = jdbc.queryForObject(
                "SELECT COALESCE(MAX(commit_seq), 0) FROM cortex_evento_operacional",
                Long.class
        );
        return max == null ? 0L : max;
    }

    private static String criarObra(String sufixo) {
        String obraId = UUID.randomUUID().toString();
        jdbc.update(
                "INSERT INTO obra (id, codigo_contrato, nome) VALUES (?, ?, ?)",
                obraId,
                "SYNC-" + sufixo + "-" + obraId.substring(0, 8),
                "Obra sincronização " + sufixo
        );
        return obraId;
    }

    private static String criarColaborador() {
        String id = UUID.randomUUID().toString();
        jdbc.update(
                """
                INSERT INTO colaborador (id, banco_origem, tabela_origem, pk_origem, nome)
                VALUES (?, 'simulacao', 'simulacao', ?, 'Apontador Simulado')
                """,
                id,
                id
        );
        return id;
    }

    private static String criarDispositivo(String usuarioId) {
        String id = UUID.randomUUID().toString();
        jdbc.update(
                """
                INSERT INTO sync_dispositivo (id, nome, tipo, usuario_id, ativo)
                VALUES (?, 'Aparelho simulado', 'WEB', ?, TRUE)
                """,
                id,
                usuarioId
        );
        return id;
    }

    private static void pausa(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
