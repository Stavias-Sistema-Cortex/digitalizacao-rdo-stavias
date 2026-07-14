package com.projeto.cortex.auth.otp;

import com.projeto.cortex.auth.PapelAcesso;
import com.projeto.cortex.auth.identity.AmbiguousAuthIdentityException;
import com.projeto.cortex.auth.identity.AuthIdentity;
import com.projeto.cortex.auth.identity.AuthIdentityChallengeLookup;
import com.projeto.cortex.email.EmailMessage;
import java.time.Instant;
import java.util.Optional;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Creates one real or decoy challenge only after rate limiting succeeds. */
@Service
public class EmailOtpChallengeIssuer {

    private final AuthIdentityChallengeLookup identities;
    private final EmailOtpChallengeRepository challenges;
    private final OtpCryptography cryptography;
    private final OtpPolicy policy;
    private final ApplicationEventPublisher events;

    public EmailOtpChallengeIssuer(
            AuthIdentityChallengeLookup identities,
            EmailOtpChallengeRepository challenges,
            OtpCryptography cryptography,
            OtpPolicy policy,
            ApplicationEventPublisher events
    ) {
        this.identities = identities;
        this.challenges = challenges;
        this.cryptography = cryptography;
        this.policy = policy;
        this.events = events;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void issue(String challengeId, String identifier) {
        Optional<AuthIdentity> identity = resolveEligible(identifier);
        String code = cryptography.generateCode();
        String identifierDigest = cryptography.identifierDigest(identifier);
        String codeDigest = identity
                .map(value -> cryptography.codeDigest(
                        challengeId,
                        code,
                        value.emailAutenticacao()
                ))
                .orElseGet(() -> cryptography.decoyCodeDigest(
                        challengeId,
                        code
                ));
        Instant expiresAt = challenges.create(
                challengeId,
                identity.map(AuthIdentity::colaboradorId).orElse(null),
                identifierDigest,
                codeDigest,
                policy.ttlSeconds(),
                policy.maxAttempts()
        );
        events.publishEvent(identity
                .map(value -> OtpDeliveryRequested.deliverable(
                        challengeId,
                        value.emailAutenticacao(),
                        code,
                        expiresAt
                ))
                .orElseGet(OtpDeliveryRequested::decoy));
    }

    private Optional<AuthIdentity> resolveEligible(String identifier) {
        try {
            return identities.find(identifier)
                    .filter(this::eligibleForChallenge);
        } catch (AmbiguousAuthIdentityException exception) {
            return Optional.empty();
        }
    }

    private boolean eligibleForChallenge(AuthIdentity identity) {
        return identity != null
                && identity.colaboradorId() != null
                && !identity.colaboradorId().isBlank()
                && EmailMessage.isValidRecipient(
                        identity.emailAutenticacao()
                )
                && PapelAcesso.fromPersistedExact(identity.papelAcesso())
                        .isPresent();
    }
}
