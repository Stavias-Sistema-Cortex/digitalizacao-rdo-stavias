package com.projeto.cortex.auth.identity;

import com.projeto.cortex.colaboradores.CpfHasher;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Locates authentication identities and upgrades lookup material. Returning an
 * identity from this repository does not authenticate the collaborator; OTP or
 * another verifiable challenge remains mandatory.
 */
@Repository
public class AuthIdentityRepository {

    public static final String MANUAL_PENDING_SOURCE = "MANUAL_PENDENTE";

    private static final String AMBIGUOUS_IDENTITY_MESSAGE =
            "Identidade de autenticação ambígua.";
    private static final String IDENTITY_CONFLICT_MESSAGE =
            "Conflito de identidade de autenticação.";

    private static final RowMapper<AuthIdentity> IDENTITY_ROW_MAPPER =
            (resultSet, rowNumber) -> new AuthIdentity(
                    resultSet.getString("colaborador_id"),
                    resultSet.getString("nome"),
                    resultSet.getString("email_autenticacao"),
                    resultSet.getString("papel_acesso")
            );

    private final JdbcTemplate jdbcTemplate;
    private final CpfLookupDigestService digestService;

    public AuthIdentityRepository(
            JdbcTemplate jdbcTemplate,
            CpfLookupDigestService digestService
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.digestService = digestService;
    }

    @Transactional
    public Optional<AuthIdentity> findActiveByCpf(String cpfRaw) {
        List<CpfLookupDigest> candidates = digestService.candidates(cpfRaw);
        if (candidates.isEmpty()) {
            throw new IllegalStateException(
                    "Configuração de CPF HMAC sem chave atual."
            );
        }

        CpfLookupDigest current = candidates.get(0);
        List<AuthIdentity> owners = new ArrayList<>();
        boolean currentDigestMatched = false;
        for (int index = 0; index < candidates.size(); index++) {
            List<AuthIdentity> matches = findOwnersByDigest(
                    candidates.get(index)
            );
            owners.addAll(matches);
            if (index == 0 && !matches.isEmpty()) {
                currentDigestMatched = true;
            }
        }

        String digits = CpfNormalizer.requireValid(cpfRaw);
        owners.addAll(findOwnersByLegacySha(
                CpfHasher.hashDeDigitos(digits)
        ));

        Optional<AuthIdentity> owner = uniqueOwner(owners);
        if (owner.isEmpty()) {
            return Optional.empty();
        }

        Optional<AuthIdentity> eligible = findEligibleById(
                owner.orElseThrow().colaboradorId()
        );
        if (eligible.isPresent() && !currentDigestMatched) {
            upgradeToCurrent(
                    eligible.orElseThrow().colaboradorId(),
                    current
            );
        }
        return eligible;
    }

    @Transactional
    public void upsertAcademyIdentity(
            String colaboradorId,
            String cpfRaw,
            String academyEmail
    ) {
        CpfLookupDigest digest = digestService.current(cpfRaw);
        writeAcademyIdentity(
                colaboradorId,
                digest,
                academyEmail
        );
    }

    /**
     * Locates an existing active collaborator through the protected CPF lookup
     * boundary and assigns an e-mail that still requires OTP verification.
     */
    @Transactional
    public void upsertProvisionedIdentity(
            String cpfRaw,
            String email,
            String source
    ) {
        if (!MANUAL_PENDING_SOURCE.equals(source)
                || email == null
                || email.isBlank()
                || email.length() > 320
                || email.contains("\r")
                || email.contains("\n")) {
            throw unavailableForProvisioning();
        }

        String cpf = CpfNormalizer.requireValid(cpfRaw);
        AuthIdentity identity = findActiveByCpf(cpf)
                .orElseThrow(this::unavailableForProvisioning);
        CpfLookupDigest digest = digestService.current(cpf);
        executeProtectedWrite(
                identity.colaboradorId(),
                digest,
                exists -> {
                    if (!exists) {
                        throw unavailableForProvisioning();
                    }
                    if (!lockProvisioningEligible(
                            identity.colaboradorId()
                    )) {
                        throw unavailableForProvisioning();
                    }
                    jdbcTemplate.update("""
                            UPDATE auth_identity
                            SET cpf_lookup_hmac = ?,
                                cpf_lookup_key_id = ?,
                                email_autenticacao = TRIM(?),
                                email_verificado_em = NULL,
                                email_fonte = ?,
                                status = 'PENDENTE',
                                versao_linha = versao_linha + 1
                            WHERE colaborador_id = ?
                            """,
                            digest.value(),
                            digest.keyId(),
                            email,
                            source,
                            identity.colaboradorId()
                    );
                }
        );
    }

    private boolean lockProvisioningEligible(String colaboradorId) {
        List<String> eligible = jdbcTemplate.query("""
                SELECT identity.colaborador_id
                FROM auth_identity identity
                INNER JOIN colaborador
                    ON colaborador.id = identity.colaborador_id
                WHERE identity.colaborador_id = ?
                  AND colaborador.ativo = 1
                  AND colaborador.deletado_em IS NULL
                  AND identity.status <> 'BLOQUEADA'
                FOR UPDATE
                """,
                (resultSet, rowNumber) -> resultSet.getString(1),
                colaboradorId
        );
        return eligible.size() == 1;
    }

    private List<AuthIdentity> findOwnersByDigest(CpfLookupDigest digest) {
        return jdbcTemplate.query("""
                SELECT
                    colaborador.id AS colaborador_id,
                    colaborador.nome,
                    identity.email_autenticacao,
                    colaborador.papel_acesso
                FROM auth_identity identity
                INNER JOIN colaborador
                    ON colaborador.id = identity.colaborador_id
                WHERE identity.cpf_lookup_key_id = ?
                  AND identity.cpf_lookup_hmac = ?
                """,
                IDENTITY_ROW_MAPPER,
                digest.keyId(),
                digest.value()
        );
    }

    private List<AuthIdentity> findOwnersByLegacySha(String legacySha) {
        return jdbcTemplate.query("""
                SELECT
                    colaborador.id AS colaborador_id,
                    colaborador.nome,
                    identity.email_autenticacao,
                    colaborador.papel_acesso
                FROM colaborador
                LEFT JOIN auth_identity identity
                    ON identity.colaborador_id = colaborador.id
                WHERE colaborador.cpf_hash = ?
                LIMIT 2
                """,
                IDENTITY_ROW_MAPPER,
                legacySha
        );
    }

    private Optional<AuthIdentity> findEligibleById(String colaboradorId) {
        return uniqueOwner(jdbcTemplate.query("""
                SELECT
                    colaborador.id AS colaborador_id,
                    colaborador.nome,
                    identity.email_autenticacao,
                    colaborador.papel_acesso
                FROM colaborador
                LEFT JOIN auth_identity identity
                    ON identity.colaborador_id = colaborador.id
                WHERE colaborador.id = ?
                  AND colaborador.ativo = 1
                  AND colaborador.deletado_em IS NULL
                  AND (
                      identity.status IS NULL
                      OR identity.status <> 'BLOQUEADA'
                  )
                """,
                IDENTITY_ROW_MAPPER,
                colaboradorId
        ));
    }

    private Optional<AuthIdentity> uniqueOwner(
            List<AuthIdentity> identities
    ) {
        Map<String, AuthIdentity> owners = new LinkedHashMap<>();
        for (AuthIdentity identity : identities) {
            owners.putIfAbsent(identity.colaboradorId(), identity);
        }
        if (owners.size() > 1) {
            throw new AmbiguousAuthIdentityException(
                    AMBIGUOUS_IDENTITY_MESSAGE
            );
        }
        return owners.values().stream().findFirst();
    }

    private void upgradeToCurrent(
            String colaboradorId,
            CpfLookupDigest current
    ) {
        executeProtectedWrite(colaboradorId, current, exists -> {
            if (exists) {
                jdbcTemplate.update("""
                        UPDATE auth_identity
                        SET cpf_lookup_hmac = ?,
                            cpf_lookup_key_id = ?,
                            versao_linha = versao_linha + 1
                        WHERE colaborador_id = ?
                        """,
                        current.value(),
                        current.keyId(),
                        colaboradorId
                );
                return;
            }
            jdbcTemplate.update("""
                    INSERT INTO auth_identity (
                        colaborador_id,
                        cpf_lookup_hmac,
                        cpf_lookup_key_id,
                        status
                    ) VALUES (?, ?, ?, 'PENDENTE')
                    """,
                    colaboradorId,
                    current.value(),
                    current.keyId()
            );
        });
    }

    private void writeAcademyIdentity(
            String colaboradorId,
            CpfLookupDigest digest,
            String academyEmail
    ) {
        executeProtectedWrite(colaboradorId, digest, exists -> {
            if (!exists) {
                jdbcTemplate.update("""
                        INSERT INTO auth_identity (
                            colaborador_id,
                            cpf_lookup_hmac,
                            cpf_lookup_key_id,
                            email_autenticacao,
                            email_verificado_em,
                            email_fonte,
                            status
                        ) VALUES (
                            ?, ?, ?, NULLIF(TRIM(?), ''),
                            NULL, 'ACADEMY', 'PENDENTE'
                        )
                        """,
                        colaboradorId,
                        digest.value(),
                        digest.keyId(),
                        academyEmail
                );
                return;
            }
            jdbcTemplate.update("""
                    UPDATE auth_identity
                    SET cpf_lookup_hmac = ?,
                        cpf_lookup_key_id = ?,
                        email_autenticacao = CASE
                            WHEN email_verificado_em IS NOT NULL
                              OR email_fonte IN (
                                  'MANUAL_VERIFICADO',
                                  'MANUAL_PENDENTE'
                              )
                                THEN email_autenticacao
                            ELSE COALESCE(
                                NULLIF(TRIM(?), ''),
                                email_autenticacao
                            )
                        END,
                        email_fonte = CASE
                            WHEN email_verificado_em IS NOT NULL
                              OR email_fonte IN (
                                  'MANUAL_VERIFICADO',
                                  'MANUAL_PENDENTE'
                              )
                                THEN email_fonte
                            WHEN NULLIF(TRIM(?), '') IS NOT NULL
                                THEN 'ACADEMY'
                            ELSE email_fonte
                        END,
                        versao_linha = versao_linha + 1
                    WHERE colaborador_id = ?
                    """,
                    digest.value(),
                    digest.keyId(),
                    academyEmail,
                    academyEmail,
                    colaboradorId
            );
        });
    }

    private void executeProtectedWrite(
            String colaboradorId,
            CpfLookupDigest digest,
            IdentityWrite write
    ) {
        try {
            List<String> digestOwners = jdbcTemplate.query("""
                    SELECT colaborador_id
                    FROM auth_identity
                    WHERE cpf_lookup_key_id = ?
                      AND cpf_lookup_hmac = ?
                    FOR UPDATE
                    """,
                    (resultSet, rowNumber) -> resultSet.getString(1),
                    digest.keyId(),
                    digest.value()
            );
            if (digestOwners.stream().anyMatch(
                    ownerId -> !colaboradorId.equals(ownerId)
            )) {
                throw identityConflict();
            }

            List<String> collaboratorRows = jdbcTemplate.query("""
                    SELECT colaborador_id
                    FROM auth_identity
                    WHERE colaborador_id = ?
                    FOR UPDATE
                    """,
                    (resultSet, rowNumber) -> resultSet.getString(1),
                    colaboradorId
            );
            write.execute(!collaboratorRows.isEmpty());
        } catch (DataIntegrityViolationException
                 | PessimisticLockingFailureException exception) {
            throw identityConflict();
        }
    }

    private IllegalStateException identityConflict() {
        return new IllegalStateException(IDENTITY_CONFLICT_MESSAGE);
    }

    private IllegalStateException unavailableForProvisioning() {
        return new IllegalStateException(
                "Identidade indisponível para provisionamento."
        );
    }

    @FunctionalInterface
    private interface IdentityWrite {

        void execute(boolean exists);
    }
}
