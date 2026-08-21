package com.projeto.cortex.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * As guardas de sessão do runtime não podem sumir numa refatoração de perfil.
 *
 * <p>Todo evento da memória operacional passa pela linha única de
 * {@code commit_seq}, segurada da alocação até o commit. Uma transação
 * abandonada com essa trava congela a sincronização de todas as obras — o
 * cenário está reproduzido em
 * {@code PostgresqlSincronizacaoConcorrenteIT}. A proteção de produção é o
 * par de guardas armado em cada conexão do pool do perfil {@code postgresql};
 * o perfil de migração fica de fora de propósito, porque um DDL do Flyway
 * pode legitimamente esperar mais que o runtime.</p>
 */
class PostgresqlSessionGuardContractTest {

    @Test
    void runtimeProfileArmsTheSessionGuardsOnEveryPooledConnection()
            throws Exception {
        String runtime = Files.readString(
                Path.of("src/main/resources/application-postgresql.yml")
        );

        assertThat(runtime)
                .contains("connection-init-sql:")
                .contains("SET idle_in_transaction_session_timeout = '60s'")
                .contains("SET lock_timeout = '30s'");
    }

    @Test
    void migrationProfileKeepsItsOwnPatienceForDdl() throws Exception {
        String migrate = Files.readString(
                Path.of("src/main/resources/application-postgresql-migrate.yml")
        );

        assertThat(migrate).doesNotContain("connection-init-sql");
    }
}
