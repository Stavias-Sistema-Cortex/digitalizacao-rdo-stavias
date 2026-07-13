package com.projeto.cortex.pdor;

import static org.assertj.core.api.Assertions.assertThat;

import com.projeto.cortex.auth.identity.AuthIdentity;
import com.projeto.cortex.auth.identity.AuthIdentityRepository;
import com.projeto.cortex.auth.identity.CpfLookupDigest;
import com.projeto.cortex.auth.identity.HmacCpfLookupDigestService;
import com.projeto.cortex.colaboradores.CpfHasher;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;

@EnabledIfEnvironmentVariable(named = "CORTEX_MYSQL_ROOT_PASSWORD", matches = ".+")
class AuthIdentityRepositoryMysqlIntegrationTest {

    private static final String SYNTHETIC_CPF = "11144477735";
    private static final String TEST_SECRET =
            "test-only-mysql-hmac-secret-material-0001";

    private PdorMysqlTestDatabase database;

    @AfterEach
    void dropDatabase() {
        if (database != null) {
            database.drop();
        }
    }

    @Test
    void upgradesLegacyLookupAndNeverOverwritesVerifiedEmail() {
        database = PdorMysqlTestDatabase.create("auth_identity_hmac");
        database.migrate();

        JdbcTemplate jdbc = new JdbcTemplate(database.dataSource());
        String colaboradorId = UUID.randomUUID().toString();
        String legacySha = CpfHasher.hashDeDigitos(SYNTHETIC_CPF);
        jdbc.update("""
                INSERT INTO colaborador (
                    id,
                    banco_origem,
                    tabela_origem,
                    pk_origem,
                    cpf_hash,
                    nome,
                    papel_acesso,
                    ativo
                ) VALUES (?, 'teste', 'teste', 'academy-sintetico', ?,
                          'Colaborador Sintético', 'ALFA', 1)
                """,
                colaboradorId,
                legacySha
        );

        HmacCpfLookupDigestService digestService =
                new HmacCpfLookupDigestService(
                        "test-current",
                        null,
                        TEST_SECRET,
                        null
                );
        AuthIdentityRepository repository = new AuthIdentityRepository(
                jdbc,
                digestService
        );

        AuthIdentity legacyIdentity = repository
                .findActiveByCpf(SYNTHETIC_CPF)
                .orElseThrow();
        CpfLookupDigest current = digestService.current(SYNTHETIC_CPF);

        assertThat(legacyIdentity.colaboradorId()).isEqualTo(colaboradorId);
        assertThat(current.value()).isNotEqualTo(legacySha);
        assertThat(jdbc.queryForObject(
                "SELECT cpf_lookup_hmac FROM auth_identity WHERE colaborador_id = ?",
                String.class,
                colaboradorId
        )).isEqualTo(current.value());

        repository.upsertAcademyIdentity(
                colaboradorId,
                SYNTHETIC_CPF,
                "academy@example.invalid"
        );
        assertThat(authenticationEmail(jdbc, colaboradorId))
                .isEqualTo("academy@example.invalid");

        jdbc.update("""
                UPDATE auth_identity
                SET email_autenticacao = 'verificado@example.invalid',
                    email_verificado_em = CURRENT_TIMESTAMP(6),
                    email_fonte = 'ACADEMY',
                    status = 'ATIVA'
                WHERE colaborador_id = ?
                """,
                colaboradorId
        );

        repository.upsertAcademyIdentity(
                colaboradorId,
                SYNTHETIC_CPF,
                "substituto@example.invalid"
        );

        assertThat(authenticationEmail(jdbc, colaboradorId))
                .isEqualTo("verificado@example.invalid");
        assertThat(jdbc.queryForObject(
                "SELECT status FROM auth_identity WHERE colaborador_id = ?",
                String.class,
                colaboradorId
        )).isEqualTo("ATIVA");
    }

    private String authenticationEmail(
            JdbcTemplate jdbc,
            String colaboradorId
    ) {
        return jdbc.queryForObject(
                "SELECT email_autenticacao FROM auth_identity WHERE colaborador_id = ?",
                String.class,
                colaboradorId
        );
    }
}
