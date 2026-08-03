package com.projeto.cortex.ontology.graph;

import com.projeto.cortex.auth.CurrentUserService;
import com.projeto.cortex.auth.PapelAcesso;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(OntologyGraphController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(CurrentUserService.class)
class OntologyGraphAuthorizationMockMvcTest {

    private static final String WORKSITE_A = "obra-a";
    private static final String WORKSITE_B = "obra-b";
    private static final String LOCAL_ENTITY_ID = "entity-a";
    private static final String FOREIGN_ENTITY_ID = "entity-b";
    private static final String SHARED_ENTITY_ID = "entity-shared";
    private static final Instant NOW = Instant.parse("2026-07-21T12:00:00Z");

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private JdbcTemplate jdbcTemplate;

    @MockBean
    private OntologyGraphQueryService queryService;

    @BeforeEach
    void authorizeBetaForWorksiteA() {
        papel("beta", PapelAcesso.BETA);
        vinculo("beta", WORKSITE_A, true);
        vinculo("beta", WORKSITE_B, false);
        when(jdbcTemplate.queryForList(
                contains("FROM obra"),
                eq(String.class)
        )).thenReturn(List.of(WORKSITE_A));
        when(queryService.resolveWorksiteIds(any())).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            Set<String> entityIds = invocation.getArgument(0, Set.class);
            return entityIds.stream().collect(java.util.stream.Collectors.toMap(
                    id -> id,
                    this::worksitesFor
            ));
        });
    }

    @Test
    void foreignEntityIsForbiddenWithoutExistenceMessage() throws Exception {
        when(queryService.findEntity(FOREIGN_ENTITY_ID))
                .thenReturn(Optional.of(entity(FOREIGN_ENTITY_ID, WORKSITE_B)));

        mockMvc.perform(get("/api/ontology/entities/{id}", FOREIGN_ENTITY_ID)
                        .requestAttr(CurrentUserService.REQUEST_ATTRIBUTE_USER_ID, "beta"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ONTOLOGY_ACCESS_DENIED"))
                .andExpect(jsonPath("$.message").doesNotExist());

        verify(queryService, never()).findEntity(FOREIGN_ENTITY_ID);
    }

    @Test
    void traversalDepthAboveThreeIsRejectedWithStableCode() throws Exception {
        mockMvc.perform(get("/api/ontology/entities/{id}/relations", LOCAL_ENTITY_ID)
                        .param("depth", "6")
                        .requestAttr(CurrentUserService.REQUEST_ATTRIBUTE_USER_ID, "beta"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("ONTOLOGY_DEPTH_LIMIT"));

        verify(queryService, never()).listRelationsScoped(any(), any(), any(), anyInt(), anyInt(), anyInt());
    }

    @Test
    void lowerAndAdjacentPaginationAndDepthBoundariesAreRejected() throws Exception {
        mockMvc.perform(get("/api/ontology/entities/{id}/relations", LOCAL_ENTITY_ID)
                        .param("depth", "0")
                        .requestAttr(CurrentUserService.REQUEST_ATTRIBUTE_USER_ID, "beta"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("ONTOLOGY_DEPTH_LIMIT"));
        mockMvc.perform(get("/api/ontology/entities/{id}/relations", LOCAL_ENTITY_ID)
                        .param("depth", "4")
                        .requestAttr(CurrentUserService.REQUEST_ATTRIBUTE_USER_ID, "beta"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("ONTOLOGY_DEPTH_LIMIT"));
        mockMvc.perform(get("/api/ontology/entities")
                        .param("obraId", WORKSITE_A)
                        .param("size", "0")
                        .requestAttr(CurrentUserService.REQUEST_ATTRIBUTE_USER_ID, "beta"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("ONTOLOGY_PAGE_SIZE_INVALID"));
        mockMvc.perform(get("/api/ontology/entities")
                        .param("obraId", WORKSITE_A)
                        .param("page", "-1")
                        .requestAttr(CurrentUserService.REQUEST_ATTRIBUTE_USER_ID, "beta"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("ONTOLOGY_PAGE_INVALID"));

        verify(queryService, never()).listRelationsScoped(any(), any(), any(), anyInt(), anyInt(), anyInt());
        verify(queryService, never()).listEntitiesScoped(any(), any(), any(), anyInt(), anyInt());
    }

    @Test
    void pageSizeAboveOneHundredIsRejected() throws Exception {
        mockMvc.perform(get("/api/ontology/entities")
                        .param("obraId", WORKSITE_A)
                        .param("size", "101")
                        .requestAttr(CurrentUserService.REQUEST_ATTRIBUTE_USER_ID, "beta"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("ONTOLOGY_PAGE_SIZE_LIMIT"));

        verify(queryService, never()).listEntitiesScoped(any(), any(), any(), anyInt(), anyInt());
    }

    @Test
    void oversizedTypeFilterIsRejectedBeforeQuery() throws Exception {
        mockMvc.perform(get("/api/ontology/events")
                        .param("obraId", WORKSITE_A)
                        .param("type", "X".repeat(121))
                        .requestAttr(CurrentUserService.REQUEST_ATTRIBUTE_USER_ID, "beta"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("ONTOLOGY_FILTER_LIMIT"));

        verify(queryService, never()).listEventsScoped(any(), any(), any(), anyInt(), anyInt());
    }

    @Test
    void oversizedSearchAndIdentifierAreRejectedBeforeResolutionOrQuery() throws Exception {
        mockMvc.perform(get("/api/ontology/entities")
                        .param("obraId", WORKSITE_A)
                        .param("q", "Q".repeat(201))
                        .requestAttr(CurrentUserService.REQUEST_ATTRIBUTE_USER_ID, "beta"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("ONTOLOGY_SEARCH_LIMIT"));
        mockMvc.perform(get("/api/ontology/entities/{id}", "I".repeat(161))
                        .requestAttr(CurrentUserService.REQUEST_ATTRIBUTE_USER_ID, "beta"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("ONTOLOGY_IDENTIFIER_LIMIT"));

        verify(queryService, never()).listEntitiesScoped(any(), any(), any(), anyInt(), anyInt());
        verify(queryService, never()).resolveWorksiteIds(any());
    }

    @Test
    void relationTargetMustBelongToAuthorizedWorksite() throws Exception {
        when(queryService.listRelationsScoped(Set.of(WORKSITE_A), null, null, 1, 0, 50))
                .thenReturn(List.of(relation(LOCAL_ENTITY_ID, FOREIGN_ENTITY_ID)));

        mockMvc.perform(get("/api/ontology/relations")
                        .param("obraId", WORKSITE_A)
                        .requestAttr(CurrentUserService.REQUEST_ATTRIBUTE_USER_ID, "beta"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").doesNotExist());
    }

    @Test
    void eventRelatedEntityMustBelongToAuthorizedWorksite() throws Exception {
        when(queryService.listEventsScoped(Set.of(WORKSITE_A), null, null, 0, 50))
                .thenReturn(List.of(event(LOCAL_ENTITY_ID, FOREIGN_ENTITY_ID)));

        mockMvc.perform(get("/api/ontology/events")
                        .param("obraId", WORKSITE_A)
                        .requestAttr(CurrentUserService.REQUEST_ATTRIBUTE_USER_ID, "beta"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").doesNotExist());
    }

    @Test
    void stateEntityMustBelongToAuthorizedWorksite() throws Exception {
        when(queryService.listStatesScoped(Set.of(WORKSITE_A), null, null, 0, 50))
                .thenReturn(List.of(state(FOREIGN_ENTITY_ID)));

        mockMvc.perform(get("/api/ontology/states")
                        .param("obraId", WORKSITE_A)
                        .requestAttr(CurrentUserService.REQUEST_ATTRIBUTE_USER_ID, "beta"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").doesNotExist());
    }

    @Test
    void evidenceEntityMustBelongToAuthorizedWorksite() throws Exception {
        when(queryService.listEvidencesScoped(Set.of(WORKSITE_A), null, null, 0, 50))
                .thenReturn(List.of(evidence(FOREIGN_ENTITY_ID)));

        mockMvc.perform(get("/api/ontology/evidences")
                        .param("obraId", WORKSITE_A)
                        .requestAttr(CurrentUserService.REQUEST_ATTRIBUTE_USER_ID, "beta"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").doesNotExist());
    }

    @Test
    void entityListKeepsPublicFieldNamesAndUsesBoundedPagination() throws Exception {
        when(queryService.listEntitiesScoped(Set.of(WORKSITE_A), "RDO", "ponte", 2, 25))
                .thenReturn(List.of(entity(LOCAL_ENTITY_ID, WORKSITE_A)));

        mockMvc.perform(get("/api/ontology/entities")
                        .param("obraId", WORKSITE_A)
                        .param("type", "RDO")
                        .param("q", "ponte")
                        .param("page", "2")
                        .param("size", "25")
                        .requestAttr(CurrentUserService.REQUEST_ATTRIBUTE_USER_ID, "beta"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(LOCAL_ENTITY_ID))
                .andExpect(jsonPath("$[0].entityType").value("RDO"))
                .andExpect(jsonPath("$[0].type").doesNotExist());
    }

    @Test
    void betaCannotUseUnscopedEntityList() throws Exception {
        mockMvc.perform(get("/api/ontology/entities")
                        .requestAttr(CurrentUserService.REQUEST_ATTRIBUTE_USER_ID, "beta"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").doesNotExist());

        verify(queryService, never()).listEntitiesScoped(any(), any(), any(), anyInt(), anyInt());
    }

    @Test
    void alfaKeepsAccessToUnscopedEntityList() throws Exception {
        papel("alfa", PapelAcesso.ALFA);
        when(queryService.listEntitiesScoped(null, null, null, 0, 50)).thenReturn(List.of());

        mockMvc.perform(get("/api/ontology/entities")
                        .requestAttr(CurrentUserService.REQUEST_ATTRIBUTE_USER_ID, "alfa"))
                .andExpect(status().isOk());
    }

    @Test
    void sharedEntityUsesAllowedWorksiteIntersectionAndBatchAuthorizationForFullPage() throws Exception {
        List<GraphRelation> page = IntStream.range(0, 100)
                .mapToObj(index -> relation(
                        "relation-" + index,
                        SHARED_ENTITY_ID,
                        LOCAL_ENTITY_ID + "-" + index
                ))
                .toList();
        when(queryService.listRelationsScoped(
                Set.of(WORKSITE_A), SHARED_ENTITY_ID, null, 1, 0, 100
        )).thenReturn(page);

        mockMvc.perform(get("/api/ontology/entities/{id}/relations", SHARED_ENTITY_ID)
                        .param("depth", "1")
                        .param("size", "100")
                        .requestAttr(CurrentUserService.REQUEST_ATTRIBUTE_USER_ID, "beta"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(100));

        verify(queryService).listRelationsScoped(
                Set.of(WORKSITE_A), SHARED_ENTITY_ID, null, 1, 0, 100
        );
        verify(queryService, times(2)).resolveWorksiteIds(any());
    }

    @Test
    void exactDepthPageFilterSearchAndIdentifierBoundariesAreAccepted() throws Exception {
        String exactFilter = "T".repeat(120);
        when(queryService.listRelationsScoped(
                Set.of(WORKSITE_A), LOCAL_ENTITY_ID, exactFilter, 3, 0, 100
        )).thenReturn(List.of());
        when(queryService.listEntitiesScoped(
                Set.of(WORKSITE_A), null, "Q".repeat(200), 0, 100
        )).thenReturn(List.of());

        mockMvc.perform(get("/api/ontology/entities/{id}/relations", LOCAL_ENTITY_ID)
                        .param("depth", "3")
                        .param("size", "100")
                        .param("type", exactFilter)
                        .requestAttr(CurrentUserService.REQUEST_ATTRIBUTE_USER_ID, "beta"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/ontology/entities")
                        .param("obraId", WORKSITE_A)
                        .param("q", "Q".repeat(200))
                        .param("size", "100")
                        .requestAttr(CurrentUserService.REQUEST_ATTRIBUTE_USER_ID, "beta"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/ontology/entities/{id}", "I".repeat(160))
                        .requestAttr(CurrentUserService.REQUEST_ATTRIBUTE_USER_ID, "beta"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ONTOLOGY_NOT_FOUND"));
    }

    @Test
    void relationEventStateAndEvidenceKeepPublicObjectNamesAfterBatchAuthorization() throws Exception {
        when(queryService.listRelationsScoped(Set.of(WORKSITE_A), null, null, 1, 0, 50))
                .thenReturn(List.of(relation(LOCAL_ENTITY_ID, LOCAL_ENTITY_ID)));
        when(queryService.listEventsScoped(Set.of(WORKSITE_A), null, null, 0, 50))
                .thenReturn(List.of(event(LOCAL_ENTITY_ID, LOCAL_ENTITY_ID)));
        when(queryService.listStatesScoped(Set.of(WORKSITE_A), null, null, 0, 50))
                .thenReturn(List.of(state(LOCAL_ENTITY_ID)));
        when(queryService.listEvidencesScoped(Set.of(WORKSITE_A), null, null, 0, 50))
                .thenReturn(List.of(evidence(LOCAL_ENTITY_ID)));

        mockMvc.perform(get("/api/ontology/relations")
                        .param("obraId", WORKSITE_A)
                        .requestAttr(CurrentUserService.REQUEST_ATTRIBUTE_USER_ID, "beta"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].relationType").value("BELONGS_TO_WORKSITE"))
                .andExpect(jsonPath("$[0].type").doesNotExist());
        mockMvc.perform(get("/api/ontology/events")
                        .param("obraId", WORKSITE_A)
                        .requestAttr(CurrentUserService.REQUEST_ATTRIBUTE_USER_ID, "beta"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].eventType").value("RDO_UPDATED"))
                .andExpect(jsonPath("$[0].type").doesNotExist());
        mockMvc.perform(get("/api/ontology/states")
                        .param("obraId", WORKSITE_A)
                        .requestAttr(CurrentUserService.REQUEST_ATTRIBUTE_USER_ID, "beta"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].stateType").value("STATUS"))
                .andExpect(jsonPath("$[0].type").doesNotExist());
        mockMvc.perform(get("/api/ontology/evidences")
                        .param("obraId", WORKSITE_A)
                        .requestAttr(CurrentUserService.REQUEST_ATTRIBUTE_USER_ID, "beta"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].evidenceType").value("RDO_OBSERVATION"))
                .andExpect(jsonPath("$[0].type").doesNotExist());
    }

    @SuppressWarnings("unchecked")
    private void papel(String userId, PapelAcesso papel) {
        when(jdbcTemplate.query(
                contains("FROM colaborador"),
                any(ResultSetExtractor.class),
                eq(userId)
        )).thenReturn(papel);
    }

    private void vinculo(String userId, String obraId, boolean ativo) {
        when(jdbcTemplate.queryForObject(
                contains("vinculo_colaborador_obra"),
                eq(Integer.class),
                eq(userId),
                eq(obraId)
        )).thenReturn(ativo ? 1 : 0);
    }

    private GraphEntity entity(String id, String obraId) {
        return new GraphEntity(
                id,
                "RDO",
                "rdo",
                id,
                "RDO " + id,
                "Registro operacional",
                "ATIVO",
                Map.of("obraId", obraId),
                NOW,
                NOW
        );
    }

    private GraphRelation relation(String sourceId, String targetId) {
        return relation("relation-1", sourceId, targetId);
    }

    private GraphRelation relation(String id, String sourceId, String targetId) {
        return new GraphRelation(
                id,
                sourceId,
                "BELONGS_TO_WORKSITE",
                targetId,
                BigDecimal.ONE,
                Map.of(),
                NOW
        );
    }

    private Set<String> worksitesFor(String entityId) {
        if (SHARED_ENTITY_ID.equals(entityId)) {
            return Set.of(WORKSITE_A, WORKSITE_B);
        }
        if (entityId != null && entityId.startsWith(LOCAL_ENTITY_ID)) {
            return Set.of(WORKSITE_A);
        }
        if (FOREIGN_ENTITY_ID.equals(entityId)) {
            return Set.of(WORKSITE_B);
        }
        return Set.of();
    }

    private GraphEvent event(String entityId, String relatedEntityId) {
        return new GraphEvent(
                "event-1",
                "RDO_UPDATED",
                entityId,
                relatedEntityId,
                "rdo",
                "rdo-1",
                "RDO atualizado",
                Map.of(),
                NOW,
                NOW
        );
    }

    private GraphState state(String entityId) {
        return new GraphState(
                "state-1",
                entityId,
                "STATUS",
                "ATIVO",
                null,
                null,
                NOW,
                null,
                null,
                Map.of()
        );
    }

    private GraphEvidence evidence(String entityId) {
        return new GraphEvidence(
                "evidence-1",
                entityId,
                "RDO_OBSERVATION",
                "rdo",
                "rdo-1",
                "Observação persistida",
                null,
                Map.of(),
                NOW
        );
    }
}
