package com.projeto.cortex.auth.otp;

import com.projeto.cortex.auth.PapelAcesso;
import com.projeto.cortex.auth.identity.AuthenticationChallengeLookup;
import com.projeto.cortex.email.EmailMessage;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Enumeration-safe request and single-use verification state machine. */
@Service
public class EmailOtpChallengeService {

    private static final String PENDING = "PENDENTE";

    private final AuthRateLimiter rateLimiter;
    private final EmailOtpChallengeIssuer issuer;
    private final EmailOtpChallengeStore challenges;
    private final OtpCryptography cryptography;
    private final OtpPolicy policy;
    private final Supplier<String> challengeIds;

    @Autowired
    public EmailOtpChallengeService(
            AuthRateLimiter rateLimiter,
            EmailOtpChallengeIssuer issuer,
            EmailOtpChallengeStore challenges,
            OtpCryptography cryptography,
            OtpPolicy policy
    ) {
        this(
                rateLimiter,
                issuer,
                challenges,
                cryptography,
                policy,
                () -> UUID.randomUUID().toString()
        );
    }

    EmailOtpChallengeService(
            AuthRateLimiter rateLimiter,
            EmailOtpChallengeIssuer issuer,
            EmailOtpChallengeStore challenges,
            OtpCryptography cryptography,
            OtpPolicy policy,
            Supplier<String> challengeIds
    ) {
        this.rateLimiter = rateLimiter;
        this.issuer = issuer;
        this.challenges = challenges;
        this.cryptography = cryptography;
        this.policy = policy;
        this.challengeIds = challengeIds;
    }

    public EmailOtpChallengeService(
            AuthenticationChallengeLookup identities,
            AuthRateLimiter rateLimiter,
            EmailOtpChallengeStore challenges,
            OtpCryptography cryptography,
            OtpPolicy policy,
            ApplicationEventPublisher events
    ) {
        this(
                identities,
                rateLimiter,
                challenges,
                cryptography,
                policy,
                events,
                new MysqlCpfIdentifierNormalizer(),
                () -> UUID.randomUUID().toString()
        );
    }

    EmailOtpChallengeService(
            AuthenticationChallengeLookup identities,
            AuthRateLimiter rateLimiter,
            EmailOtpChallengeStore challenges,
            OtpCryptography cryptography,
            OtpPolicy policy,
            ApplicationEventPublisher events,
            Supplier<String> challengeIds
    ) {
        this(
                identities,
                rateLimiter,
                challenges,
                cryptography,
                policy,
                events,
                new MysqlCpfIdentifierNormalizer(),
                challengeIds
        );
    }

    EmailOtpChallengeService(
            AuthenticationChallengeLookup identities,
            AuthRateLimiter rateLimiter,
            EmailOtpChallengeStore challenges,
            OtpCryptography cryptography,
            OtpPolicy policy,
            ApplicationEventPublisher events,
            AuthenticationIdentifierNormalizer identifierNormalizer,
            Supplier<String> challengeIds
    ) {
        this(
                rateLimiter,
                new EmailOtpChallengeIssuer(
                        identities,
                        challenges,
                        cryptography,
                        policy,
                        events,
                        identifierNormalizer
                ),
                challenges,
                cryptography,
                policy,
                challengeIds
        );
    }

    @Transactional(propagation = Propagation.NEVER)
    public OtpChallengeResponse request(
            String identifier,
            String clientIp
    ) {
        String challengeId = challengeIds.get();
        OtpChallengeResponse response = OtpChallengeResponse.generic(
                challengeId,
                policy.ttlSeconds()
        );
        if (!rateLimiter.allow(identifier, clientIp)) {
            return response;
        }
        issuer.issue(challengeId, identifier);
        return response;
    }

    @Transactional
    public Optional<AuthenticatedIdentity> verify(
            String challengeId,
            String code
    ) {
        if (!canonicalUuid(challengeId)) {
            return Optional.empty();
        }
        Optional<EmailOtpChallengeStore.LockedChallenge> locked =
                challenges.lockForVerification(challengeId);
        if (locked.isEmpty()) {
            return Optional.empty();
        }
        EmailOtpChallengeStore.LockedChallenge candidate =
                locked.orElseThrow();
        if (!PENDING.equals(candidate.challengeStatus())) {
            return Optional.empty();
        }
        if (!candidate.databaseNow().isBefore(candidate.expiresAt())) {
            challenges.markExpired(challengeId);
            return Optional.empty();
        }
        if (candidate.attempts() >= candidate.maxAttempts()
                || candidate.maxAttempts() < 1) {
            challenges.block(challengeId);
            return Optional.empty();
        }
        if (code == null || !code.matches("[0-9]{6}")) {
            challenges.recordFailedAttempt(challengeId);
            return Optional.empty();
        }
        if (candidate.collaboratorId() == null) {
            cryptography.matchesDigest(
                    cryptography.decoyCodeDigest(challengeId, code),
                    candidate.codeDigest()
            );
            challenges.recordFailedAttempt(challengeId);
            return Optional.empty();
        }

        Optional<PapelAcesso> role = PapelAcesso.fromPersistedExact(
                candidate.persistedRole()
        );
        if (!eligibleAtVerification(candidate, role)) {
            challenges.block(challengeId);
            return Optional.empty();
        }

        String candidateDigest = cryptography.codeDigest(
                challengeId,
                code,
                candidate.authenticationEmail()
        );
        if (!cryptography.matchesDigest(
                candidateDigest,
                candidate.codeDigest()
        )) {
            challenges.recordFailedAttempt(challengeId);
            return Optional.empty();
        }
        int consumed = challenges.consume(
                challengeId,
                candidate.collaboratorId(),
                candidateDigest
        );
        if (consumed != 1) {
            return Optional.empty();
        }
        int activated = challenges.activateIdentity(
                candidate.collaboratorId(),
                candidate.authenticationEmail()
        );
        if (activated != 1) {
            throw new IllegalStateException(
                    "Identidade não pôde ser ativada."
            );
        }
        return Optional.of(new AuthenticatedIdentity(
                candidate.collaboratorId(),
                candidate.collaboratorName(),
                role.orElseThrow()
        ));
    }

    private boolean eligibleAtVerification(
            EmailOtpChallengeStore.LockedChallenge candidate,
            Optional<PapelAcesso> role
    ) {
        return candidate.collaboratorActive()
                && !candidate.collaboratorDeleted()
                && candidate.collaboratorName() != null
                && !candidate.collaboratorName().isBlank()
                && EmailMessage.isValidRecipient(
                        candidate.authenticationEmail()
                )
                && ("PENDENTE".equals(candidate.identityStatus())
                    || "ATIVA".equals(candidate.identityStatus()))
                && role.isPresent();
    }

    private boolean canonicalUuid(String value) {
        try {
            return value != null
                    && UUID.fromString(value).toString().equals(value);
        } catch (RuntimeException exception) {
            return false;
        }
    }
}
