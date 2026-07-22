package com.projeto.cortex.ontology;

import com.projeto.cortex.auth.CurrentUserService;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.Set;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class OperationalMemoryController {

    private final OperationalMemoryQueryService service;
    private final CurrentUserService currentUserService;

    public OperationalMemoryController(
            OperationalMemoryQueryService service,
            CurrentUserService currentUserService
    ) {
        this.service = service;
        this.currentUserService = currentUserService;
    }

    @GetMapping("/api/ontology/memory")
    public OperationalMemoryPageResponse memory(
            @RequestParam(required = false) String entityType,
            @RequestParam(required = false) String entityId,
            @RequestParam(required = false) String obraId,
            @RequestParam(required = false) String rdoId,
            @RequestParam(required = false) String actorId,
            @RequestParam(required = false) String eventType,
            @RequestParam(required = false) String origin,
            @RequestParam(required = false) String result,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
            LocalDateTime from,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
            LocalDateTime to,
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) Long beforeCommitSeq
    ) {
        String userId = currentUserService.requireUserId();
        authorizeExplicitScope(entityType, entityId, obraId, rdoId);

        Optional<Set<String>> allowedWorksites =
                currentUserService.allowedObraIds(userId);
        OperationalMemoryScope scope = allowedWorksites
                .map(ids -> OperationalMemoryScope.beta(userId, ids))
                .orElseGet(() -> OperationalMemoryScope.alfa(userId));

        OperationalMemoryFilter filter = new OperationalMemoryFilter(
                entityType,
                entityId,
                obraId,
                rdoId,
                actorId,
                eventType,
                origin,
                result,
                from,
                to
        );

        return service.memory(
                scope,
                filter,
                clampLimit(limit),
                beforeCommitSeq
        );
    }

    private void authorizeExplicitScope(
            String entityType,
            String entityId,
            String obraId,
            String rdoId
    ) {
        if (hasText(obraId)) {
            currentUserService.requireWorksiteAccess(obraId);
        }
        if (hasText(rdoId)) {
            currentUserService.requireRdoAccess(rdoId);
        }
        if ("OBRA".equalsIgnoreCase(entityType) && hasText(entityId)) {
            currentUserService.requireWorksiteAccess(entityId);
        }
        if ("RDO".equalsIgnoreCase(entityType) && hasText(entityId)) {
            currentUserService.requireRdoAccess(entityId);
        }
    }

    private int clampLimit(Integer requestedLimit) {
        if (requestedLimit == null) {
            return 50;
        }
        return Math.max(1, Math.min(100, requestedLimit));
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
