package com.projeto.cortex.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.projeto.cortex.auth.CurrentUserService;
import com.projeto.cortex.equipes.EquipeService;
import com.projeto.cortex.financeiro.access.FinancialAccessService;
import com.projeto.cortex.memory.CortexOperationalMemoryService;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * O envelope antigo que derrubava o handler novo.
 *
 * <p>O envelope legado carrega a entidade só em português; o canônico carrega o
 * mesmo dado também em inglês. {@link EquipeSyncOperationHandler} lê o inglês —
 * e {@code SyncService} não o protegia, porque a validação canônica devolve
 * cedo justamente para o legado. A mutação chegava ao handler com
 * {@code entityId} nulo, estourava NullPointerException dentro de
 * {@code requireText}, e a captura genérica devolvia erro interno: o aparelho
 * recebia 500 sem nenhuma pista do que havia de errado no que ele mandou.</p>
 *
 * <p>Uma fila offline não esquece. O envelope antigo preso no IndexedDB de um
 * aparelho voltaria a cada janela de sincronização, e cada janela repetiria o
 * mesmo 500 — para sempre, sem que ninguém pudesse ler o motivo.</p>
 */
class SyncLegacyEnvelopeRejectionTest {

    private static final String DEVICE_ID =
            "11111111-1111-4111-8111-111111111111";

    @Test
    void recusaEnvelopeLegadoAntesDeChegarAoHandlerCanonico() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        CurrentUserService currentUserService = mock(CurrentUserService.class);
        when(currentUserService.requireUserId()).thenReturn("usuario-auth");
        when(
                jdbcTemplate.queryForObject(
                        anyString(),
                        eq(Integer.class),
                        any(),
                        any()
                )
        ).thenReturn(1);

        EquipeService equipeService = mock(EquipeService.class);
        EquipeSyncOperationHandler handler = new EquipeSyncOperationHandler(
                equipeService,
                mock(CortexOperationalMemoryService.class),
                currentUserService,
                new ObjectMapper()
        );

        SyncService service = new SyncService(
                jdbcTemplate,
                new ObjectMapper(),
                transactionTemplateQueExecuta(),
                new SyncOperationRegistry(List.of(handler)),
                currentUserService,
                mock(FinancialAccessService.class)
        );

        SyncPushResponse response = service.push(
                new SyncPushRequest(DEVICE_ID, List.of(envelopeLegadoDeEquipe()))
        );

        SyncPushResponse.ResultadoMutacao resultado =
                response.resultados().getFirst();
        assertThat(resultado.status()).isEqualTo("ERRO");
        assertThat(resultado.erro())
                .contains("CRIAR_EQUIPE")
                .contains("schemaVersion 13");

        /*
         * A prova de que a recusa veio antes do despacho. Se o handler tivesse
         * sido chamado, o `entityId` nulo teria estourado dentro dele — e o
         * serviço de equipes teria sido tocado no caminho.
         */
        assertThat(mockingDetails(equipeService).getInvocations()).isEmpty();
    }

    /**
     * A recusa cabe numa mutação só.
     *
     * <p>Um aparelho com envelope antigo na fila sincroniza no mesmo lote que
     * os outros. Se a recusa derrubasse o push inteiro, a fila antiga de uma
     * pessoa impediria o apontamento de campo de todas as demais.</p>
     */
    @Test
    void oEnvelopeAntigoNaoDerrubaOLoteDeQuemVemJunto() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        CurrentUserService currentUserService = mock(CurrentUserService.class);
        when(currentUserService.requireUserId()).thenReturn("usuario-auth");
        when(
                jdbcTemplate.queryForObject(
                        anyString(),
                        eq(Integer.class),
                        any(),
                        any()
                )
        ).thenReturn(1);

        SyncService service = new SyncService(
                jdbcTemplate,
                new ObjectMapper(),
                transactionTemplateQueExecuta(),
                new SyncOperationRegistry(List.of(
                        new EquipeSyncOperationHandler(
                                mock(EquipeService.class),
                                mock(CortexOperationalMemoryService.class),
                                currentUserService,
                                new ObjectMapper()
                        )
                )),
                currentUserService,
                mock(FinancialAccessService.class)
        );

        SyncPushResponse response = service.push(
                new SyncPushRequest(
                        DEVICE_ID,
                        List.of(
                                envelopeLegadoDeEquipe(),
                                envelopeLegadoDeEquipe()
                        )
                )
        );

        assertThat(response.resultados())
                .hasSize(2)
                .allSatisfy(resultado ->
                        assertThat(resultado.status()).isEqualTo("ERRO"));
    }

    /**
     * Quem depende dos apelidos canônicos diz isso ao lado do código que os lê.
     *
     * <p>Sem esta trava, remover a declaração devolveria o handler ao estado em
     * que o envelope antigo chega até ele — e o defeito volta calado, como
     * estava.</p>
     */
    @Test
    void handlersCanonicosDeclaramQueExigemOEnvelopeNovo() {
        assertThat(
                new EquipeSyncOperationHandler(
                        mock(EquipeService.class),
                        mock(CortexOperationalMemoryService.class),
                        mock(CurrentUserService.class),
                        new ObjectMapper()
                ).requiresCanonicalEnvelope()
        ).isTrue();
    }

    private SyncPushRequest.MutacaoCliente envelopeLegadoDeEquipe() {
        ObjectMapper mapper = new ObjectMapper();
        String equipeId = UUID.randomUUID().toString();
        return new SyncPushRequest.MutacaoCliente(
                UUID.randomUUID().toString(),
                "EQUIPE",
                equipeId,
                "CRIAR_EQUIPE",
                null,
                mapper.createObjectNode()
                        .put("id", equipeId)
                        .put("obraId", UUID.randomUUID().toString())
                        .put("nome", "Equipe de campo"),
                LocalDateTime.now(),
                UUID.randomUUID().toString()
        );
    }

    /**
     * O `TransactionTemplate` de verdade executa o corpo; o mock cru devolve
     * nulo e faria o teste medir a ausência de uma transação, não a recusa.
     */
    private TransactionTemplate transactionTemplateQueExecuta() {
        TransactionTemplate template = mock(TransactionTemplate.class);
        when(template.execute(any())).thenAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(mock(TransactionStatus.class));
        });
        return template;
    }
}
