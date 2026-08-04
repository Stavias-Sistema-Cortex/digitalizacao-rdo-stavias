package com.projeto.cortex.mensagens.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.projeto.cortex.auth.CurrentUserService;
import com.projeto.cortex.mensagens.api.MessagingDirectoryResponse;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Duas pessoas da mesma empresa precisam se achar.
 *
 * <p>Antes, quem não era Alfa tinha de escolher uma obra antes de procurar
 * alguém, e só encontrava quem estivesse vinculado àquela obra — então quem
 * está em campo não achava quem está no escritório, e a conclusão de quem
 * testou foi a certa: o Córtex parecia funcionar só para quem o administra.
 *
 * <p>O diretório é plano de propósito. O que continua fechado é o conteúdo:
 * participar da conversa segue sendo a única credencial que abre mensagem e
 * anexo. Poder endereçar não é poder ler.
 */
@Testcontainers(disabledWithoutDocker = true)
class PostgresqlMessagingDirectoryIT {

    @Container
    private static final PostgreSQLContainer<?> DATABASE =
            new PostgreSQLContainer<>("postgres:18")
                    .withDatabaseName("cortex_messaging_directory_it");

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

    private String collaborator(String nome, String papel, boolean ativo) {
        String id = UUID.randomUUID().toString();
        jdbc.update(
                """
                INSERT INTO colaborador (
                    id, banco_origem, tabela_origem, pk_origem, nome,
                    nome_perfil, papel_acesso, ativo
                ) VALUES (?, 'directory-it', 'colaborador', ?, ?,
                          'Apontador', ?, ?)
                """,
                id,
                id,
                nome,
                papel,
                ativo
        );
        return id;
    }

    private List<MessagingDirectoryResponse> buscar(String actorId, String query) {
        CurrentUserService currentUser = mock(CurrentUserService.class);
        when(currentUser.requireUserId()).thenReturn(actorId);
        return new MessagingDirectoryService(jdbc, currentUser).buscar(query);
    }

    /** O caso relatado: um Beta procurando quem não está na obra dele. */
    @Test
    void betaEncontraColegaSemPrecisarCompartilharObra() {
        String beta = collaborator("Pessoa de Campo", "BETA", true);
        String alvo = collaborator("João Lucas", "ALFA", true);

        assertThat(buscar(beta, "João"))
                .extracting(MessagingDirectoryResponse::id)
                .contains(alvo);
    }

    /** Sem termo, o diretório abre com quem existe — não com uma tela vazia. */
    @Test
    void listaSemTermoEmVezDeExigirBuscaExata() {
        String beta = collaborator("Quem Procura", "BETA", true);
        collaborator("Alguém Ativo", "BETA", true);

        assertThat(buscar(beta, null)).isNotEmpty();
        assertThat(buscar(beta, "  ")).isNotEmpty();
    }

    /** Quem saiu da empresa não é destinatário possível. */
    @Test
    void naoOfereceQuemEstaInativo() {
        String beta = collaborator("Quem Procura", "BETA", true);
        String desligado = collaborator("Pessoa Desligada", "BETA", false);

        assertThat(buscar(beta, "Desligada"))
                .extracting(MessagingDirectoryResponse::id)
                .doesNotContain(desligado);
    }

    /** Ninguém precisa se procurar para falar consigo. */
    @Test
    void naoOfereceQuemEstaProcurando() {
        String beta = collaborator("Pessoa Singular", "BETA", true);

        assertThat(buscar(beta, "Singular"))
                .extracting(MessagingDirectoryResponse::id)
                .doesNotContain(beta);
    }

    /**
     * Curinga digitado é texto, não comando: sem escapar, {@code %} listaria
     * todo mundo e o filtro passaria a obedecer a quem digita.
     */
    @Test
    void trataCuringaComoTextoLiteral() {
        String beta = collaborator("Quem Procura", "BETA", true);
        collaborator("Alguém Ativo", "BETA", true);

        assertThat(buscar(beta, "%")).isEmpty();
        assertThat(buscar(beta, "_")).isEmpty();
    }

    /** O diretório entrega o mínimo para endereçar, e nada de cadastro. */
    @Test
    void entregaSomenteOQueEnderecarPede() {
        String beta = collaborator("Quem Procura", "BETA", true);
        collaborator("Alvo Nominal", "BETA", true);

        MessagingDirectoryResponse pessoa = buscar(beta, "Alvo Nominal").getFirst();

        assertThat(pessoa.nome()).isEqualTo("Alvo Nominal");
        assertThat(pessoa.nomePerfil()).isEqualTo("Apontador");
        assertThat(MessagingDirectoryResponse.class.getRecordComponents())
                .extracting(java.lang.reflect.RecordComponent::getName)
                .containsExactly("id", "nome", "nomePerfil");
    }
}
