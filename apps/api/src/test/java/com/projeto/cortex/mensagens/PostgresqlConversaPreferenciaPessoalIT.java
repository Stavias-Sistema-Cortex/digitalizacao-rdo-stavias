package com.projeto.cortex.mensagens;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.projeto.cortex.auth.CurrentUserService;
import com.projeto.cortex.mensagens.api.ConversationCreateRequest;
import com.projeto.cortex.mensagens.api.ConversationResponse;
import com.projeto.cortex.mensagens.api.MessageResponse;
import com.projeto.cortex.mensagens.domain.ConversaAccessPolicy;
import com.projeto.cortex.mensagens.domain.ConversaService;
import com.projeto.cortex.mensagens.domain.MensagemService;
import com.projeto.cortex.mensagens.domain.MessagingAuditContext;
import com.projeto.cortex.mensagens.domain.MessagingOperationalEventService;
import com.projeto.cortex.mensagens.domain.PreferenciaDeConversaService;
import com.projeto.cortex.obras.ObraOperabilityGuard;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Arrumar a própria caixa não mexe na caixa de ninguém.
 *
 * <p>É a única propriedade que importa aqui, e é a que um arquivamento no
 * status da conversa quebraria: quem quisesse a tela limpa tiraria o assunto da
 * vista de toda a obra. Cada teste abaixo confere os dois lados da mesma ação —
 * o que sumiu para quem agiu e o que continuou para quem estava junto.
 */
@Testcontainers(disabledWithoutDocker = true)
class PostgresqlConversaPreferenciaPessoalIT {

    @Container
    private static final PostgreSQLContainer<?> DATABASE =
            new PostgreSQLContainer<>("postgres:18")
                    .withDatabaseName("cortex_preferencia_conversa_it");

    private static JdbcTemplate jdbc;
    private static CurrentUserService currentUser;
    private static ConversaService conversas;
    private static MensagemService mensagens;
    private static PreferenciaDeConversaService preferencias;
    private static TransactionTemplate transactions;

    @BeforeAll
    static void migrarECriarServicos() {
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
        currentUser = new CurrentUserService(jdbc, new MockEnvironment(), false);
        ConversaAccessPolicy politica =
                new ConversaAccessPolicy(jdbc, currentUser);
        preferencias =
                new PreferenciaDeConversaService(jdbc, currentUser, politica);
        conversas = new ConversaService(
                jdbc,
                currentUser,
                politica,
                mock(MessagingOperationalEventService.class),
                mock(ObraOperabilityGuard.class),
                preferencias
        );
        mensagens = new MensagemService(
                jdbc,
                currentUser,
                politica,
                null,
                null,
                null,
                mock(ObraOperabilityGuard.class),
                preferencias
        );
    }

    @AfterEach
    void limparAutenticacao() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void arquivarTiraDaMinhaListaEDeixaADoOutroIntacta() {
        String eu = colaborador("Quem arruma a caixa");
        String outro = colaborador("Quem fica com tudo");
        String conversaId = conversa(eu, "Assunto compartilhado");
        participante(conversaId, eu, eu);
        participante(conversaId, outro, eu);

        autenticar(eu);
        preferencias.arquivar(conversaId);

        assertThat(idsDaLista(false)).doesNotContain(conversaId);
        // A gaveta existe para nada ficar irrecuperável.
        assertThat(idsDaLista(true)).contains(conversaId);

        autenticar(outro);
        assertThat(idsDaLista(false)).contains(conversaId);
        assertThat(idsDaLista(true)).doesNotContain(conversaId);
    }

    @Test
    void desarquivarDevolveAConversaParaAMinhaLista() {
        String eu = colaborador("Quem se arrependeu");
        String conversaId = conversa(eu, "Assunto que volta");
        participante(conversaId, eu, eu);

        autenticar(eu);
        preferencias.arquivar(conversaId);
        preferencias.desarquivar(conversaId);

        assertThat(idsDaLista(false)).contains(conversaId);
        assertThat(idsDaLista(true)).doesNotContain(conversaId);
    }

    /*
     * Limpar é cortina, não demolição: a mensagem continua no banco e na tela
     * de quem estava junto. Uma conversa de obra é registro de trabalho, e
     * registro não some porque alguém quis a tela mais limpa.
     */
    @Test
    void limparEscondeOPassadoSoParaMimEDeixaOFuturoChegar() {
        String eu = colaborador("Quem limpa");
        String outro = colaborador("Quem lembra");
        String conversaId = conversa(eu, "Assunto com histórico");
        participante(conversaId, eu, eu);
        participante(conversaId, outro, eu);
        String antiga = mensagem(conversaId, outro, "Combinado de ontem");

        autenticar(eu);
        preferencias.limpar(conversaId);
        assertThat(idsDoHistorico(conversaId)).isEmpty();

        String nova = mensagem(conversaId, outro, "Combinado de agora");
        assertThat(idsDoHistorico(conversaId)).containsExactly(nova);

        autenticar(outro);
        assertThat(idsDoHistorico(conversaId))
                .containsExactlyInAnyOrder(antiga, nova);
    }

    @Test
    void limpezaOfflinePreservaOCorteEReplaysNaoORegridem() {
        String eu = colaborador("Quem limpa offline");
        String outro = colaborador("Quem escreve enquanto esta offline");
        String conversaId = conversa(eu, "Assunto reconectado");
        participante(conversaId, eu, eu);
        participante(conversaId, outro, eu);
        String antiga = mensagem(
                conversaId,
                outro,
                "Antes da limpeza",
                LocalDateTime.parse("2026-08-19T09:55:00")
        );
        String nova = mensagem(
                conversaId,
                outro,
                "Durante a desconexao",
                LocalDateTime.parse("2026-08-19T10:05:00")
        );

        autenticar(eu);
        preferencias.limpar(
                conversaId,
                Instant.parse("2026-08-19T10:00:00Z")
        );
        preferencias.limpar(
                conversaId,
                Instant.parse("2026-08-19T10:00:00Z")
        );
        preferencias.limpar(
                conversaId,
                Instant.parse("2026-08-19T09:00:00Z")
        );

        assertThat(jdbc.queryForObject(
                """
                SELECT limpo_ate
                FROM conversa_preferencia_pessoal
                WHERE conversa_id = ? AND colaborador_id = ?
                """,
                LocalDateTime.class,
                conversaId,
                eu
        )).isEqualTo(LocalDateTime.parse("2026-08-19T10:00:00"));
        assertThat(idsDoHistorico(conversaId))
                .containsExactly(nova)
                .doesNotContain(antiga);
        assertThat(conversas.authorizationSnapshot().preferences())
                .filteredOn(preference ->
                        preference.conversationId().equals(conversaId)
                )
                .singleElement()
                .extracting(preference -> preference.limpoAte())
                .isEqualTo(Instant.parse("2026-08-19T10:00:00Z"));
    }

    @Test
    void reabrirOHistoricoDevolveOQueACortinaEscondia() {
        String eu = colaborador("Quem reabre");
        String conversaId = conversa(eu, "Assunto reaberto");
        participante(conversaId, eu, eu);
        String antiga = mensagem(conversaId, eu, "Registro que volta");

        autenticar(eu);
        preferencias.limpar(conversaId);
        assertThat(idsDoHistorico(conversaId)).isEmpty();

        preferencias.desfazerLimpeza(conversaId);
        assertThat(idsDoHistorico(conversaId)).containsExactly(antiga);
    }

    /*
     * O papel administrativo alcança registro de obra, não a caixa de
     * mensagens alheia. Sem esta regra, quem tinha Alfa lia a conversa direta
     * de qualquer pessoa da empresa — e as duas pontas não tinham como saber.
     */
    @Test
    void oAlfaNaoAlcancaAConversaDiretaDeOutrasDuasPessoas() {
        String um = colaborador("Quem conversa");
        String outro = colaborador("Com quem conversa");
        String alfa = colaboradorAlfa("Quem administra");
        String conversaId = conversaDireta(um, outro);
        participante(conversaId, um, um);
        participante(conversaId, outro, um);
        String reservada = mensagem(conversaId, um, "Assunto particular");

        autenticar(alfa);
        assertThat(idsDaLista(false)).doesNotContain(conversaId);
        assertThatThrownBy(() -> mensagens.history(conversaId, null, 50))
                .isInstanceOf(ResponseStatusException.class);

        // Quem participa continua vendo tudo.
        autenticar(um);
        assertThat(idsDoHistorico(conversaId)).containsExactly(reservada);
    }

    /* Conversa de obra continua alcançável por Alfa: aquilo é documentação. */
    @Test
    void oAlfaSegueAlcancandoAConversaDeTrabalho() {
        String dono = colaborador("Quem abriu o grupo");
        String alfa = colaboradorAlfa("Quem administra o grupo");
        String conversaId = conversa(dono, "Assunto de obra");
        participante(conversaId, dono, dono);

        autenticar(alfa);
        assertThat(idsDaLista(false)).contains(conversaId);
    }

    @Test
    void snapshotAutorizadoIncluiTodaAListaEAGavetaSemLimite() {
        String eu = colaborador("Quem sincroniza a caixa completa");
        Set<String> autorizadas = new LinkedHashSet<>();
        for (int indice = 0; indice < 101; indice++) {
            String conversaId = conversa(eu, "Assunto " + indice);
            participante(conversaId, eu, eu);
            autorizadas.add(conversaId);
        }
        String arquivada = autorizadas.iterator().next();

        autenticar(eu);
        preferencias.arquivar(arquivada);

        assertThat(idsDaLista(false)).doesNotContain(arquivada);
        assertThat(conversas.authorizedConversationIds())
                .hasSize(101)
                .containsExactlyInAnyOrderElementsOf(autorizadas);
    }

    @Test
    void snapshotAutorizadoExcluiAcessoRevogadoEConversaDeletada() {
        String eu = colaborador("Quem perde dois acessos");
        String revogada = conversa(eu, "Participação revogada");
        participante(revogada, eu, eu);
        String deletada = conversa(eu, "Conversa deletada");
        participante(deletada, eu, eu);
        jdbc.update("""
                UPDATE conversa_participante
                SET status = 'REMOVIDO',
                    removido_em = CURRENT_TIMESTAMP(6),
                    deletado_em = CURRENT_TIMESTAMP(6)
                WHERE conversa_id = ? AND colaborador_id = ?
                """, revogada, eu);
        jdbc.update(
                "UPDATE conversa SET deletado_em = CURRENT_TIMESTAMP(6) WHERE id = ?",
                deletada
        );

        autenticar(eu);

        assertThat(conversas.authorizedConversationIds())
                .doesNotContain(revogada, deletada);
    }

    @Test
    void snapshotDoAlfaPreservaPrivacidadeDaConversaDireta() {
        String um = colaborador("Quem fala em particular");
        String outro = colaborador("Quem recebe em particular");
        String alfa = colaboradorAlfa("Quem administra sem ler particular");
        String direta = conversaDireta(um, outro);
        participante(direta, um, um);
        participante(direta, outro, um);
        String registro = conversa(um, "Registro operacional");
        participante(registro, um, um);

        autenticar(alfa);

        assertThat(conversas.authorizedConversationIds())
                .contains(registro)
                .doesNotContain(direta);
    }

    @Test
    void recriarConversaDiretaExistenteDevolveAMesmaConversa() {
        String eu = colaborador("Quem inicia a conversa");
        String outro = colaborador("Quem recebe a conversa");
        autenticar(eu);

        ConversationResponse criada = transactions.execute(status ->
                conversas.create(
                        conversaDiretaRequest(outro),
                        MessagingAuditContext.online(eu, "primeira-direta")
                )
        );
        ConversationResponse repetida = transactions.execute(status ->
                conversas.create(
                        conversaDiretaRequest(outro),
                        MessagingAuditContext.online(eu, "replay-direta")
                )
        );

        assertThat(repetida.id()).isEqualTo(criada.id());
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM conversa WHERE id = ?",
                Integer.class,
                criada.id()
        )).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM conversa_participante WHERE conversa_id = ?",
                Integer.class,
                criada.id()
        )).isEqualTo(2);
    }

    @Test
    void novaDiretaArquivadaReutilizaAConversaComHistoricoVazioSoParaMim() {
        String eu = colaborador("Quem começa de novo");
        String outro = colaborador("Quem conserva o histórico");
        autenticar(eu);

        ConversationResponse criada = transactions.execute(status ->
                conversas.create(
                        conversaDiretaRequest(outro),
                        MessagingAuditContext.online(eu, "direta-original")
                )
        );
        String antiga = mensagem(
                criada.id(),
                outro,
                "Mensagem da conversa anterior"
        );
        preferencias.arquivar(criada.id());

        ConversationResponse reiniciada = transactions.execute(status ->
                conversas.create(
                        conversaDiretaRequest(outro),
                        MessagingAuditContext.online(eu, "nova-direta")
                )
        );

        assertThat(reiniciada.id()).isEqualTo(criada.id());
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM conversa WHERE id = ?",
                Integer.class,
                criada.id()
        )).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM mensagem WHERE conversa_id = ?",
                Integer.class,
                criada.id()
        )).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                """
                SELECT COUNT(*)
                FROM conversa_preferencia_pessoal
                WHERE conversa_id = ?
                  AND colaborador_id = ?
                  AND arquivado_em IS NULL
                  AND limpo_ate IS NOT NULL
                """,
                Integer.class,
                criada.id(),
                eu
        )).isEqualTo(1);
        assertThat(idsDaLista(false)).contains(criada.id());
        assertThat(idsDoHistorico(criada.id())).isEmpty();

        autenticar(outro);
        assertThat(idsDoHistorico(criada.id())).containsExactly(antiga);
    }

    @Test
    void replayExatoNaoReiniciaConversaDiretaDepoisDeArquivada() {
        String eu = colaborador("Quem apenas repete a operação");
        String outro = colaborador("Quem não perde histórico por retry");
        autenticar(eu);
        ConversationCreateRequest request = conversaDiretaRequest(outro);

        ConversationResponse criada = transactions.execute(status ->
                conversas.create(
                        request,
                        MessagingAuditContext.online(eu, "direta-original")
                )
        );
        String antiga = mensagem(criada.id(), outro, "Mensagem preservada");
        preferencias.arquivar(criada.id());

        ConversationResponse replay = transactions.execute(status ->
                conversas.create(
                        request,
                        MessagingAuditContext.online(eu, "retry-tecnico")
                )
        );

        assertThat(replay.id()).isEqualTo(criada.id());
        assertThat(idsDaLista(false)).doesNotContain(criada.id());
        assertThat(idsDaLista(true)).contains(criada.id());
        assertThat(idsDoHistorico(criada.id())).containsExactly(antiga);
    }

    @Test
    void repetirAMesmaCriacaoComOMesmoIdEhReplayExato() {
        String eu = colaborador("Quem repete a criação");
        String outro = colaborador("Participante da criação repetida");
        autenticar(eu);
        ConversationCreateRequest request = conversaDiretaRequest(outro);

        ConversationResponse criada = transactions.execute(status ->
                conversas.create(
                        request,
                        MessagingAuditContext.online(eu, "primeiro-envio")
                )
        );
        ConversationResponse repetida = transactions.execute(status ->
                conversas.create(
                        request,
                        MessagingAuditContext.online(eu, "replay-exato")
                )
        );

        assertThat(repetida.id()).isEqualTo(criada.id());
        assertThat(repetida.participantes())
                .extracting(participante -> participante.colaboradorId())
                .containsExactlyInAnyOrder(eu, outro);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM conversa WHERE id = ?",
                Integer.class,
                criada.id()
        )).isEqualTo(1);
    }

    @Test
    void idDeOutraConversaNaoPodeRedirecionarUmaNovaConversaDireta() {
        String eu = colaborador("Quem cria com id repetido");
        String participanteOriginal = colaborador("Participante original");
        String participantePretendido = colaborador("Participante pretendido");
        autenticar(eu);

        ConversationResponse existente = transactions.execute(status ->
                conversas.create(
                        conversaDiretaRequest(participanteOriginal),
                        MessagingAuditContext.online(eu, "direta-original")
                )
        );
        ConversationCreateRequest payloadDivergente =
                new ConversationCreateRequest(
                        existente.id(),
                        "DIRETA",
                        null,
                        null,
                        null,
                        List.of(participantePretendido)
                );

        assertThatThrownBy(() -> transactions.execute(status ->
                conversas.create(
                        payloadDivergente,
                        MessagingAuditContext.online(eu, "id-reutilizado")
                )
        )).isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("409 CONFLICT");

        assertThat(jdbc.queryForList(
                """
                SELECT colaborador_id
                FROM conversa_participante
                WHERE conversa_id = ? AND status = 'ATIVO'
                """,
                String.class,
                existente.id()
        )).containsExactlyInAnyOrder(eu, participanteOriginal);
    }

    @Test
    void doisAparelhosCriamUmaUnicaPreferenciaSemAbortarATransacao()
            throws Exception {
        String eu = colaborador("Quem arquiva em dois aparelhos");
        String conversaId = conversa(eu, "Assunto arquivado em paralelo");
        participante(conversaId, eu, eu);

        CountDownLatch updatesSemLinha = new CountDownLatch(2);
        JdbcTemplate jdbcCoordenado = new PreferenceRaceJdbcTemplate(
                jdbc.getDataSource(),
                updatesSemLinha
        );
        CurrentUserService usuarioConcorrente = new CurrentUserService(
                jdbcCoordenado,
                new MockEnvironment(),
                false
        );
        PreferenciaDeConversaService preferenciasConcorrentes =
                new PreferenciaDeConversaService(
                        jdbcCoordenado,
                        usuarioConcorrente,
                        new ConversaAccessPolicy(
                                jdbcCoordenado,
                                usuarioConcorrente
                        )
                );

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<?> primeira = executor.submit(() -> arquivarEmTransacao(
                    preferenciasConcorrentes,
                    eu,
                    conversaId
            ));
            Future<?> segunda = executor.submit(() -> arquivarEmTransacao(
                    preferenciasConcorrentes,
                    eu,
                    conversaId
            ));

            primeira.get(30, TimeUnit.SECONDS);
            segunda.get(30, TimeUnit.SECONDS);
        }

        assertThat(jdbc.queryForObject(
                """
                SELECT COUNT(*)
                FROM conversa_preferencia_pessoal
                WHERE conversa_id = ? AND colaborador_id = ?
                """,
                Integer.class,
                conversaId,
                eu
        )).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                """
                SELECT arquivado_em IS NOT NULL
                FROM conversa_preferencia_pessoal
                WHERE conversa_id = ? AND colaborador_id = ?
                """,
                Boolean.class,
                conversaId,
                eu
        )).isTrue();
    }

    private static void arquivarEmTransacao(
            PreferenciaDeConversaService service,
            String colaboradorId,
            String conversaId
    ) {
        transactions.executeWithoutResult(status -> {
            autenticar(colaboradorId);
            try {
                service.arquivar(conversaId);
            } finally {
                RequestContextHolder.resetRequestAttributes();
            }
        });
    }

    private static ConversationCreateRequest conversaDiretaRequest(
            String participanteId
    ) {
        return new ConversationCreateRequest(
                UUID.randomUUID().toString(),
                "DIRETA",
                null,
                null,
                null,
                List.of(participanteId)
        );
    }

    private static String colaboradorAlfa(String nome) {
        String id = colaborador(nome);
        jdbc.update(
                "UPDATE colaborador SET papel_acesso = 'ALFA' WHERE id = ?",
                id
        );
        return id;
    }

    private static String conversaDireta(String um, String outro) {
        String id = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO conversa
                    (id, tipo, criado_por, chave_participantes_direta)
                VALUES (?, 'DIRETA', ?, ?)
                """,
                id,
                um,
                id.replace("-", "").repeat(2).substring(0, 64)
        );
        return id;
    }

    private static List<String> idsDaLista(boolean arquivadas) {
        return conversas.list(50, arquivadas).stream()
                .map(ConversationResponse::id)
                .toList();
    }

    private static List<String> idsDoHistorico(String conversaId) {
        return mensagens.history(conversaId, null, 50).stream()
                .map(MessageResponse::id)
                .toList();
    }

    private static String colaborador(String nome) {
        String id = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO colaborador (
                    id, banco_origem, tabela_origem, pk_origem,
                    nome, papel_acesso, ativo
                ) VALUES (?, 'test', 'colaborador', ?, ?, 'BETA', TRUE)
                """, id, id, nome);
        return id;
    }

    private static String conversa(String donoId, String titulo) {
        String id = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO conversa (id, tipo, titulo, criado_por)
                VALUES (?, 'GRUPO', ?, ?)
                """, id, titulo, donoId);
        return id;
    }

    private static void participante(
            String conversaId,
            String colaboradorId,
            String adicionadoPor
    ) {
        jdbc.update("""
                INSERT INTO conversa_participante (
                    id, conversa_id, colaborador_id, papel, status,
                    adicionado_por
                ) VALUES (?, ?, ?, 'MEMBRO', 'ATIVO', ?)
                """,
                UUID.randomUUID().toString(),
                conversaId,
                colaboradorId,
                adicionadoPor
        );
    }

    private static String mensagem(
            String conversaId,
            String autorId,
            String corpo
    ) {
        return mensagem(
                conversaId,
                autorId,
                corpo,
                LocalDateTime.now(ZoneOffset.UTC)
        );
    }

    private static String mensagem(
            String conversaId,
            String autorId,
            String corpo,
            LocalDateTime criadaEm
    ) {
        String id = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO mensagem (
                    id, conversa_id, autor_id, corpo, status,
                    client_mutation_id, criado_cliente_em, criado_em
                ) VALUES (?, ?, ?, ?, 'ATIVA', ?, ?, ?)
                """,
                id,
                conversaId,
                autorId,
                corpo,
                UUID.randomUUID().toString(),
                criadaEm,
                criadaEm
        );
        return id;
    }

    private static void autenticar(String colaboradorId) {
        MockHttpServletRequest requisicao = new MockHttpServletRequest();
        requisicao.setAttribute(
                CurrentUserService.REQUEST_ATTRIBUTE_USER_ID,
                colaboradorId
        );
        RequestContextHolder.setRequestAttributes(
                new ServletRequestAttributes(requisicao)
        );
    }

    private static final class PreferenceRaceJdbcTemplate
            extends JdbcTemplate {

        private final CountDownLatch updatesSemLinha;

        private PreferenceRaceJdbcTemplate(
                DataSource dataSource,
                CountDownLatch updatesSemLinha
        ) {
            super(dataSource);
            this.updatesSemLinha = updatesSemLinha;
        }

        @Override
        public int update(String sql, Object... args) {
            int alteradas = super.update(sql, args);
            if (alteradas == 0 && sql.startsWith(
                    "UPDATE conversa_preferencia_pessoal SET arquivado_em"
            )) {
                updatesSemLinha.countDown();
                try {
                    if (!updatesSemLinha.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException(
                                "As duas gravações não alcançaram o INSERT."
                        );
                    }
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(
                            "A corrida de preferência foi interrompida.",
                            exception
                    );
                }
            }
            return alteradas;
        }
    }
}
