package com.projeto.cortex.auth.webauthn;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yubico.webauthn.data.AuthenticatorTransport;
import com.yubico.webauthn.data.ByteArray;
import java.util.Set;
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
 * A cerimônia de passkey persistida contra o banco que a produção usa.
 *
 * <p>Este teste existe por causa de um buraco que custou caro: o repositório
 * WebAuthn só era exercitado de ponta a ponta contra MySQL, e MySQL aceita uma
 * string em coluna JSON sem reclamar. PostgreSQL não — sem o cast explícito,
 * gravar a passkey falha na hora, e o registro morria num "Internal Server
 * Error" depois de a pessoa já ter aprovado a credencial no aparelho. O CI
 * ficava verde enquanto a produção quebrava, porque o banco do teste não era o
 * banco de verdade.
 */
@Testcontainers(disabledWithoutDocker = true)
class PostgresqlWebAuthnCredentialPersistenceIT {

    @Container
    private static final PostgreSQLContainer<?> DATABASE =
            new PostgreSQLContainer<>("postgres:18")
                    .withDatabaseName("webauthn_persistence");

    private static JdbcTemplate jdbc;
    private static WebAuthnCredentialRepository repository;

    @BeforeAll
    static void migratePostgresql() {
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
        repository = new WebAuthnCredentialRepository(
                jdbc,
                new ObjectMapper()
        );
    }

    private static String insertCollaborator() {
        String id = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO colaborador (
                    id, banco_origem, tabela_origem, pk_origem,
                    codigo_colaborador, nome, papel_acesso, ativo
                ) VALUES (?, 'webauthn-it', 'colaborador', ?, ?, ?, 'BETA', TRUE)
                """,
                id,
                id,
                id.substring(0, 8),
                "Colaborador " + id.substring(0, 8)
        );
        // A leitura da credencial só reconhece quem tem identidade ATIVA; sem
        // esta linha o teste falharia na consulta e não na gravação, apontando
        // para o lugar errado.
        jdbc.update("""
                INSERT INTO auth_identity (colaborador_id, status)
                VALUES (?, 'ATIVA')
                """, id);
        return id;
    }

    /**
     * O caminho inteiro que o botão "Registrar passkey" percorre no servidor:
     * challenge criado, consumido uma vez só, credencial gravada e legível de
     * volta com transports e contador. É a gravação — a parte PG-específica —
     * que este teste prende no lugar.
     */
    @Test
    void persisteACerimoniaDeRegistroContraPostgresReal() {
        String collaboratorId = insertCollaborator();
        String challengeId = UUID.randomUUID().toString();
        String clientInstanceHash = "a".repeat(64);
        ByteArray challenge = new ByteArray(new byte[]{1, 2, 3, 4});

        repository.createChallenge(
                challengeId,
                collaboratorId,
                WebAuthnCeremony.REGISTRATION,
                challenge,
                "{\"request\":true}",
                clientInstanceHash,
                300
        );
        assertThat(repository.consumeChallenge(
                challengeId,
                WebAuthnCeremony.REGISTRATION,
                clientInstanceHash
        )).get().extracting(StoredWebAuthnChallenge::collaboratorId)
                .isEqualTo(collaboratorId);
        assertThat(repository.consumeChallenge(
                challengeId,
                WebAuthnCeremony.REGISTRATION,
                clientInstanceHash
        )).isEmpty();

        ByteArray credentialId = new ByteArray(new byte[]{5, 6, 7, 8});
        ByteArray userHandle = WebAuthnUserHandle.fromCollaboratorId(
                collaboratorId
        );
        repository.saveCredential(
                UUID.randomUUID().toString(),
                collaboratorId,
                new NewWebAuthnCredential(
                        credentialId,
                        userHandle,
                        new ByteArray(new byte[]{9, 10, 11}),
                        2,
                        Set.of(AuthenticatorTransport.INTERNAL),
                        "00000000-0000-0000-0000-000000000000",
                        true,
                        false
                ),
                "Passkey de teste"
        );

        assertThat(repository.findActiveCredentialOwner(credentialId))
                .contains(collaboratorId);
        assertThat(repository.lookup(credentialId, userHandle))
                .get()
                .satisfies(stored -> {
                    assertThat(stored.getSignatureCount()).isEqualTo(2);
                    assertThat(stored.getTransports())
                            .contains(Set.of(AuthenticatorTransport.INTERNAL));
                });
    }
}
