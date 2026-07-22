package com.projeto.cortex.ontology.graph;

import com.projeto.cortex.auth.CurrentUserService;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** Authorized API for the independent operational ontology graph. */
@RestController
public class OntologyGraphController {

    private static final int DEFAULT_PAGE_SIZE = 50;
    private static final int MAX_PAGE_SIZE = 100;
    private static final int MAX_DEPTH = 3;
    private static final int MAX_SEARCH_LENGTH = 200;
    private static final int MAX_FILTER_LENGTH = 120;
    private static final int MAX_IDENTIFIER_LENGTH = 160;

    private final OntologyGraphQueryService queryService;
    private final CurrentUserService currentUserService;

    public OntologyGraphController(
            OntologyGraphQueryService queryService,
            CurrentUserService currentUserService
    ) {
        this.queryService = queryService;
        this.currentUserService = currentUserService;
    }

    @GetMapping("/api/ontology/entities")
    public List<OntologyGraphEntityResponse> entities(
            @RequestParam(required = false) String obraId,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) Integer limit
    ) {
        PageRequest pagination = page(page, size, limit);
        validateSearch(q);
        validateFilter(type);
        EffectiveScope scope = authorizeScope(obraId, null);
        List<GraphEntity> entities = queryService.listEntitiesScoped(
                        scope.worksiteIds(),
                        type,
                        q,
                        pagination.page(),
                        pagination.size()
                );
        authorizeObjects(
                entities.stream().map(GraphEntity::id).collect(Collectors.toSet()),
                scope
        );
        return entities.stream().map(OntologyGraphEntityResponse::from).toList();
    }

    /** Compatibility alias retained for existing graph search clients. */
    @GetMapping("/api/ontology/search")
    public List<OntologyGraphEntityResponse> search(
            @RequestParam(required = false) String obraId,
            @RequestParam String q,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) Integer limit
    ) {
        return entities(obraId, type, q, page, size, limit);
    }

    @GetMapping("/api/ontology/entities/{id}")
    public OntologyGraphEntityResponse entity(@PathVariable String id) {
        currentUserService.requireUserId();
        validateIdentifier(id);
        authorizeScope(null, id);
        return queryService.findEntity(id)
                .map(OntologyGraphEntityResponse::from)
                .orElseThrow(this::notFound);
    }

    @GetMapping("/api/ontology/relations")
    public List<OntologyGraphRelationResponse> relations(
            @RequestParam(required = false) String obraId,
            @RequestParam(required = false) String entityId,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) Integer depth,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) Integer limit
    ) {
        return queryRelations(obraId, entityId, type, depth, page, size, limit);
    }

    @GetMapping("/api/ontology/entities/{id}/relations")
    public List<OntologyGraphRelationResponse> entityRelations(
            @PathVariable String id,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) Integer depth,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) Integer limit
    ) {
        return queryRelations(null, id, type, depth, page, size, limit);
    }

    @GetMapping("/api/ontology/events")
    public List<OntologyGraphEventResponse> events(
            @RequestParam(required = false) String obraId,
            @RequestParam(required = false) String entityId,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) Integer limit
    ) {
        return queryEvents(obraId, entityId, type, page, size, limit);
    }

    @GetMapping("/api/ontology/entities/{id}/events")
    public List<OntologyGraphEventResponse> entityEvents(
            @PathVariable String id,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) Integer limit
    ) {
        return queryEvents(null, id, type, page, size, limit);
    }

    @GetMapping("/api/ontology/states")
    public List<OntologyGraphStateResponse> states(
            @RequestParam(required = false) String obraId,
            @RequestParam(required = false) String entityId,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) Integer limit
    ) {
        return queryStates(obraId, entityId, type, page, size, limit);
    }

    @GetMapping("/api/ontology/entities/{id}/states")
    public List<OntologyGraphStateResponse> entityStates(
            @PathVariable String id,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) Integer limit
    ) {
        return queryStates(null, id, type, page, size, limit);
    }

    @GetMapping("/api/ontology/evidences")
    public List<OntologyGraphEvidenceResponse> evidences(
            @RequestParam(required = false) String obraId,
            @RequestParam(required = false) String entityId,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) Integer limit
    ) {
        return queryEvidences(obraId, entityId, type, page, size, limit);
    }

    @GetMapping("/api/ontology/entities/{id}/evidences")
    public List<OntologyGraphEvidenceResponse> entityEvidences(
            @PathVariable String id,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) Integer limit
    ) {
        return queryEvidences(null, id, type, page, size, limit);
    }

    @ExceptionHandler(OntologyGraphRequestException.class)
    ResponseEntity<OntologyGraphErrorResponse> graphRequestError(
            OntologyGraphRequestException exception
    ) {
        return ResponseEntity.status(exception.status())
                .body(new OntologyGraphErrorResponse(exception.code()));
    }

    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<OntologyGraphErrorResponse> authorizationError(
            ResponseStatusException exception
    ) {
        HttpStatus status = HttpStatus.resolve(exception.getStatusCode().value());
        HttpStatus safeStatus = status == null ? HttpStatus.BAD_REQUEST : status;
        String code = switch (safeStatus) {
            case FORBIDDEN -> "ONTOLOGY_ACCESS_DENIED";
            case UNAUTHORIZED -> "ONTOLOGY_UNAUTHORIZED";
            case NOT_FOUND -> "ONTOLOGY_NOT_FOUND";
            default -> "ONTOLOGY_REQUEST_REJECTED";
        };
        return ResponseEntity.status(safeStatus).body(new OntologyGraphErrorResponse(code));
    }

    private List<OntologyGraphRelationResponse> queryRelations(
            String obraId,
            String entityId,
            String type,
            Integer depth,
            Integer page,
            Integer size,
            Integer limit
    ) {
        currentUserService.requireUserId();
        int boundedDepth = depth(depth);
        PageRequest pagination = page(page, size, limit);
        validateFilter(type);
        EffectiveScope scope = authorizeScope(obraId, entityId);
        List<GraphRelation> relations = queryService.listRelationsScoped(
                        scope.worksiteIds(),
                        entityId,
                        type,
                        boundedDepth,
                        pagination.page(),
                        pagination.size()
                );
        Set<String> endpointIds = new LinkedHashSet<>();
        relations.forEach(relation -> {
            endpointIds.add(relation.sourceEntityId());
            endpointIds.add(relation.targetEntityId());
        });
        authorizeObjects(endpointIds, scope);
        return relations.stream().map(OntologyGraphRelationResponse::from).toList();
    }

    private List<OntologyGraphEventResponse> queryEvents(
            String obraId,
            String entityId,
            String type,
            Integer page,
            Integer size,
            Integer limit
    ) {
        currentUserService.requireUserId();
        PageRequest pagination = page(page, size, limit);
        validateFilter(type);
        EffectiveScope scope = authorizeScope(obraId, entityId);
        List<GraphEvent> events = queryService.listEventsScoped(
                        scope.worksiteIds(),
                        entityId,
                        type,
                        pagination.page(),
                        pagination.size()
                );
        Set<String> endpointIds = new LinkedHashSet<>();
        events.forEach(event -> {
            endpointIds.add(event.entityId());
            if (hasText(event.relatedEntityId())) {
                endpointIds.add(event.relatedEntityId());
            }
        });
        authorizeObjects(endpointIds, scope);
        return events.stream().map(OntologyGraphEventResponse::from).toList();
    }

    private List<OntologyGraphStateResponse> queryStates(
            String obraId,
            String entityId,
            String type,
            Integer page,
            Integer size,
            Integer limit
    ) {
        currentUserService.requireUserId();
        PageRequest pagination = page(page, size, limit);
        validateFilter(type);
        EffectiveScope scope = authorizeScope(obraId, entityId);
        List<GraphState> states = queryService.listStatesScoped(
                        scope.worksiteIds(),
                        entityId,
                        type,
                        pagination.page(),
                        pagination.size()
                );
        authorizeObjects(
                states.stream().map(GraphState::entityId).collect(Collectors.toSet()),
                scope
        );
        return states.stream().map(OntologyGraphStateResponse::from).toList();
    }

    private List<OntologyGraphEvidenceResponse> queryEvidences(
            String obraId,
            String entityId,
            String type,
            Integer page,
            Integer size,
            Integer limit
    ) {
        currentUserService.requireUserId();
        PageRequest pagination = page(page, size, limit);
        validateFilter(type);
        EffectiveScope scope = authorizeScope(obraId, entityId);
        List<GraphEvidence> evidences = queryService.listEvidencesScoped(
                        scope.worksiteIds(),
                        entityId,
                        type,
                        pagination.page(),
                        pagination.size()
                );
        authorizeObjects(
                evidences.stream().map(GraphEvidence::entityId).collect(Collectors.toSet()),
                scope
        );
        return evidences.stream().map(OntologyGraphEvidenceResponse::from).toList();
    }

    private EffectiveScope authorizeScope(String obraId, String entityId) {
        String userId = currentUserService.requireUserId();
        validateIdentifier(obraId);
        validateIdentifier(entityId);
        if (hasText(obraId)) {
            String normalizedObraId = obraId.trim();
            currentUserService.requireWorksiteAccess(normalizedObraId);
            if (hasText(entityId)) {
                Set<String> entityWorksites = resolvedWorksites(entityId);
                if (!entityWorksites.contains(normalizedObraId)) {
                    throw accessDenied();
                }
            }
            return new EffectiveScope(Set.of(normalizedObraId));
        }
        if (hasText(entityId)) {
            Set<String> entityWorksites = resolvedWorksites(entityId);
            Optional<Set<String>> allowedWorksites = currentUserService.allowedObraIds(userId);
            if (allowedWorksites.isEmpty()) {
                return new EffectiveScope(entityWorksites);
            }
            Set<String> intersection = new LinkedHashSet<>(entityWorksites);
            intersection.retainAll(allowedWorksites.orElseThrow());
            if (intersection.isEmpty()) {
                throw accessDenied();
            }
            return new EffectiveScope(Set.copyOf(intersection));
        }
        currentUserService.requireAdmin();
        return new EffectiveScope(null);
    }

    private Set<String> resolvedWorksites(String entityId) {
        String normalizedEntityId = entityId.trim();
        Set<String> worksiteIds = queryService.resolveWorksiteIds(Set.of(normalizedEntityId))
                .getOrDefault(normalizedEntityId, Set.of());
        if (worksiteIds.isEmpty()) {
            throw notFound();
        }
        return worksiteIds;
    }

    private void authorizeObjects(Set<String> entityIds, EffectiveScope scope) {
        if (entityIds.isEmpty()) {
            return;
        }
        Map<String, Set<String>> resolved = queryService.resolveWorksiteIds(entityIds);
        for (String entityId : entityIds) {
            Set<String> worksiteIds = resolved.getOrDefault(entityId, Set.of());
            if (worksiteIds.isEmpty()) {
                throw notFound();
            }
            if (scope.worksiteIds() != null
                    && worksiteIds.stream().noneMatch(scope.worksiteIds()::contains)) {
                throw accessDenied();
            }
        }
    }

    private PageRequest page(Integer page, Integer size, Integer limit) {
        int requestedPage = page == null ? 0 : page;
        if (requestedPage < 0) {
            throw new OntologyGraphRequestException(
                    HttpStatus.BAD_REQUEST,
                    "ONTOLOGY_PAGE_INVALID"
            );
        }
        Integer requestedSize = size != null ? size : limit;
        int boundedSize = requestedSize == null ? DEFAULT_PAGE_SIZE : requestedSize;
        if (boundedSize <= 0) {
            throw new OntologyGraphRequestException(
                    HttpStatus.BAD_REQUEST,
                    "ONTOLOGY_PAGE_SIZE_INVALID"
            );
        }
        if (boundedSize > MAX_PAGE_SIZE) {
            throw new OntologyGraphRequestException(
                    HttpStatus.BAD_REQUEST,
                    "ONTOLOGY_PAGE_SIZE_LIMIT"
            );
        }
        return new PageRequest(requestedPage, boundedSize);
    }

    private int depth(Integer requestedDepth) {
        int boundedDepth = requestedDepth == null ? 1 : requestedDepth;
        if (boundedDepth < 1 || boundedDepth > MAX_DEPTH) {
            throw new OntologyGraphRequestException(
                    HttpStatus.BAD_REQUEST,
                    "ONTOLOGY_DEPTH_LIMIT"
            );
        }
        return boundedDepth;
    }

    private void validateSearch(String query) {
        if (query != null && query.length() > MAX_SEARCH_LENGTH) {
            throw new OntologyGraphRequestException(
                    HttpStatus.BAD_REQUEST,
                    "ONTOLOGY_SEARCH_LIMIT"
            );
        }
    }

    private void validateFilter(String filter) {
        validateLength(filter, MAX_FILTER_LENGTH, "ONTOLOGY_FILTER_LIMIT");
    }

    private void validateIdentifier(String identifier) {
        validateLength(identifier, MAX_IDENTIFIER_LENGTH, "ONTOLOGY_IDENTIFIER_LIMIT");
    }

    private void validateLength(String value, int maximum, String code) {
        if (value != null && value.length() > maximum) {
            throw new OntologyGraphRequestException(HttpStatus.BAD_REQUEST, code);
        }
    }

    private OntologyGraphRequestException notFound() {
        return new OntologyGraphRequestException(HttpStatus.NOT_FOUND, "ONTOLOGY_NOT_FOUND");
    }

    private OntologyGraphRequestException accessDenied() {
        return new OntologyGraphRequestException(HttpStatus.FORBIDDEN, "ONTOLOGY_ACCESS_DENIED");
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private record PageRequest(int page, int size) {
    }

    private record EffectiveScope(Set<String> worksiteIds) {
    }

    private static final class OntologyGraphRequestException extends RuntimeException {
        private final HttpStatus status;
        private final String code;

        private OntologyGraphRequestException(HttpStatus status, String code) {
            if (status == null || code == null || code.isBlank()) {
                throw new IllegalArgumentException("Ontology graph error status and code are required.");
            }
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

record OntologyGraphErrorResponse(String code) {
}

record OntologyGraphEntityResponse(
        String id,
        String entityType,
        String externalRefType,
        String externalRefId,
        String canonicalName,
        String description,
        String status,
        Map<String, Object> metadata,
        Instant createdAt,
        Instant updatedAt
) {
    static OntologyGraphEntityResponse from(GraphEntity entity) {
        return new OntologyGraphEntityResponse(
                entity.id(), entity.type(), entity.externalRefType(), entity.externalRefId(),
                entity.canonicalName(), entity.description(), entity.status(), entity.metadata(),
                entity.createdAt(), entity.updatedAt()
        );
    }
}

record OntologyGraphRelationResponse(
        String id,
        String sourceEntityId,
        String relationType,
        String targetEntityId,
        BigDecimal confidence,
        Map<String, Object> metadata,
        Instant createdAt
) {
    static OntologyGraphRelationResponse from(GraphRelation relation) {
        return new OntologyGraphRelationResponse(
                relation.id(), relation.sourceEntityId(), relation.type(), relation.targetEntityId(),
                relation.confidence(), relation.metadata(), relation.createdAt()
        );
    }
}

record OntologyGraphEventResponse(
        String id,
        String eventType,
        String entityId,
        String relatedEntityId,
        String sourceType,
        String sourceId,
        String description,
        Map<String, Object> payload,
        Instant occurredAt,
        Instant createdAt
) {
    static OntologyGraphEventResponse from(GraphEvent event) {
        return new OntologyGraphEventResponse(
                event.id(), event.type(), event.entityId(), event.relatedEntityId(), event.sourceType(),
                event.sourceId(), event.description(), event.payload(), event.occurredAt(), event.createdAt()
        );
    }
}

record OntologyGraphStateResponse(
        String id,
        String entityId,
        String stateType,
        String stateValue,
        BigDecimal numericValue,
        String unit,
        Instant validFrom,
        Instant validTo,
        String sourceEventId,
        Map<String, Object> metadata
) {
    static OntologyGraphStateResponse from(GraphState state) {
        return new OntologyGraphStateResponse(
                state.id(), state.entityId(), state.type(), state.value(), state.numericValue(), state.unit(),
                state.validFrom(), state.validTo(), state.sourceEventId(), state.metadata()
        );
    }
}

record OntologyGraphEvidenceResponse(
        String id,
        String entityId,
        String evidenceType,
        String sourceType,
        String sourceId,
        String description,
        String fileRef,
        Map<String, Object> metadata,
        Instant createdAt
) {
    static OntologyGraphEvidenceResponse from(GraphEvidence evidence) {
        return new OntologyGraphEvidenceResponse(
                evidence.id(), evidence.entityId(), evidence.type(), evidence.sourceType(), evidence.sourceId(),
                evidence.description(), evidence.fileRef(), evidence.metadata(), evidence.createdAt()
        );
    }
}
