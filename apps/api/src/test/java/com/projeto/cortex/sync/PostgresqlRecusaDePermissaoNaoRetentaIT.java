package com.projeto.cortex.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.projeto.cortex.auth.CurrentUserService;
import com.projeto.cortex.financeiro.access.FinancialAccessService;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
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

/**
 * Uma recusa de permissão termina o assunto; uma de validação, não.
 *
 * <p>Toda {@link ResponseStatusException} de mutação não canônica virava
 * {@code ERRO}, e {@code ERRO} é retentável — o aparelho devolvia a linha à fila
 * e a empurrava de novo, em toda janela, para sempre. Para uma recusa de
 * validação isso ainda serve: o dado pode ser corrigido, e há reparos que
 * dependem justamente da retentativa. Para uma recusa de autorização não serve
 * de nada: o servidor não muda de ideia porque a pergunta foi repetida.
 *
 * <p>O sintoma aparecia longe da causa — a tarja de erro acesa em toda
 * sincronização, sem nada na tela dizendo o que estava preso —, e quem é Alfa
 * nunca é recusado, então nunca reproduzia.
 *
 * <p>Isto só se verifica contra um PostgreSQL de verdade: o que se afirma aqui é
 * o que ficou gravado em {@code sync_mutacao_cliente}, e é dele que sai a
 * resposta do replay.
 */
@Testcontainers(disabledWithoutDocker = true)
class PostgresqlRecusaDePermissaoNaoRetentaIT {

    @Container
    private static final PostgreSQLContainer<?> DATABASE =
            new PostgreSQLContainer<>("postgres:18")
                    .withDatabaseName("cortex_recusa_permissao_it");

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

    @Test
    void mutacaoRecusadaPorPermissaoEhRejeitadaEmVezDeErro() {
        String ownerId = colaborador();
        String deviceId = dispositivo(ownerId);
        AtomicInteger tentativas = new AtomicInteger();
        SyncService service = servico(
                ownerId,
                new HandlerQueRecusa(
                        tentativas,
                        HttpStatus.FORBIDDEN,
                        "A operação exige perfil administrativo (Alfa)."
                )
        );
        String clientMutationId = UUID.randomUUID().toString();

        SyncPushResponse resposta = service.push(new SyncPushRequest(
                deviceId,
                List.of(mutacao(clientMutationId))
        ));

        SyncPushResponse.ResultadoMutacao resultado = resposta.resultados().get(0);
        assertThat(resultado.status()).isEqualTo("REJEITADA");
        assertThat(resultado.erro())
                .isEqualTo("A operação exige perfil administrativo (Alfa).");
        // A categoria é o que o aparelho lê para saber que não adianta insistir.
        assertThat(
                resultado.resultado().path("rejeicao").path("categoria").asText()
        ).isEqualTo("AUTHORIZATION");
        assertThat(statusGravado(clientMutationId)).isEqualTo("REJEITADA");
    }

    /**
     * O reenvio da mesma linha não volta a bater no domínio.
     *
     * <p>Era aqui que o ciclo se fechava: {@code ERRO} é reprocessado no replay,
     * então cada janela repetia a chamada e recebia a mesma recusa. Uma linha
     * {@code REJEITADA} devolve o que já está gravado.
     */
    @Test
    void oReenvioDaMesmaRecusaNaoPerguntaDeNovoAoDominio() {
        String ownerId = colaborador();
        String deviceId = dispositivo(ownerId);
        AtomicInteger tentativas = new AtomicInteger();
        SyncService service = servico(
                ownerId,
                new HandlerQueRecusa(
                        tentativas,
                        HttpStatus.FORBIDDEN,
                        "Você não possui permissão para acessar esta obra."
                )
        );
        String clientMutationId = UUID.randomUUID().toString();
        SyncPushRequest.MutacaoCliente mutacao = mutacao(clientMutationId);

        service.push(new SyncPushRequest(deviceId, List.of(mutacao)));
        SyncPushResponse reenvio =
                service.push(new SyncPushRequest(deviceId, List.of(mutacao)));

        assertThat(reenvio.resultados().get(0).status()).isEqualTo("REJEITADA");
        assertThat(tentativas.get()).isEqualTo(1);
    }

    /**
     * Validação continua retentável, e isso não é descuido.
     *
     * <p>Reparos do aparelho dependem de a linha permanecer em {@code ERRO} para
     * voltar corrigida — o RDO que o servidor não tem e reenvia como criação é o
     * caso mais comum. Estreitar a mudança a 401 e 403 preserva esses caminhos.
     */
    @Test
    void recusaDeValidacaoContinuaRetentavel() {
        String ownerId = colaborador();
        String deviceId = dispositivo(ownerId);
        SyncService service = servico(
                ownerId,
                new HandlerQueRecusa(
                        new AtomicInteger(),
                        HttpStatus.NOT_FOUND,
                        "RDO não encontrado."
                )
        );
        String clientMutationId = UUID.randomUUID().toString();

        SyncPushResponse resposta = service.push(new SyncPushRequest(
                deviceId,
                List.of(mutacao(clientMutationId))
        ));

        assertThat(resposta.resultados().get(0).status()).isEqualTo("ERRO");
        assertThat(statusGravado(clientMutationId)).isEqualTo("ERRO");
    }

    private SyncService servico(String ownerId, SyncOperationHandler handler) {
        CurrentUserService currentUser = mock(CurrentUserService.class);
        when(currentUser.requireUserId()).thenReturn(ownerId);
        return new SyncService(
                jdbc,
                mapper,
                transactions,
                new SyncOperationRegistry(List.of(handler)),
                currentUser,
                mock(FinancialAccessService.class)
        );
    }

    private SyncPushRequest.MutacaoCliente mutacao(String clientMutationId) {
        return new SyncPushRequest.MutacaoCliente(
                clientMutationId,
                HandlerQueRecusa.ENTIDADE,
                UUID.randomUUID().toString(),
                HandlerQueRecusa.OPERACAO,
                null,
                mapper.createObjectNode(),
                LocalDateTime.now(),
                null
        );
    }

    private String statusGravado(String clientMutationId) {
        return jdbc.queryForObject(
                "SELECT status FROM sync_mutacao_cliente WHERE client_mutation_id = ?",
                String.class,
                clientMutationId
        );
    }

    private String colaborador() {
        String id = UUID.randomUUID().toString();
        jdbc.update(
                """
                INSERT INTO colaborador (
                    id, banco_origem, tabela_origem, pk_origem, nome,
                    papel_acesso, ativo
                ) VALUES (?, 'recusa-it', 'colaborador', ?,
                          'Apontador de campo', 'BETA', TRUE)
                """,
                id,
                id
        );
        return id;
    }

    private String dispositivo(String ownerId) {
        String id = UUID.randomUUID().toString();
        jdbc.update(
                "INSERT INTO sync_dispositivo (id, usuario_id, ativo) VALUES (?, ?, TRUE)",
                id,
                ownerId
        );
        jdbc.update(
                "INSERT INTO sync_estado_dispositivo (dispositivo_id) VALUES (?)",
                id
        );
        return id;
    }

    /** Domínio que recusa sempre, contando quantas vezes foi perguntado. */
    private static final class HandlerQueRecusa implements SyncOperationHandler {

        static final String ENTIDADE = "TAREFA";
        static final String OPERACAO = "ATUALIZAR_TAREFA";

        private final AtomicInteger tentativas;
        private final HttpStatus status;
        private final String motivo;

        HandlerQueRecusa(
                AtomicInteger tentativas,
                HttpStatus status,
                String motivo
        ) {
            this.tentativas = tentativas;
            this.status = status;
            this.motivo = motivo;
        }

        @Override
        public String entityType() {
            return ENTIDADE;
        }

        @Override
        public Set<String> operations() {
            return Set.of(OPERACAO);
        }

        @Override
        public boolean requiresBaseVersion(String operation) {
            return false;
        }

        @Override
        public AppliedSyncMutation apply(
                SyncPushRequest.MutacaoCliente mutation,
                SyncMutationContext context
        ) {
            tentativas.incrementAndGet();
            throw new ResponseStatusException(status, motivo);
        }
    }
}
