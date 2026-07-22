package com.projeto.cortex.ontology;

import com.projeto.cortex.auth.CurrentUserService;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
public class OperationalMemoryController {

    private static final int DEFAULT_LIMIT = 50;
    private static final int MAX_LIMIT = 100;
    private static final int MAX_SEARCH_LENGTH = 200;
    private static final int MAX_FILTER_LENGTH = 120;
    private static final int MAX_IDENTIFIER_LENGTH = 160;

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
            @RequestParam(required = false) String q,
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
            Instant from,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
            Instant to,
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) Long beforeCommitSequence,
            @RequestParam(required = false) String beforeEventId
    ) {
        String userId = currentUserService.requireUserId();
        validateRequest(
                q,
                entityType,
                entityId,
                obraId,
                rdoId,
                actorId,
                eventType,
                origin,
                result,
                from,
                to,
                beforeCommitSequence,
                beforeEventId
        );
        authorizeExplicitScope(entityType, entityId, obraId, rdoId);

        Optional<Set<String>> allowedWorksites = currentUserService.allowedObraIds(userId);
        OperationalMemoryScope scope = allowedWorksites
                .map(ids -> OperationalMemoryScope.beta(userId, ids))
                .orElseGet(() -> OperationalMemoryScope.alfa(userId));
        OperationalMemoryFilter filter = new OperationalMemoryFilter(
                q,
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
        OperationalMemoryCursor cursor = beforeCommitSequence == null
                ? null
                : new OperationalMemoryCursor(beforeCommitSequence, beforeEventId);
        return service.search(scope, filter, boundedLimit(limit), cursor);
    }

    @ExceptionHandler(MemoryRequestException.class)
    ResponseEntity<OperationalMemoryErrorResponse> requestError(
            MemoryRequestException exception
    ) {
        return ResponseEntity.status(exception.status())
                .body(new OperationalMemoryErrorResponse(exception.code()));
    }

    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<OperationalMemoryErrorResponse> authorizationError(
            ResponseStatusException exception
    ) {
        HttpStatus status = HttpStatus.resolve(exception.getStatusCode().value());
        HttpStatus safeStatus = status == null ? HttpStatus.BAD_REQUEST : status;
        String code = switch (safeStatus) {
            case FORBIDDEN -> "MEMORY_ACCESS_DENIED";
            case UNAUTHORIZED -> "MEMORY_UNAUTHORIZED";
            case NOT_FOUND -> "MEMORY_NOT_FOUND";
            default -> "MEMORY_REQUEST_REJECTED";
        };
        return ResponseEntity.status(safeStatus)
                .body(new OperationalMemoryErrorResponse(code));
    }

    @ExceptionHandler(OperationalMemoryCursorScopeException.class)
    ResponseEntity<OperationalMemoryErrorResponse> cursorScopeError() {
        return ResponseEntity.badRequest()
                .body(new OperationalMemoryErrorResponse("MEMORY_CURSOR_SCOPE_MISMATCH"));
    }

    private void validateRequest(
            String query,
            String entityType,
            String entityId,
            String obraId,
            String rdoId,
            String actorId,
            String eventType,
            String origin,
            String result,
            Instant from,
            Instant to,
            Long beforeCommitSequence,
            String beforeEventId
    ) {
        validateLength(query, MAX_SEARCH_LENGTH, "MEMORY_SEARCH_LIMIT");
        validateLength(entityType, MAX_FILTER_LENGTH, "MEMORY_FILTER_LIMIT");
        validateLength(eventType, MAX_FILTER_LENGTH, "MEMORY_FILTER_LIMIT");
        validateLength(origin, MAX_FILTER_LENGTH, "MEMORY_FILTER_LIMIT");
        validateLength(result, MAX_FILTER_LENGTH, "MEMORY_FILTER_LIMIT");
        validateLength(entityId, MAX_IDENTIFIER_LENGTH, "MEMORY_IDENTIFIER_LIMIT");
        validateLength(obraId, MAX_IDENTIFIER_LENGTH, "MEMORY_IDENTIFIER_LIMIT");
        validateLength(rdoId, MAX_IDENTIFIER_LENGTH, "MEMORY_IDENTIFIER_LIMIT");
        validateLength(actorId, MAX_IDENTIFIER_LENGTH, "MEMORY_IDENTIFIER_LIMIT");
        validateLength(beforeEventId, MAX_IDENTIFIER_LENGTH, "MEMORY_CURSOR_INVALID");
        if (hasText(entityType) != hasText(entityId)) {
            throw badRequest("MEMORY_ENTITY_FILTER_INVALID");
        }
        if ((beforeCommitSequence == null) != !hasText(beforeEventId)
                || (beforeCommitSequence != null && beforeCommitSequence < 0)) {
            throw badRequest("MEMORY_CURSOR_INVALID");
        }
        if (from != null && to != null && from.isAfter(to)) {
            throw badRequest("MEMORY_UTC_RANGE_INVALID");
        }
    }

    private void authorizeExplicitScope(
            String entityType,
            String entityId,
            String obraId,
            String rdoId
    ) {
        if (hasText(obraId)) {
            currentUserService.requireWorksiteAccess(obraId.trim());
        }
        if (hasText(rdoId)) {
            currentUserService.requireRdoAccess(rdoId.trim());
        }
        if ("OBRA".equalsIgnoreCase(entityType) && hasText(entityId)) {
            currentUserService.requireWorksiteAccess(entityId.trim());
        }
        if ("RDO".equalsIgnoreCase(entityType) && hasText(entityId)) {
            currentUserService.requireRdoAccess(entityId.trim());
        }
    }

    private int boundedLimit(Integer requestedLimit) {
        if (requestedLimit == null) {
            return DEFAULT_LIMIT;
        }
        return Math.max(1, Math.min(MAX_LIMIT, requestedLimit));
    }

    private void validateLength(String value, int maximum, String code) {
        if (value != null && value.length() > maximum) {
            throw badRequest(code);
        }
    }

    private MemoryRequestException badRequest(String code) {
        return new MemoryRequestException(HttpStatus.BAD_REQUEST, code);
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static final class MemoryRequestException extends RuntimeException {
        private final HttpStatus status;
        private final String code;

        private MemoryRequestException(HttpStatus status, String code) {
            this.status = status;
            this.code = code;
        }

        private HttpStatus status() {
            return status;
        }

        private String code() {
            return code;
        }
    }
}

record OperationalMemoryErrorResponse(String code) {
}
