package com.projeto.cortex.auth.identity;

import com.projeto.cortex.colaboradores.CpfHasher;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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

    /**
     * Read-only local-development authentication lookup restricted to an
     * already active Academy identity. It deliberately excludes legacy SHA
     * material and never upgrades persisted HMAC data.
     */
    public Optional<AuthIdentity> findActiveAcademyByCpf(String cpfRaw) {
        AuthChallengeLookupMaterial material =
                digestService.challengeLookup(cpfRaw);
        CpfLookupDigest current = material.candidates().get(0);
        CpfLookupDigest previous = material.candidates().size() == 2
                ? material.candidates().get(1)
                : current;
        List<AuthIdentity> identities = jdbcTemplate.query("""
                SELECT
                    colaborador.id AS colaborador_id,
                    colaborador.nome,
                    identity.email_autenticacao,
                    colaborador.papel_acesso
                FROM auth_identity identity
                INNER JOIN colaborador
                    ON colaborador.id = identity.colaborador_id
                WHERE (
                    (
                        identity.cpf_lookup_key_id = ?
                        AND identity.cpf_lookup_hmac = ?
                    ) OR (
                        identity.cpf_lookup_key_id = ?
                        AND identity.cpf_lookup_hmac = ?
                    )
                )
                  AND colaborador.banco_origem = 'dbstavias_acad'
                  AND colaborador.tabela_origem = 'usuarios'
                  AND colaborador.ativo = TRUE
                  AND colaborador.deletado_em IS NULL
                  AND colaborador.papel_acesso IN ('ALFA', 'BETA')
                  AND identity.status = 'ATIVA'
                LIMIT 2
                """,
                IDENTITY_ROW_MAPPER,
                current.keyId(),
                current.value(),
                previous.keyId(),
                previous.value()
        );
        return identities.size() == 1
                ? Optional.of(identities.get(0))
                : Optional.empty();
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
     * Keeps the historical identity row but removes login ownership when an
     * Academy user leaves the eligible active/valid-CPF set. Source-owned,
     * unverified email is releasable; manual or verified email is preserved.
     */
    @Transactional
    public void revokeAcademyCpfLogin(String colaboradorId) {
        PostgresqlAuthIdentityMutationLock.acquire(jdbcTemplate);
        jdbcTemplate.update("""
                UPDATE auth_identity
                SET cpf_lookup_hmac = NULL,
                    cpf_lookup_key_id = NULL,
                    email_autenticacao = CASE
                        WHEN email_verificado_em IS NULL
                          AND email_fonte = 'ACADEMY'
                            THEN NULL
                        ELSE email_autenticacao
                    END,
                    status = CASE
                        WHEN status = 'BLOQUEADA' THEN status
                        ELSE 'PENDENTE'
                    END,
                    versao_linha = versao_linha + 1
                WHERE colaborador_id = ?
                  AND status <> 'BLOQUEADA'
                  AND (
                      cpf_lookup_hmac IS NOT NULL
                      OR cpf_lookup_key_id IS NOT NULL
                      OR status NOT IN ('PENDENTE', 'BLOQUEADA')
                      OR (
                          email_autenticacao IS NOT NULL
                          AND email_verificado_em IS NULL
                          AND email_fonte = 'ACADEMY'
                      )
                  )
                """,
                colaboradorId
        );
    }

    /**
     * Validates the final protected identity ownership for one complete
     * Academy snapshot before the collaborator import performs domain writes.
     */
    @Transactional
    public void validateAcademyIdentityBatch(
            List<AcademyIdentityCandidate> candidates
    ) {
        validateAcademyIdentityBatch(candidates, Set.of());
    }

    @Transactional
    public void validateAcademyIdentityBatch(
            List<AcademyIdentityCandidate> candidates,
            Set<String> releasedCollaboratorIds
    ) {
        PostgresqlAuthIdentityMutationLock.acquire(jdbcTemplate);
        if (candidates == null || candidates.isEmpty()) {
            return;
        }
        Set<String> released = releasedCollaboratorIds == null
                ? Set.of()
                : Set.copyOf(releasedCollaboratorIds);
        List<PersistedAcademyIdentity> persisted = jdbcTemplate.query("""
                SELECT
                    colaborador_id,
                    cpf_lookup_key_id,
                    cpf_lookup_hmac,
                    email_autenticacao,
                    email_verificado_em IS NOT NULL AS email_verified,
                    email_fonte,
                    status
                FROM auth_identity
                """,
                (resultSet, rowNumber) -> new PersistedAcademyIdentity(
                        resultSet.getString("colaborador_id"),
                        resultSet.getString("cpf_lookup_key_id"),
                        resultSet.getString("cpf_lookup_hmac"),
                        resultSet.getString("email_autenticacao"),
                        resultSet.getBoolean("email_verified"),
                        resultSet.getString("email_fonte"),
                        resultSet.getString("status")
                )
        );

        Map<String, PersistedAcademyIdentity> persistedByCollaborator =
                new HashMap<>();
        Map<DigestOwnerKey, String> persistedDigestOwners =
                new HashMap<>();
        Map<String, String> persistedEmailOwners = new HashMap<>();
        for (PersistedAcademyIdentity identity : persisted) {
            persistedByCollaborator.put(
                    identity.colaboradorId(),
                    identity
            );
            boolean releasedOwner =
                    released.contains(identity.colaboradorId())
                            && !identity.blocked();
            if (!releasedOwner
                    && identity.keyId() != null
                    && identity.hmac() != null) {
                persistedDigestOwners.put(
                        new DigestOwnerKey(
                                identity.keyId(),
                                identity.hmac()
                        ),
                        identity.colaboradorId()
                );
            }
            String normalizedEmail = normalizeIdentityEmail(
                    identity.email()
            );
            boolean releasedAcademyEmail =
                    releasedOwner
                            && releasableAcademyEmail(identity);
            if (normalizedEmail != null && !releasedAcademyEmail) {
                persistedEmailOwners.put(
                        normalizedEmail,
                        identity.colaboradorId()
                );
            }
        }
        Map<String, List<String>> persistedLegacyCpfOwners =
                new HashMap<>();
        Set<String> blockedCollaboratorIds = persisted.stream()
                .filter(PersistedAcademyIdentity::blocked)
                .map(PersistedAcademyIdentity::colaboradorId)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        jdbcTemplate.query("""
                SELECT id, cpf_hash
                FROM colaborador
                WHERE cpf_hash IS NOT NULL
                """,
                resultSet -> {
                    String ownerId = resultSet.getString("id");
                    if (released.contains(ownerId)
                            && !blockedCollaboratorIds.contains(ownerId)) {
                        return;
                    }
                    persistedLegacyCpfOwners.computeIfAbsent(
                            resultSet.getString("cpf_hash"),
                            ignored -> new ArrayList<>()
                    ).add(ownerId);
                }
        );

        Map<String, String> candidateIds = new HashMap<>();
        Map<DigestOwnerKey, String> desiredDigestOwners = new HashMap<>();
        Map<String, String> desiredLegacyCpfOwners = new HashMap<>();
        Map<String, String> desiredEmailOwners = new HashMap<>();
        for (AcademyIdentityCandidate candidate : candidates) {
            if (candidateIds.putIfAbsent(
                    candidate.colaboradorId(),
                    candidate.colaboradorId()
            ) != null) {
                throw identityConflict();
            }

            String canonicalCpf = CpfNormalizer.requireValid(
                    candidate.cpfRaw()
            );
            List<CpfLookupDigest> digestCandidates =
                    digestService.candidates(canonicalCpf);
            if (digestCandidates.isEmpty()) {
                throw identityConflict();
            }
            for (CpfLookupDigest digest : digestCandidates) {
                DigestOwnerKey digestKey = new DigestOwnerKey(
                        digest.keyId(),
                        digest.value()
                );
                rejectDifferentOwner(
                        persistedDigestOwners.get(digestKey),
                        candidate.colaboradorId()
                );
                rejectDifferentOwner(
                        desiredDigestOwners.putIfAbsent(
                                digestKey,
                                candidate.colaboradorId()
                        ),
                        candidate.colaboradorId()
                );
            }

            String legacyCpfHash = CpfHasher.hashDeDigitos(canonicalCpf);
            rejectDifferentOwners(
                    persistedLegacyCpfOwners.get(legacyCpfHash),
                    candidate.colaboradorId()
            );
            rejectDifferentOwner(
                    desiredLegacyCpfOwners.putIfAbsent(
                            legacyCpfHash,
                            candidate.colaboradorId()
                    ),
                    candidate.colaboradorId()
            );

            PersistedAcademyIdentity current =
                    persistedByCollaborator.get(
                            candidate.colaboradorId()
                    );
            String effectiveEmail = effectiveAcademyEmail(
                    current,
                    candidate.academyEmail()
            );
            if (effectiveEmail == null) {
                continue;
            }
            rejectDifferentOwner(
                    persistedEmailOwners.get(effectiveEmail),
                    candidate.colaboradorId()
            );
            rejectDifferentOwner(
                    desiredEmailOwners.putIfAbsent(
                            effectiveEmail,
                            candidate.colaboradorId()
                    ),
                    candidate.colaboradorId()
            );
        }
    }

    private String effectiveAcademyEmail(
            PersistedAcademyIdentity current,
            String academyEmail
    ) {
        if (current != null && (
                current.emailVerified()
                        || "MANUAL_VERIFICADO".equals(current.emailSource())
                        || MANUAL_PENDING_SOURCE.equals(current.emailSource())
        )) {
            return normalizeIdentityEmail(current.email());
        }
        String normalizedAcademy = normalizeIdentityEmail(academyEmail);
        if (normalizedAcademy != null) {
            return normalizedAcademy;
        }
        return current == null
                ? null
                : normalizeIdentityEmail(current.email());
    }

    private boolean releasableAcademyEmail(
            PersistedAcademyIdentity identity
    ) {
        return !identity.blocked()
                && !identity.emailVerified()
                && "ACADEMY".equals(identity.emailSource());
    }

    private String normalizeIdentityEmail(String email) {
        if (email == null || email.isBlank()) {
            return null;
        }
        return email.trim().toLowerCase(Locale.ROOT);
    }

    private void rejectDifferentOwner(
            String existingOwner,
            String expectedOwner
    ) {
        if (existingOwner != null && !existingOwner.equals(expectedOwner)) {
            throw identityConflict();
        }
    }

    private void rejectDifferentOwners(
            List<String> existingOwners,
            String expectedOwner
    ) {
        if (existingOwners == null) {
            return;
        }
        for (String existingOwner : existingOwners) {
            rejectDifferentOwner(existingOwner, expectedOwner);
        }
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
                  AND colaborador.ativo = TRUE
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
                  AND colaborador.ativo = TRUE
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
                            NULL, 'ACADEMY', 'ATIVA'
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
                        status = CASE
                            WHEN status = 'BLOQUEADA' THEN status
                            ELSE 'ATIVA'
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
            PostgresqlAuthIdentityMutationLock.acquire(jdbcTemplate);
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

    public record AcademyIdentityCandidate(
            String colaboradorId,
            String cpfRaw,
            String academyEmail
    ) {

        @Override
        public String toString() {
            return "AcademyIdentityCandidate[protected]";
        }
    }

    private record PersistedAcademyIdentity(
            String colaboradorId,
            String keyId,
            String hmac,
            String email,
            boolean emailVerified,
            String emailSource,
            String status
    ) {

        private boolean blocked() {
            return "BLOQUEADA".equals(status);
        }
    }

    private record DigestOwnerKey(String keyId, String hmac) {}

    @FunctionalInterface
    private interface IdentityWrite {

        void execute(boolean exists);
    }
}
