package com.projeto.cortex.auth.password;

import com.projeto.cortex.auth.identity.AuthIdentity;
import com.projeto.cortex.auth.identity.AuthIdentityRepository;
import com.projeto.cortex.auth.identity.CpfNormalizer;
import com.projeto.cortex.auth.session.AuthSessionRepository;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** First-access and reset state machine for individual passwords. */
@Service
@Profile("postgresql-common")
public final class PasswordSetupService {

    static final int TTL_SECONDS = 1800;
    static final int MAX_ATTEMPTS = 5;
    private static final String PENDING = "PENDENTE";
    private static final String SESSION_REVOCATION_REASON =
            "SENHA_DEFINIDA_OU_REDEFINIDA";
    private static final String RESET_SESSION_REVOCATION_REASON =
            "REDEFINICAO_DE_SENHA_SOLICITADA";

    private final PasswordSetupStore setups;
    private final PasswordCredentialRepository credentials;
    private final AuthIdentityRepository identities;
    private final PasswordHashService hashes;
    private final PasswordPolicy passwords;
    private final AuthSessionRepository sessions;
    private final PasswordSetupCodeCryptography codes;
    private final Supplier<String> challengeIds;

    @Autowired
    public PasswordSetupService(
            PasswordSetupStore setups,
            PasswordCredentialRepository credentials,
            AuthIdentityRepository identities,
            PasswordHashService hashes,
            PasswordPolicy passwords,
            AuthSessionRepository sessions,
            PasswordSetupCodeCryptography codes
    ) {
        this(
                setups,
                credentials,
                identities,
                hashes,
                passwords,
                sessions,
                codes,
                () -> UUID.randomUUID().toString()
        );
    }

    PasswordSetupService(
            PasswordSetupStore setups,
            PasswordCredentialRepository credentials,
            AuthIdentityRepository identities,
            PasswordHashService hashes,
            PasswordPolicy passwords,
            AuthSessionRepository sessions,
            PasswordSetupCodeCryptography codes,
            Supplier<String> challengeIds
    ) {
        this.setups = setups;
        this.credentials = credentials;
        this.identities = identities;
        this.hashes = hashes;
        this.passwords = passwords;
        this.sessions = sessions;
        this.codes = codes;
        this.challengeIds = challengeIds;
    }

    @Transactional
    public IssuedPasswordSetupCode issue(
            String actorId,
            String collaboratorId
    ) {
        String actor = requireUuid(actorId, "administrador");
        String targetId = requireUuid(collaboratorId, "colaborador");
        if (!setups.canManageAccess(actor)) {
            throw status(
                    HttpStatus.FORBIDDEN,
                    "A operação exige administração de acessos."
            );
        }
        PasswordSetupTarget target = setups.findActiveAcademyTarget(targetId)
                .orElseThrow(() -> status(
                        HttpStatus.NOT_FOUND,
                        "Colaborador ativo do Academy não encontrado."
                ));
        PasswordSetupPurpose purpose = credentials
                .findHashByCollaboratorId(targetId)
                .isPresent()
                ? PasswordSetupPurpose.RESET
                : PasswordSetupPurpose.FIRST_ACCESS;
        setups.invalidatePending(targetId, "NOVO_CODIGO_EMITIDO");

        String challengeId = requireUuid(challengeIds.get(), "desafio");
        String rawCode = codes.generateCode();
        String codeDigest = codes.digest(challengeId, rawCode);
        var expiresAt = setups.create(
                challengeId,
                targetId,
                codeDigest,
                purpose,
                actor,
                TTL_SECONDS,
                MAX_ATTEMPTS
        );
        setups.audit(
                purpose == PasswordSetupPurpose.RESET
                        ? "CODIGO_RESET_EMITIDO"
                        : "CODIGO_PRIMEIRO_ACESSO_EMITIDO",
                actor,
                targetId,
                challengeId
        );
        if (purpose == PasswordSetupPurpose.RESET) {
            sessions.revokeAllByCollaboratorId(
                    targetId,
                    RESET_SESSION_REVOCATION_REASON
            );
        }
        return new IssuedPasswordSetupCode(
                target.collaboratorId(),
                target.name(),
                rawCode,
                purpose,
                expiresAt
        );
    }

    @Transactional
    public boolean complete(String cpf, String code, String newPassword) {
        String acceptedPassword = passwords.requireValid(newPassword);
        Optional<AuthIdentity> identity = findActiveIdentity(cpf);
        if (identity.isEmpty()) {
            return false;
        }
        String collaboratorId = identity.get().colaboradorId();
        Optional<LockedPasswordSetupChallenge> locked =
                setups.lockLatestPending(collaboratorId);
        if (locked.isEmpty()) {
            return false;
        }
        LockedPasswordSetupChallenge challenge = locked.get();
        if (!PENDING.equals(challenge.status())) {
            return false;
        }
        if (!challenge.databaseNow().isBefore(challenge.expiresAt())) {
            setups.markExpired(challenge.challengeId());
            return false;
        }
        if (challenge.maxAttempts() < 1
                || challenge.attempts() >= challenge.maxAttempts()) {
            setups.block(challenge.challengeId());
            return false;
        }
        if (!codes.matches(
                challenge.challengeId(),
                code,
                challenge.codeDigest()
        )) {
            setups.recordFailedAttempt(challenge.challengeId());
            return false;
        }

        String passwordHash = hashes.hash(acceptedPassword);
        credentials.upsertHash(collaboratorId, passwordHash);
        if (setups.activateIdentity(collaboratorId) != 1) {
            throw new IllegalStateException(
                    "Identidade não pôde ser ativada para senha."
            );
        }
        if (setups.consume(challenge.challengeId()) != 1) {
            throw new IllegalStateException(
                    "Código temporário não pôde ser consumido."
            );
        }
        sessions.revokeAllByCollaboratorId(
                collaboratorId,
                SESSION_REVOCATION_REASON
        );
        setups.audit(
                "SENHA_DEFINIDA",
                collaboratorId,
                collaboratorId,
                challenge.challengeId()
        );
        return true;
    }

    private Optional<AuthIdentity> findActiveIdentity(String cpf) {
        try {
            return identities.findAcademyPasswordSetupCandidateByCpf(
                    CpfNormalizer.requireValid(cpf)
            );
        } catch (IllegalArgumentException exception) {
            return Optional.empty();
        }
    }

    private String requireUuid(String value, String label) {
        try {
            String normalized = value.strip();
            if (!UUID.fromString(normalized).toString()
                    .equalsIgnoreCase(normalized)) {
                throw new IllegalArgumentException();
            }
            return normalized;
        } catch (RuntimeException exception) {
            throw status(
                    HttpStatus.BAD_REQUEST,
                    "Identificador de " + label + " inválido."
            );
        }
    }

    private ResponseStatusException status(HttpStatus status, String message) {
        return new ResponseStatusException(status, message);
    }
}
