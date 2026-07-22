package com.projeto.cortex.ontology.graph;

import com.projeto.cortex.auth.CurrentUserService;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
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
        authorizeScope(obraId, null);
        return queryService.listEntities(
                        obraId,
                        type,
                        q,
                        pagination.page(),
                        pagination.size()
                ).stream()
                .map(this::authorizedEntityResponse)
                .toList();
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
        authorizeEntity(id);
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
        authorizeScope(obraId, entityId);
        return queryService.listRelations(
                        obraId,
                        entityId,
                        type,
                        boundedDepth,
                        pagination.page(),
                        pagination.size()
                ).stream()
                .map(this::authorizedRelationResponse)
                .toList();
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
        authorizeScope(obraId, entityId);
        return queryService.listEvents(
                        obraId,
                        entityId,
                        type,
                        pagination.page(),
                        pagination.size()
                ).stream()
                .map(this::authorizedEventResponse)
                .toList();
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
        authorizeScope(obraId, entityId);
        return queryService.listStates(
                        obraId,
                        entityId,
                        type,
                        pagination.page(),
                        pagination.size()
                ).stream()
                .map(this::authorizedStateResponse)
                .toList();
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
        authorizeScope(obraId, entityId);
        return queryService.listEvidences(
                        obraId,
                        entityId,
                        type,
                        pagination.page(),
                        pagination.size()
                ).stream()
                .map(this::authorizedEvidenceResponse)
                .toList();
    }

    private void authorizeScope(String obraId, String entityId) {
        currentUserService.requireUserId();
        validateIdentifier(obraId);
        validateIdentifier(entityId);
        if (hasText(obraId)) {
            currentUserService.requireWorksiteAccess(obraId);
            if (hasText(entityId)) {
                authorizeEntity(entityId);
            }
            return;
        }
        if (hasText(entityId)) {
            authorizeEntity(entityId);
            return;
        }
        currentUserService.requireAdmin();
    }

    private void authorizeEntity(String entityId) {
        String worksiteId = queryService.resolveWorksiteId(entityId)
                .orElseThrow(this::notFound);
        currentUserService.requireWorksiteAccess(worksiteId);
    }

    private void authorizeRelation(GraphRelation relation) {
        authorizeEntity(relation.sourceEntityId());
        authorizeEntity(relation.targetEntityId());
    }

    private void authorizeEvent(GraphEvent event) {
        authorizeEntity(event.entityId());
        if (hasText(event.relatedEntityId())) {
            authorizeEntity(event.relatedEntityId());
        }
    }

    private OntologyGraphEntityResponse authorizedEntityResponse(GraphEntity entity) {
        authorizeEntity(entity.id());
        return OntologyGraphEntityResponse.from(entity);
    }

    private OntologyGraphRelationResponse authorizedRelationResponse(GraphRelation relation) {
        authorizeRelation(relation);
        return OntologyGraphRelationResponse.from(relation);
    }

    private OntologyGraphEventResponse authorizedEventResponse(GraphEvent event) {
        authorizeEvent(event);
        return OntologyGraphEventResponse.from(event);
    }

    private OntologyGraphStateResponse authorizedStateResponse(GraphState state) {
        authorizeEntity(state.entityId());
        return OntologyGraphStateResponse.from(state);
    }

    private OntologyGraphEvidenceResponse authorizedEvidenceResponse(GraphEvidence evidence) {
        authorizeEntity(evidence.entityId());
        return OntologyGraphEvidenceResponse.from(evidence);
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

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private record PageRequest(int page, int size) {
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
