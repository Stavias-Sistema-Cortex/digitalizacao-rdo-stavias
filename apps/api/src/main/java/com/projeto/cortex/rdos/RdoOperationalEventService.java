package com.projeto.cortex.rdos;

import com.projeto.cortex.memory.CortexOperationalMemoryService;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RdoOperationalEventService {

    private static final String FONTE_SYNC = "RDO_SYNC_CLIENTE";
    private static final Pattern UUID_PATTERN = Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"
    );

    private final CortexOperationalMemoryService memoryService;

    public RdoOperationalEventService(
            CortexOperationalMemoryService memoryService
    ) {
        this.memoryService = memoryService;
    }

    @Transactional
    public void registrarEventosCliente(
            String rdoId,
            String obraId,
            List<RdoCreateRequest.OperationalEventItem> events
    ) {
        if (events == null || events.isEmpty()) {
            return;
        }

        for (RdoCreateRequest.OperationalEventItem event : events) {
            if (
                    event == null
                    || isBlank(event.id())
                    || isBlank(event.type())
            ) {
                continue;
            }

            RdoCreateRequest.OperationalEntityRef principal =
                    event.principalEntity();
            PrincipalEntity principalEntity =
                    normalizePrincipalEntity(principal, rdoId);

            Map<String, Object> payload = new LinkedHashMap<>();
            if (event.payload() != null) {
                payload.putAll(event.payload());
            }
            if (principalEntity.originalId() != null
                    && !principalEntity.originalId()
                            .equals(principalEntity.id())) {
                Map<String, Object> originalPrincipal =
                        new LinkedHashMap<>();
                originalPrincipal.put("tipo", principalEntity.originalType());
                originalPrincipal.put("id", principalEntity.originalId());
                originalPrincipal.put("nome", principalEntity.originalName());
                payload.put("originalPrincipalEntity", originalPrincipal);
            }
            payload.put("clientEventId", event.id());
            payload.put("responsibleUserId", event.responsibleUserId());
            payload.put("responsibleUserName", event.responsibleUserName());

            memoryService.registrarEventoDetalhado(
                    event.id().trim(),
                    principalEntity.type(),
                    principalEntity.id(),
                    event.type().trim(),
                    FONTE_SYNC,
                    firstNonBlank(event.obraId(), obraId),
                    firstNonBlank(event.rdoId(), rdoId),
                    uuidColumnOrNull(event.colaboradorId()),
                    relatedEntities(event.relatedEntities()),
                    firstNonBlank(event.origin(), "OFFLINE"),
                    "SYNCED",
                    event.occurredAt(),
                    LocalDateTime.now(),
                    event.schemaVersion() == null
                            ? 1
                            : event.schemaVersion(),
                    payload
            );
        }
    }

    private PrincipalEntity normalizePrincipalEntity(
            RdoCreateRequest.OperationalEntityRef principal,
            String rdoId
    ) {
        if (principal == null || isBlank(principal.id())) {
            return new PrincipalEntity(
                    "RDO",
                    rdoId,
                    null,
                    null,
                    null
            );
        }

        String originalType = isBlank(principal.tipo())
                ? "RDO"
                : principal.tipo().trim();
        String originalId = principal.id().trim();
        String uuidId = uuidColumnOrNull(originalId);

        if (uuidId != null) {
            return new PrincipalEntity(
                    originalType,
                    uuidId,
                    originalType,
                    originalId,
                    principal.nome()
            );
        }

        return new PrincipalEntity(
                "RDO",
                rdoId,
                originalType,
                originalId,
                principal.nome()
        );
    }

    private List<Map<String, Object>> relatedEntities(
            List<RdoCreateRequest.OperationalEntityRef> entities
    ) {
        if (entities == null || entities.isEmpty()) {
            return List.of();
        }

        return entities.stream()
                .filter(entity -> entity != null && !isBlank(entity.id()))
                .map(entity -> {
                    Map<String, Object> related =
                            new LinkedHashMap<>();
                    related.put("tipo", entity.tipo());
                    related.put("id", entity.id());
                    related.put("nome", entity.nome());
                    return related;
                })
                .toList();
    }

    private String firstNonBlank(String value, String fallback) {
        return isBlank(value) ? fallback : value.trim();
    }

    private String blankToNull(String value) {
        return isBlank(value) ? null : value.trim();
    }

    private String uuidColumnOrNull(String value) {
        String candidate = blankToNull(value);
        if (candidate == null) {
            return null;
        }

        Matcher matcher = UUID_PATTERN.matcher(candidate);
        String lastMatch = null;
        while (matcher.find()) {
            lastMatch = matcher.group();
        }
        return lastMatch;
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private record PrincipalEntity(
            String type,
            String id,
            String originalType,
            String originalId,
            String originalName
    ) {
    }
}
