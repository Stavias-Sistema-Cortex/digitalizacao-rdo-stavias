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
import com.projeto.cortex.rdos.RdoDraftUpdateService;
import com.projeto.cortex.rdos.RdoQueryService;
import com.projeto.cortex.rdos.RdoService;
import com.projeto.cortex.rdos.RdoWorkflowService;
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
     * A exclusividade canônica também condena só a própria mutação.
     *
     * <p>Ela era validada antes do laço e lançava para fora do push: um único
     * envelope antigo com operação de ciclo de vida — CANCELAR_RDO gravado por
     * uma versão velha do app — respondia 400 para a requisição INTEIRA, e o
     * aparelho via o lote todo recusado em toda janela, para sempre. A recusa
     * agora é terminal e da mutação: REJEITADA, com a categoria que o aparelho
     * lê para saber que não adianta reenviar igual.</p>
     */
    @Test
    void aOperacaoExclusivaCanonicaComEnvelopeAntigoNaoDerrubaOLote() {
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

        RdoWorkflowService workflowService = mock(RdoWorkflowService.class);
        SyncService service = new SyncService(
                jdbcTemplate,
                new ObjectMapper(),
                transactionTemplateQueExecuta(),
                new SyncOperationRegistry(List.of(new RdoSyncOperationHandler(
                        jdbcTemplate,
                        new ObjectMapper(),
                        mock(RdoService.class),
                        mock(RdoDraftUpdateService.class),
                        workflowService,
                        mock(RdoQueryService.class),
                        currentUserService,
                        mock(com.projeto.cortex.financeiro.revenue.RdoExecutionDecisionService.class)
                ))),
                currentUserService,
                mock(FinancialAccessService.class)
        );

        SyncPushResponse response = service.push(new SyncPushRequest(
                DEVICE_ID,
                List.of(envelopeLegadoDeCancelamentoDeRdo())
        ));

        SyncPushResponse.ResultadoMutacao resultado =
                response.resultados().getFirst();
        assertThat(resultado.status()).isEqualTo("REJEITADA");
        assertThat(resultado.erro()).contains("schemaVersion 13");
        assertThat(
                resultado.resultado().path("rejeicao").path("categoria").asText()
        ).isEqualTo("UNSUPPORTED_SCHEMA_VERSION");
        // A recusa veio antes do domínio: cancelar nunca foi tentado.
        assertThat(mockingDetails(workflowService).getInvocations()).isEmpty();
    }

    /**
     * A ordem entre as duas recusas decide o desfecho, e por isso é contrato.
     *
     * <p>O handler de obra exige o envelope canônico; para o envelope legado de
     * ARQUIVAR_OBRA, essa exigência disparava antes da exclusividade e produzia
     * ERRO retentável — a linha velha voltava ao ciclo eterno que a rejeição
     * terminal existe para encerrar. Foi exatamente o que o CI pegou. A
     * exclusividade decide primeiro, porque só precisa da operação e da versão.
     */
    @Test
    void oEnvelopeLegadoDeObraSaiComoRejeicaoTerminalENaoComoErro() {
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

        com.projeto.cortex.obras.ObraService obraService =
                mock(com.projeto.cortex.obras.ObraService.class);
        SyncService service = new SyncService(
                jdbcTemplate,
                new ObjectMapper(),
                transactionTemplateQueExecuta(),
                new SyncOperationRegistry(List.of(new ObraSyncOperationHandler(
                        obraService,
                        currentUserService,
                        new ObjectMapper()
                ))),
                currentUserService,
                mock(FinancialAccessService.class)
        );

        SyncPushResponse response = service.push(new SyncPushRequest(
                DEVICE_ID,
                List.of(new SyncPushRequest.MutacaoCliente(
                        UUID.randomUUID().toString(),
                        "OBRA",
                        UUID.randomUUID().toString(),
                        "ARQUIVAR_OBRA",
                        1L,
                        new ObjectMapper().createObjectNode(),
                        LocalDateTime.of(2026, 8, 12, 12, 0),
                        null
                ))
        ));

        SyncPushResponse.ResultadoMutacao resultado =
                response.resultados().getFirst();
        assertThat(resultado.status()).isEqualTo("REJEITADA");
        assertThat(
                resultado.resultado().path("rejeicao").path("categoria").asText()
        ).isEqualTo("UNSUPPORTED_SCHEMA_VERSION");
        assertThat(mockingDetails(obraService).getInvocations()).isEmpty();
    }

    private SyncPushRequest.MutacaoCliente envelopeLegadoDeCancelamentoDeRdo() {
        return new SyncPushRequest.MutacaoCliente(
                UUID.randomUUID().toString(),
                "RDO",
                UUID.randomUUID().toString(),
                "CANCELAR_RDO",
                1L,
                new ObjectMapper().createObjectNode(),
                LocalDateTime.of(2026, 8, 12, 12, 0),
                null
        );
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
