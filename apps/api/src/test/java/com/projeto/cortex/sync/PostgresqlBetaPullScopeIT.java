package com.projeto.cortex.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.projeto.cortex.auth.CurrentUserService;
import com.projeto.cortex.financeiro.access.FinancialAccessService;
import com.projeto.cortex.financeiro.access.FinancialPermission;
import java.util.List;
import java.util.Optional;
import java.util.Set;
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

/**
 * O pull do Beta nunca tinha sido executado contra um banco de verdade.
 *
 * <p>{@code SyncPullScopeTest} confere o <em>texto</em> do SQL montado e a
 * lista de parâmetros, e passa. Só que texto plausível não é consulta válida:
 * a montagem tem ramos condicionais que acrescentam trechos e parâmetros em
 * pontos diferentes, e nada garantia que os dois lados terminassem
 * correspondentes.
 *
 * <p>A assimetria é o que escondeu isso do uso diário: para Alfa,
 * {@code allowedObraIds} devolve vazio e {@code forSync} retorna filtro
 * nenhum — a consulta é trivial e sempre funcionou. O SQL complicado só existe
 * para Beta. Quem administra o sistema nunca passa por ele, então "funciona na
 * minha máquina" era literalmente verdade, e literalmente enganoso.
 *
 * <p>Este teste executa o pull de verdade, com o schema real. Se a consulta não
 * for válida, ele quebra aqui em vez de virar 500 na mão de quem está em campo
 * com um aparelho novo — que é exatamente quem pede {@code afterCommitSeq=0} e
 * varre o log inteiro.
 */
@Testcontainers(disabledWithoutDocker = true)
class PostgresqlBetaPullScopeIT {

    @Container
    private static final PostgreSQLContainer<?> DATABASE =
            new PostgreSQLContainer<>("postgres:18")
                    .withDatabaseName("cortex_beta_pull_it");

    private static JdbcTemplate jdbc;
    private static TransactionTemplate transactions;

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

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
                DATABASE.getJdbcUrl(),
                DATABASE.getUsername(),
                DATABASE.getPassword()
        );
        jdbc = new JdbcTemplate(dataSource);
        transactions = new TransactionTemplate(
                new DataSourceTransactionManager(dataSource)
        );
    }

    private String collaborator(String papel) {
        String id = UUID.randomUUID().toString();
        jdbc.update(
                """
                INSERT INTO colaborador (
                    id, banco_origem, tabela_origem, pk_origem, nome,
                    papel_acesso, ativo
                ) VALUES (?, 'beta-pull-it', 'colaborador', ?,
                          'Pessoa de campo', ?, TRUE)
                """,
                id,
                id,
                papel
        );
        return id;
    }

    private String worksite() {
        String id = UUID.randomUUID().toString();
        jdbc.update(
                "INSERT INTO obra (id, codigo_contrato, nome) VALUES (?, ?, 'Obra de campo')",
                id,
                "BETA-" + id
        );
        return id;
    }

    private String device(String ownerId) {
        String id = UUID.randomUUID().toString();
        jdbc.update(
                "INSERT INTO sync_dispositivo (id, usuario_id, ativo) VALUES (?, ?, TRUE)",
                id,
                ownerId
        );
        return id;
    }

    private SyncService service(String userId, Set<String> obras) {
        CurrentUserService currentUser = mock(CurrentUserService.class);
        when(currentUser.requireUserId()).thenReturn(userId);
        when(currentUser.allowedObraIds(userId))
                .thenReturn(Optional.of(obras));

        FinancialAccessService financial = mock(FinancialAccessService.class);
        when(financial.allowedObraIds(userId, FinancialPermission.FINANCEIRO_VISUALIZAR))
                .thenReturn(Set.of());
        when(financial.allowedUnitIds(userId, FinancialPermission.FINANCEIRO_VISUALIZAR))
                .thenReturn(Optional.of(Set.of()));

        return new SyncService(
                jdbc,
                mapper,
                transactions,
                new SyncOperationRegistry(List.of()),
                currentUser,
                financial
        );
    }

    /**
     * O caso do aparelho novo em campo: começa do zero e varre o log inteiro.
     * É a chamada que a PWA faz no primeiro acesso, e a que estava devolvendo
     * 500 para quem não é Alfa.
     */
    @Test
    void executaOPullDoBetaContraOBancoRealSemQuebrar() {
        String betaId = collaborator("BETA");
        String obraId = worksite();
        String deviceId = device(betaId);

        SyncPullResponse resposta =
                service(betaId, Set.of(obraId)).pull(deviceId, 0L, 100);

        assertThat(resposta).isNotNull();
        assertThat(resposta.afterCommitSeq()).isZero();
        assertThat(resposta.eventos()).isNotNull();
    }

    /**
     * Beta sem obra nenhuma ainda precisa receber os catálogos globais de
     * referência. O ramo que monta o {@code IN} das obras some quando o
     * conjunto é vazio, e é justamente aí que um descompasso entre trecho e
     * parâmetro apareceria.
     */
    @Test
    void executaOPullDoBetaSemObraAlguma() {
        String betaId = collaborator("BETA");
        String deviceId = device(betaId);

        SyncPullResponse resposta =
                service(betaId, Set.of()).pull(deviceId, 0L, 100);

        assertThat(resposta).isNotNull();
        assertThat(resposta.eventos()).isNotNull();
    }

    /** Várias obras exercitam o {@code IN} com mais de um marcador. */
    @Test
    void executaOPullDoBetaComVariasObras() {
        String betaId = collaborator("BETA");
        String deviceId = device(betaId);
        Set<String> obras = Set.of(worksite(), worksite(), worksite());

        SyncPullResponse resposta =
                service(betaId, obras).pull(deviceId, 0L, 100);

        assertThat(resposta).isNotNull();
        assertThat(resposta.eventos()).isNotNull();
    }
}
