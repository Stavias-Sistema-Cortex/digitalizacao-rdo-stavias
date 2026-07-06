package com.projeto.cortex.ontology;

import com.projeto.cortex.auth.CurrentUserService;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class OperationalTimelineController {

    private final OperationalTimelineService service;
    private final CurrentUserService currentUserService;

    public OperationalTimelineController(
            OperationalTimelineService service,
            CurrentUserService currentUserService
    ) {
        this.service = service;
        this.currentUserService = currentUserService;
    }

    @GetMapping("/api/ontology/timeline")
    public List<OperationalTimelineEventResponse> timeline(
            @RequestParam(required = false) String entityType,
            @RequestParam(required = false) String entityId,
            @RequestParam(required = false) String obraId,
            @RequestParam(required = false) String rdoId,
            @RequestParam(required = false) String colaboradorId,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
            LocalDateTime from,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
            LocalDateTime to,
            @RequestParam(required = false) Integer limit
    ) {
        boolean hasWorksiteScope = hasText(obraId);
        boolean hasRdoScope = hasText(rdoId);
        boolean hasEntityWorksiteScope =
                "OBRA".equalsIgnoreCase(entityType) && hasText(entityId);
        boolean hasEntityRdoScope =
                "RDO".equalsIgnoreCase(entityType) && hasText(entityId);
        String collaboratorScope = collaboratorScope(
                entityType,
                entityId,
                colaboradorId
        );

        if (hasWorksiteScope) {
            currentUserService.requireWorksiteAccess(obraId);
        }

        if (hasRdoScope) {
            currentUserService.requireRdoAccess(rdoId);
        }

        if (hasEntityWorksiteScope) {
            currentUserService.requireWorksiteAccess(entityId);
        }

        if (hasEntityRdoScope) {
            currentUserService.requireRdoAccess(entityId);
        }

        if (
                collaboratorScope != null
                && !hasWorksiteScope
                && !hasRdoScope
                && !hasEntityWorksiteScope
                && !hasEntityRdoScope
        ) {
            currentUserService.requireSelfOrAdmin(collaboratorScope);
        } else if (
                !hasWorksiteScope
                && !hasRdoScope
                && !hasEntityWorksiteScope
                && !hasEntityRdoScope
                && collaboratorScope == null
        ) {
            currentUserService.requireAdmin();
        }

        return service.timeline(
                entityType,
                entityId,
                obraId,
                rdoId,
                colaboradorId,
                from,
                to,
                limit
        );
    }

    private String collaboratorScope(
            String entityType,
            String entityId,
            String colaboradorId
    ) {
        if ("COLABORADOR".equalsIgnoreCase(entityType) && hasText(entityId)) {
            return entityId.trim();
        }

        if (hasText(colaboradorId)) {
            return colaboradorId.trim();
        }

        return null;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
