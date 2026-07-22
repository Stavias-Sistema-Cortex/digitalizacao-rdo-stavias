package com.projeto.cortex.intelligence.stavia.context;

import com.projeto.cortex.intelligence.stavia.model.StaviaContext;
import com.projeto.cortex.intelligence.stavia.model.StaviaEvidence;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;

@Component
public class StaviaContextBuilder {

    public StaviaContext build(StaviaRawContext rawContext) {
        if (rawContext == null) {
            throw new IllegalArgumentException(
                    "O contexto bruto da Stav.IA deve ser informado."
            );
        }

        List<StaviaEvidence> evidences = rawContext.evidences()
                .stream()
                .filter(Objects::nonNull)
                .filter(this::hasMinimumData)
                .filter(evidence ->
                        belongsToRequestedScope(
                                rawContext.obraId(),
                                evidence
                        )
                )
                .filter(evidence ->
                        isAuthorized(
                                rawContext,
                                evidence.requiredPermission()
                        )
                )
                .map(this::toEvidence)
                .toList();

        return new StaviaContext(
                rawContext.permissions(),
                evidences
        );
    }

    private boolean hasMinimumData(
            StaviaRawContext.RawEvidence evidence
    ) {
        return hasText(evidence.type())
                && hasText(evidence.id())
                && hasText(evidence.summary());
    }

    private boolean belongsToRequestedScope(
            String requestedObraId,
            StaviaRawContext.RawEvidence evidence
    ) {
        if (!hasText(requestedObraId)) {
            return true;
        }

        if (Objects.equals(
                requestedObraId.trim(),
                normalizeOptional(evidence.obraId())
        )) {
            return true;
        }

        return Boolean.TRUE.equals(
                evidence.attributes().get("crossWorksiteAuthorized")
        );
    }

    private boolean isAuthorized(
            StaviaRawContext rawContext,
            String requiredPermission
    ) {
        if (!hasText(requiredPermission)) {
            return true;
        }

        return rawContext.permissions().contains(
                requiredPermission.trim()
        );
    }

    private StaviaEvidence toEvidence(
            StaviaRawContext.RawEvidence rawEvidence
    ) {
        return new StaviaEvidence(
                rawEvidence.type(),
                rawEvidence.id(),
                rawEvidence.summary(),
                rawEvidence.updatedAt(),
                rawEvidence.validated(),
                rawEvidence.attributes()
        );
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private String normalizeOptional(String value) {
        if (!hasText(value)) {
            return null;
        }

        return value.trim();
    }
}
