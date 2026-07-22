package com.projeto.cortex.ontology;

import com.projeto.cortex.auth.CurrentUserService;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(OperationalMemoryController.class)
@AutoConfigureMockMvc(addFilters = false)
class OperationalMemoryControllerAuthorizationMockMvcTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private CurrentUserService currentUserService;

    @MockBean
    private OperationalMemoryQueryService service;

    @BeforeEach
    void emptyPage() {
        when(service.memory(any(), any(), any(Integer.class), any()))
                .thenReturn(new OperationalMemoryPageResponse(
                        List.of(),
                        null,
                        false,
                        "GLOBAL",
                        Instant.parse("2026-07-16T18:00:00Z")
                ));
    }

    @Test
    void betaWithoutExplicitWorksiteGetsAuthorizedAggregate() throws Exception {
        when(currentUserService.requireUserId()).thenReturn("beta");
        when(currentUserService.allowedObraIds("beta"))
                .thenReturn(Optional.of(Set.of("obra-1")));

        mockMvc.perform(get("/api/ontology/memory"))
                .andExpect(status().isOk());

        verify(service).memory(
                eq(OperationalMemoryScope.beta("beta", Set.of("obra-1"))),
                eq(OperationalMemoryFilter.empty()),
                eq(50),
                isNull()
        );
    }

    @Test
    void alfaGetsGlobalAggregate() throws Exception {
        when(currentUserService.requireUserId()).thenReturn("alfa");
        when(currentUserService.allowedObraIds("alfa"))
                .thenReturn(Optional.empty());

        mockMvc.perform(get("/api/ontology/memory"))
                .andExpect(status().isOk());

        verify(service).memory(
                eq(OperationalMemoryScope.alfa("alfa")),
                eq(OperationalMemoryFilter.empty()),
                eq(50),
                isNull()
        );
    }

    @Test
    void explicitUnauthorizedWorksiteIsRejected() throws Exception {
        when(currentUserService.requireUserId()).thenReturn("beta");
        doThrow(new ResponseStatusException(HttpStatus.FORBIDDEN))
                .when(currentUserService)
                .requireWorksiteAccess("obra-x");

        mockMvc.perform(get("/api/ontology/memory")
                        .param("obraId", "obra-x"))
                .andExpect(status().isForbidden());

        verify(service, never()).memory(any(), any(), any(), any());
    }

    @Test
    void explicitRdoIsAuthorizedAndFiltersReachService() throws Exception {
        when(currentUserService.requireUserId()).thenReturn("beta");
        when(currentUserService.allowedObraIds("beta"))
                .thenReturn(Optional.of(Set.of("obra-1")));

        mockMvc.perform(get("/api/ontology/memory")
                        .param("rdoId", "rdo-1")
                        .param("actorId", "actor-1")
                        .param("eventType", "rdo_editado")
                        .param("origin", "online")
                        .param("result", "sucesso")
                        .param("limit", "500")
                        .param("beforeCommitSeq", "77"))
                .andExpect(status().isOk());

        verify(currentUserService).requireRdoAccess("rdo-1");
        verify(service).memory(
                eq(OperationalMemoryScope.beta("beta", Set.of("obra-1"))),
                eq(new OperationalMemoryFilter(
                        null,
                        null,
                        null,
                        "rdo-1",
                        "actor-1",
                        "rdo_editado",
                        "online",
                        "sucesso",
                        null,
                        null
                )),
                eq(100),
                eq(77L)
        );
    }
}
