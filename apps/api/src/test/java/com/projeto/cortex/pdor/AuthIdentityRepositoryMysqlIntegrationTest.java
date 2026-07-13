package com.projeto.cortex.pdor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
    private static final String PREVIOUS_TEST_SECRET =
            "test-only-mysql-previous-hmac-secret-0002";

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

    @Test
    void academyCollisionNeverReassignsAnotherCollaborator() {
        database = PdorMysqlTestDatabase.create("auth_identity_owner");
        database.migrate();

        JdbcTemplate jdbc = new JdbcTemplate(database.dataSource());
        String ownerId = insertColaborador(
                jdbc,
                "academy-owner",
                "Proprietário Sintético",
                null,
                true
        );
        String contenderId = insertColaborador(
                jdbc,
                "academy-contender",
                "Contendor Sintético",
                null,
                true
        );
        HmacCpfLookupDigestService digestService = digestService();
        AuthIdentityRepository repository = new AuthIdentityRepository(
                jdbc,
                digestService
        );

        repository.upsertAcademyIdentity(
                ownerId,
                SYNTHETIC_CPF,
                "owner@example.invalid"
        );
        CpfLookupDigest digest = digestService.current(SYNTHETIC_CPF);

        assertThatThrownBy(() -> repository.upsertAcademyIdentity(
                contenderId,
                SYNTHETIC_CPF,
                "contender@example.invalid"
        )).isInstanceOf(IllegalStateException.class)
                .hasMessage("Conflito de identidade de autenticação.");

        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM auth_identity",
                Integer.class
        )).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT colaborador_id FROM auth_identity"
                        + " WHERE cpf_lookup_key_id = ?"
                        + " AND cpf_lookup_hmac = ?",
                String.class,
                digest.keyId(),
                digest.value()
        )).isEqualTo(ownerId);
        assertThat(authenticationEmail(jdbc, ownerId))
                .isEqualTo("owner@example.invalid");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM auth_identity WHERE colaborador_id = ?",
                Integer.class,
                contenderId
        )).isZero();
    }

    @Test
    void currentAndPreviousOwnersAreAmbiguousBeforeEligibilityFiltering() {
        database = PdorMysqlTestDatabase.create("auth_identity_rotation");
        database.migrate();

        JdbcTemplate jdbc = new JdbcTemplate(database.dataSource());
        String currentOwner = insertColaborador(
                jdbc,
                "current-inactive",
                "Atual Inativo Sintético",
                null,
                false
        );
        String previousOwner = insertColaborador(
                jdbc,
                "previous-active",
                "Anterior Ativo Sintético",
                null,
                true
        );
        HmacCpfLookupDigestService digestService = rotatingDigestService();
        CpfLookupDigest current = digestService
                .candidates(SYNTHETIC_CPF)
                .get(0);
        CpfLookupDigest previous = digestService
                .candidates(SYNTHETIC_CPF)
                .get(1);
        insertIdentity(
                jdbc,
                currentOwner,
                current,
                "current@example.invalid",
                "BLOQUEADA"
        );
        insertIdentity(
                jdbc,
                previousOwner,
                previous,
                "previous@example.invalid",
                "ATIVA"
        );
        AuthIdentityRepository repository = new AuthIdentityRepository(
                jdbc,
                digestService
        );

        assertThatThrownBy(() -> repository.findActiveByCpf(SYNTHETIC_CPF))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Identidade de autenticação ambígua.");

        assertThat(ownerOf(jdbc, current)).isEqualTo(currentOwner);
        assertThat(ownerOf(jdbc, previous)).isEqualTo(previousOwner);
    }

    @Test
    void duplicateLegacyOwnersAreAmbiguousBeforeEligibilityFiltering() {
        database = PdorMysqlTestDatabase.create("auth_identity_legacy");
        database.migrate();

        JdbcTemplate jdbc = new JdbcTemplate(database.dataSource());
        String legacySha = CpfHasher.hashDeDigitos(SYNTHETIC_CPF);
        String activeOwner = insertColaborador(
                jdbc,
                "legacy-active",
                "Legado Ativo Sintético",
                legacySha,
                true
        );
        String blockedOwner = insertColaborador(
                jdbc,
                "legacy-blocked",
                "Legado Bloqueado Sintético",
                legacySha,
                false
        );
        jdbc.update("""
                INSERT INTO auth_identity (
                    colaborador_id,
                    email_autenticacao,
                    status
                ) VALUES (?, 'blocked@example.invalid', 'BLOQUEADA')
                """, blockedOwner);
        AuthIdentityRepository repository = new AuthIdentityRepository(
                jdbc,
                digestService()
        );

        assertThatThrownBy(() -> repository.findActiveByCpf(SYNTHETIC_CPF))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Identidade de autenticação ambígua.");

        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM auth_identity WHERE colaborador_id = ?",
                Integer.class,
                activeOwner
        )).isZero();
    }

    private HmacCpfLookupDigestService digestService() {
        return new HmacCpfLookupDigestService(
                "test-current",
                null,
                TEST_SECRET,
                null
        );
    }

    private HmacCpfLookupDigestService rotatingDigestService() {
        return new HmacCpfLookupDigestService(
                "test-current",
                null,
                TEST_SECRET,
                new HmacCpfLookupDigestService.PreviousKey(
                        "test-previous",
                        null,
                        PREVIOUS_TEST_SECRET
                )
        );
    }

    private String insertColaborador(
            JdbcTemplate jdbc,
            String sourcePk,
            String name,
            String legacySha,
            boolean active
    ) {
        String colaboradorId = UUID.randomUUID().toString();
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
                ) VALUES (?, 'teste', 'teste', ?, ?, ?, 'BETA', ?)
                """,
                colaboradorId,
                sourcePk,
                legacySha,
                name,
                active
        );
        return colaboradorId;
    }

    private void insertIdentity(
            JdbcTemplate jdbc,
            String colaboradorId,
            CpfLookupDigest digest,
            String email,
            String status
    ) {
        jdbc.update("""
                INSERT INTO auth_identity (
                    colaborador_id,
                    cpf_lookup_hmac,
                    cpf_lookup_key_id,
                    email_autenticacao,
                    status
                ) VALUES (?, ?, ?, ?, ?)
                """,
                colaboradorId,
                digest.value(),
                digest.keyId(),
                email,
                status
        );
    }

    private String ownerOf(JdbcTemplate jdbc, CpfLookupDigest digest) {
        return jdbc.queryForObject(
                "SELECT colaborador_id FROM auth_identity"
                        + " WHERE cpf_lookup_key_id = ?"
                        + " AND cpf_lookup_hmac = ?",
                String.class,
                digest.keyId(),
                digest.value()
        );
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
