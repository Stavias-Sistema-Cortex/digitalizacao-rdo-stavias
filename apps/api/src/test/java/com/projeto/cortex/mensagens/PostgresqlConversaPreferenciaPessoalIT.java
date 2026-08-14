package com.projeto.cortex.mensagens;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.projeto.cortex.auth.CurrentUserService;
import com.projeto.cortex.mensagens.api.ConversationResponse;
import com.projeto.cortex.mensagens.api.MessageResponse;
import com.projeto.cortex.mensagens.domain.ConversaAccessPolicy;
import com.projeto.cortex.mensagens.domain.ConversaService;
import com.projeto.cortex.mensagens.domain.MensagemService;
import com.projeto.cortex.mensagens.domain.MessagingOperationalEventService;
import com.projeto.cortex.mensagens.domain.PreferenciaDeConversaService;
import com.projeto.cortex.obras.ObraOperabilityGuard;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletRequest;
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
        jdbc = new JdbcTemplate(new DriverManagerDataSource(
                DATABASE.getJdbcUrl(),
                DATABASE.getUsername(),
                DATABASE.getPassword()
        ));
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
                LocalDateTime.now(ZoneOffset.UTC),
                LocalDateTime.now(ZoneOffset.UTC)
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
}
