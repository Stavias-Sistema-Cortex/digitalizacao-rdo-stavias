package com.projeto.cortex.ontology.graph;

import com.projeto.cortex.auth.CurrentUserService;
import com.projeto.cortex.auth.PapelAcesso;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
        when(queryService.resolveWorksiteId(LOCAL_ENTITY_ID))
                .thenReturn(Optional.of(WORKSITE_A));
        when(queryService.resolveWorksiteId(FOREIGN_ENTITY_ID))
                .thenReturn(Optional.of(WORKSITE_B));
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

        verify(queryService, never()).listRelations(any(), any(), any(), anyInt(), anyInt(), anyInt());
    }

    @Test
    void pageSizeAboveOneHundredIsRejected() throws Exception {
        mockMvc.perform(get("/api/ontology/entities")
                        .param("obraId", WORKSITE_A)
                        .param("size", "101")
                        .requestAttr(CurrentUserService.REQUEST_ATTRIBUTE_USER_ID, "beta"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("ONTOLOGY_PAGE_SIZE_LIMIT"));

        verify(queryService, never()).listEntities(any(), any(), any(), anyInt(), anyInt());
    }

    @Test
    void oversizedTypeFilterIsRejectedBeforeQuery() throws Exception {
        mockMvc.perform(get("/api/ontology/events")
                        .param("obraId", WORKSITE_A)
                        .param("type", "X".repeat(121))
                        .requestAttr(CurrentUserService.REQUEST_ATTRIBUTE_USER_ID, "beta"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("ONTOLOGY_FILTER_LIMIT"));

        verify(queryService, never()).listEvents(any(), any(), any(), anyInt(), anyInt());
    }

    @Test
    void relationTargetMustBelongToAuthorizedWorksite() throws Exception {
        when(queryService.listRelations(WORKSITE_A, null, null, 1, 0, 50))
                .thenReturn(List.of(relation(LOCAL_ENTITY_ID, FOREIGN_ENTITY_ID)));

        mockMvc.perform(get("/api/ontology/relations")
                        .param("obraId", WORKSITE_A)
                        .requestAttr(CurrentUserService.REQUEST_ATTRIBUTE_USER_ID, "beta"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").doesNotExist());
    }

    @Test
    void eventRelatedEntityMustBelongToAuthorizedWorksite() throws Exception {
        when(queryService.listEvents(WORKSITE_A, null, null, 0, 50))
                .thenReturn(List.of(event(LOCAL_ENTITY_ID, FOREIGN_ENTITY_ID)));

        mockMvc.perform(get("/api/ontology/events")
                        .param("obraId", WORKSITE_A)
                        .requestAttr(CurrentUserService.REQUEST_ATTRIBUTE_USER_ID, "beta"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").doesNotExist());
    }

    @Test
    void stateEntityMustBelongToAuthorizedWorksite() throws Exception {
        when(queryService.listStates(WORKSITE_A, null, null, 0, 50))
                .thenReturn(List.of(state(FOREIGN_ENTITY_ID)));

        mockMvc.perform(get("/api/ontology/states")
                        .param("obraId", WORKSITE_A)
                        .requestAttr(CurrentUserService.REQUEST_ATTRIBUTE_USER_ID, "beta"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").doesNotExist());
    }

    @Test
    void evidenceEntityMustBelongToAuthorizedWorksite() throws Exception {
        when(queryService.listEvidences(WORKSITE_A, null, null, 0, 50))
                .thenReturn(List.of(evidence(FOREIGN_ENTITY_ID)));

        mockMvc.perform(get("/api/ontology/evidences")
                        .param("obraId", WORKSITE_A)
                        .requestAttr(CurrentUserService.REQUEST_ATTRIBUTE_USER_ID, "beta"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").doesNotExist());
    }

    @Test
    void entityListKeepsPublicFieldNamesAndUsesBoundedPagination() throws Exception {
        when(queryService.listEntities(WORKSITE_A, "RDO", "ponte", 2, 25))
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

        verify(queryService, never()).listEntities(any(), any(), any(), anyInt(), anyInt());
    }

    @Test
    void alfaKeepsAccessToUnscopedEntityList() throws Exception {
        papel("alfa", PapelAcesso.ALFA);
        when(queryService.listEntities(null, null, null, 0, 50)).thenReturn(List.of());

        mockMvc.perform(get("/api/ontology/entities")
                        .requestAttr(CurrentUserService.REQUEST_ATTRIBUTE_USER_ID, "alfa"))
                .andExpect(status().isOk());
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
        return new GraphRelation(
                "relation-1",
                sourceId,
                "BELONGS_TO_WORKSITE",
                targetId,
                BigDecimal.ONE,
                Map.of(),
                NOW
        );
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
