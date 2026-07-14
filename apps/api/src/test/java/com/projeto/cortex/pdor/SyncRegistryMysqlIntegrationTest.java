package com.projeto.cortex.pdor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.projeto.cortex.auth.CurrentUserService;
import com.projeto.cortex.financeiro.access.FinancialAccessService;
import com.projeto.cortex.sync.AppliedSyncMutation;
import com.projeto.cortex.sync.SyncOperationHandler;
import com.projeto.cortex.sync.SyncOperationRegistry;
import com.projeto.cortex.sync.SyncPushRequest;
import com.projeto.cortex.sync.SyncPushResponse;
import com.projeto.cortex.sync.SyncService;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@EnabledIfEnvironmentVariable(
        named = "CORTEX_MYSQL_ROOT_PASSWORD",
        matches = ".+"
)
class SyncRegistryMysqlIntegrationTest {

    private PdorMysqlTestDatabase database;

    @AfterEach
    void dropDatabase() {
        if (database != null) {
            database.drop();
        }
    }

    @Test
    void persistsCorrelatedOwnerScopedReceiptAndRejectsIdReuse()
            throws Exception {
        database = PdorMysqlTestDatabase.create("sync_registry");
        database.migrate();
        JdbcTemplate jdbc = new JdbcTemplate(database.dataSource());
        String ownerId = UUID.randomUUID().toString();
        String deviceId = UUID.randomUUID().toString();
        String entityId = UUID.randomUUID().toString();
        insertOwnerAndDevice(jdbc, ownerId, deviceId);

        AtomicInteger applications = new AtomicInteger();
        SyncOperationHandler handler = testHandler(
                jdbc,
                entityId,
                applications
        );
        CurrentUserService currentUser = mock(CurrentUserService.class);
        when(currentUser.requireUserId()).thenReturn(ownerId);
        SyncService service = new SyncService(
                jdbc,
                new ObjectMapper(),
                new TransactionTemplate(
                        new DataSourceTransactionManager(database.dataSource())
                ),
                new SyncOperationRegistry(List.of(handler)),
                currentUser,
                mock(FinancialAccessService.class)
        );

        SyncPushRequest original = request(
                deviceId,
                "mutation-1",
                "correlation-1",
                "original"
        );
        SyncPushResponse first = service.push(original);
        SyncPushResponse repeated = service.push(original);
        SyncPushResponse changed = service.push(request(
                deviceId,
                "mutation-1",
                "correlation-1",
                "changed"
        ));

        assertThat(first.resultados().getFirst().status())
                .isEqualTo("APLICADA");
        assertThat(repeated.resultados().getFirst().status())
                .isEqualTo("APLICADA");
        assertThat(changed.resultados().getFirst().status())
                .isEqualTo("ERRO");
        assertThat(changed.resultados().getFirst().erro())
                .contains("outro conteúdo");
        assertThat(applications).hasValue(1);

        assertThat(jdbc.queryForMap(
                """
                SELECT proprietario_id, correlacao_id, payload_hash, status
                FROM sync_mutacao_cliente
                WHERE dispositivo_id = ?
                  AND client_mutation_id = 'mutation-1'
                """,
                deviceId
        )).containsEntry("proprietario_id", ownerId)
                .containsEntry("correlacao_id", "correlation-1")
                .containsEntry("status", "APLICADA");
        assertThat((String) jdbc.queryForMap(
                """
                SELECT payload_hash
                FROM sync_mutacao_cliente
                WHERE dispositivo_id = ?
                  AND client_mutation_id = 'mutation-1'
                """,
                deviceId
        ).get("payload_hash")).hasSize(64);
    }

    private SyncPushRequest request(
            String deviceId,
            String mutationId,
            String correlationId,
            String value
    ) {
        ObjectNode payload = new ObjectMapper().createObjectNode();
        payload.put("value", value);
        return new SyncPushRequest(
                deviceId,
                List.of(new SyncPushRequest.MutacaoCliente(
                        mutationId,
                        "TESTE",
                        null,
                        "CRIAR_TESTE",
                        null,
                        payload,
                        LocalDateTime.of(2026, 7, 14, 12, 0),
                        correlationId
                ))
        );
    }

    private SyncOperationHandler testHandler(
            JdbcTemplate jdbc,
            String entityId,
            AtomicInteger applications
    ) {
        return new SyncOperationHandler() {
            @Override
            public String entityType() {
                return "TESTE";
            }

            @Override
            public Set<String> operations() {
                return Set.of("CRIAR_TESTE");
            }

            @Override
            public boolean requiresBaseVersion(String operation) {
                return false;
            }

            @Override
            public AppliedSyncMutation apply(
                    SyncPushRequest.MutacaoCliente mutation
            ) {
                applications.incrementAndGet();
                String eventId = UUID.randomUUID().toString();
                jdbc.update(
                        """
                        INSERT INTO cortex_evento_operacional (
                            id, commit_seq, tipo_entidade, entidade_id,
                            tipo_evento, payload_json, versao_entidade
                        ) VALUES (?, 1, 'TESTE', ?, 'TESTE_CRIADO',
                                  JSON_OBJECT(), 1)
                        """,
                        eventId,
                        entityId
                );
                Long sequence = jdbc.queryForObject(
                        """
                        SELECT sequencia
                        FROM cortex_evento_operacional
                        WHERE id = ?
                        """,
                        Long.class,
                        eventId
                );
                jdbc.update(
                        """
                        INSERT INTO cortex_estado_entidade (
                            tipo_entidade, entidade_id, versao_entidade,
                            ultimo_evento_seq
                        ) VALUES ('TESTE', ?, 1, ?)
                        """,
                        entityId,
                        sequence
                );
                ObjectNode result = new ObjectMapper().createObjectNode();
                result.put("id", entityId);
                return new AppliedSyncMutation("TESTE", entityId, result);
            }
        };
    }

    private void insertOwnerAndDevice(
            JdbcTemplate jdbc,
            String ownerId,
            String deviceId
    ) {
        jdbc.update(
                """
                INSERT INTO colaborador (
                    id, banco_origem, tabela_origem, pk_origem, nome, ativo
                ) VALUES (?, 'teste', 'colaborador', ?, 'Usuário Teste', 1)
                """,
                ownerId,
                ownerId
        );
        jdbc.update(
                """
                INSERT INTO sync_dispositivo (id, usuario_id, ativo)
                VALUES (?, ?, 1)
                """,
                deviceId,
                ownerId
        );
        jdbc.update(
                """
                INSERT INTO sync_estado_dispositivo (
                    dispositivo_id, ultimo_evento_recebido_seq,
                    ultimo_evento_recebido_commit_seq
                ) VALUES (?, 0, 0)
                """,
                deviceId
        );
    }
}
