package com.projeto.cortex.ontology;

import com.projeto.cortex.auth.CurrentUserService;
import com.projeto.cortex.auth.PapelAcesso;
import java.time.Instant;
import java.util.List;
import java.util.Set;
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
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(OperationalMemoryController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(CurrentUserService.class)
class OperationalMemoryControllerAuthorizationMockMvcTest {

    private static final String WORKSITE_A = "obra-a";
    private static final String WORKSITE_B = "obra-b";

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private JdbcTemplate jdbcTemplate;

    @MockBean
    private OperationalMemoryQueryService service;

    @BeforeEach
    void emptyPage() {
        when(service.search(any(), any(), any(), any()))
                .thenReturn(new OperationalMemoryPageResponse(
                        List.of(),
                        null,
                        false,
                        0L,
                        "scope-hash",
                        new OperationalMemoryCoverage(
                                "FULL_HISTORY", true, 0L, null, 0L
                        ),
                        Instant.parse("2026-07-22T12:00:00Z")
                ));
    }

    @Test
    void betaReceivesOnlyItsRealAuthorizedWorksiteScope() throws Exception {
        papel("beta", PapelAcesso.BETA);
        when(jdbcTemplate.queryForList(
                contains("vinculo_colaborador_obra"),
                eq(String.class),
                eq("beta")
        )).thenReturn(List.of(WORKSITE_A));

        mockMvc.perform(get("/api/ontology/memory")
                        .requestAttr(CurrentUserService.REQUEST_ATTRIBUTE_USER_ID, "beta"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isArray())
                .andExpect(jsonPath("$.highWaterMark").value(0))
                .andExpect(jsonPath("$.scopeHash").value("scope-hash"))
                .andExpect(jsonPath("$.coverage.mode").value("FULL_HISTORY"));

        verify(service).search(
                eq(OperationalMemoryScope.beta("beta", Set.of(WORKSITE_A))),
                eq(OperationalMemoryFilter.empty()),
                eq(50),
                eq(null)
        );
    }

    @Test
    void betaCannotQueryAnUnauthorizedWorksite() throws Exception {
        papel("beta", PapelAcesso.BETA);
        vinculo("beta", WORKSITE_B, false);

        mockMvc.perform(get("/api/ontology/memory")
                        .param("obraId", WORKSITE_B)
                        .requestAttr(CurrentUserService.REQUEST_ATTRIBUTE_USER_ID, "beta"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("MEMORY_ACCESS_DENIED"))
                .andExpect(jsonPath("$.message").doesNotExist());

        verify(service, never()).search(any(), any(), any(), any());
    }

    @Test
    void alfaCanReadTheGlobalScope() throws Exception {
        papel("alfa", PapelAcesso.ALFA);

        mockMvc.perform(get("/api/ontology/memory")
                        .requestAttr(CurrentUserService.REQUEST_ATTRIBUTE_USER_ID, "alfa"))
                .andExpect(status().isOk());

        verify(service).search(
                eq(OperationalMemoryScope.alfa("alfa")),
                eq(OperationalMemoryFilter.empty()),
                eq(50),
                eq(null)
        );
    }

    @Test
    void forwardsSearchStructuralUtcAndStablePairCursorWithBoundedLimit()
            throws Exception {
        papel("beta", PapelAcesso.BETA);
        vinculo("beta", WORKSITE_A, true);
        when(jdbcTemplate.query(
                contains("FROM rdo"),
                any(ResultSetExtractor.class),
                eq("rdo-1")
        )).thenReturn(WORKSITE_A);
        when(jdbcTemplate.queryForList(
                contains("vinculo_colaborador_obra"),
                eq(String.class),
                eq("beta")
        )).thenReturn(List.of(WORKSITE_A));

        mockMvc.perform(get("/api/ontology/memory")
                        .param("q", "drenagem norte")
                        .param("entityType", "rdo")
                        .param("entityId", "rdo-1")
                        .param("obraId", WORKSITE_A)
                        .param("rdoId", "rdo-1")
                        .param("actorId", "actor-1")
                        .param("eventType", "rdo_editado")
                        .param("origin", "offline")
                        .param("result", "sucesso")
                        .param("from", "2026-07-21T10:00:00Z")
                        .param("to", "2026-07-21T12:00:00Z")
                        .param("limit", "500")
                        .param("beforeCommitSequence", "77")
                        .param("beforeEventId", "event-77")
                        .requestAttr(CurrentUserService.REQUEST_ATTRIBUTE_USER_ID, "beta"))
                .andExpect(status().isOk());

        verify(service).search(
                eq(OperationalMemoryScope.beta("beta", Set.of(WORKSITE_A))),
                eq(new OperationalMemoryFilter(
                        "drenagem norte",
                        "rdo",
                        "rdo-1",
                        WORKSITE_A,
                        "rdo-1",
                        "actor-1",
                        "rdo_editado",
                        "offline",
                        "sucesso",
                        Instant.parse("2026-07-21T10:00:00Z"),
                        Instant.parse("2026-07-21T12:00:00Z")
                )),
                eq(100),
                eq(new OperationalMemoryCursor(77L, "event-77"))
        );
    }

    @Test
    void rejectsPartialCursorInvertedUtcRangeAndOversizedSearchBeforeQuery()
            throws Exception {
        papel("alfa", PapelAcesso.ALFA);

        mockMvc.perform(get("/api/ontology/memory")
                        .param("beforeCommitSequence", "77")
                        .requestAttr(CurrentUserService.REQUEST_ATTRIBUTE_USER_ID, "alfa"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MEMORY_CURSOR_INVALID"));
        mockMvc.perform(get("/api/ontology/memory")
                        .param("from", "2026-07-21T12:00:00Z")
                        .param("to", "2026-07-21T10:00:00Z")
                        .requestAttr(CurrentUserService.REQUEST_ATTRIBUTE_USER_ID, "alfa"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MEMORY_UTC_RANGE_INVALID"));
        mockMvc.perform(get("/api/ontology/memory")
                        .param("q", "q".repeat(201))
                        .requestAttr(CurrentUserService.REQUEST_ATTRIBUTE_USER_ID, "alfa"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MEMORY_SEARCH_LIMIT"));

        verify(service, never()).search(any(), any(), any(), any());
    }

    @Test
    void rejectsCursorFromAnotherAuthorizationScopeWithSafeCode() throws Exception {
        papel("alfa", PapelAcesso.ALFA);
        when(service.search(any(), any(), any(), any()))
                .thenThrow(new OperationalMemoryCursorScopeException());

        mockMvc.perform(get("/api/ontology/memory")
                        .param("beforeCommitSequence", "101")
                        .param("beforeEventId", "foreign-event")
                        .requestAttr(CurrentUserService.REQUEST_ATTRIBUTE_USER_ID, "alfa"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MEMORY_CURSOR_SCOPE_MISMATCH"))
                .andExpect(jsonPath("$.message").doesNotExist());
    }

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
}
