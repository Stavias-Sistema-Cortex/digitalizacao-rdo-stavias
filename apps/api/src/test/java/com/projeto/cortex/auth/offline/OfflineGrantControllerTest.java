package com.projeto.cortex.auth.offline;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.projeto.cortex.auth.CurrentUserService;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;

class OfflineGrantControllerTest {

    private static final UUID COLLABORATOR_ID = UUID.fromString(
            "10000000-0000-0000-0000-000000000001"
    );

    private final CurrentUserService currentUsers =
            mock(CurrentUserService.class);
    private final OfflineGrantService grants =
            mock(OfflineGrantService.class);
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(
                new OfflineGrantController(currentUsers, grants)
        ).setControllerAdvice(new OfflineGrantExceptionHandler()).build();
    }

    @Test
    void issuesOnlyForTheAuthenticatedCollaboratorAndNeverCachesIt()
            throws Exception {
        OfflineGrant grant = new OfflineGrant(
                "grant-key-2030-01",
                "cGF5bG9hZA",
                "c2lnbmF0dXJl",
                "cHVibGljLWtleQ"
        );
        when(currentUsers.requireUserId())
                .thenReturn(COLLABORATOR_ID.toString());
        when(grants.issue(COLLABORATOR_ID)).thenReturn(grant);

        mockMvc.perform(post("/api/auth/offline-grant"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.keyId").value("grant-key-2030-01"))
                .andExpect(jsonPath("$.payload").value("cGF5bG9hZA"))
                .andExpect(jsonPath("$.signature").value("c2lnbmF0dXJl"))
                .andExpect(jsonPath("$.publicKeySpki").value(
                        "cHVibGljLWtleQ"
                ))
                .andExpect(jsonPath("$.cpf").doesNotExist())
                .andExpect(jsonPath("$.email").doesNotExist())
                .andExpect(jsonPath("$.token").doesNotExist());

        verify(grants).issue(COLLABORATOR_ID);
    }

    @Test
    void malformedPrincipalFailsClosedWithoutIssuingAGrant() throws Exception {
        when(currentUsers.requireUserId()).thenReturn("not-a-uuid");

        mockMvc.perform(post("/api/auth/offline-grant"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("Cache-Control", "no-store"));
    }

    @Test
    void scopeFailureReturnsTheBoundedPortugueseRecoveryMessage()
            throws Exception {
        String message = "Escopo offline grande demais; reconecte-se.";
        when(currentUsers.requireUserId())
                .thenReturn(COLLABORATOR_ID.toString());
        when(grants.issue(COLLABORATOR_ID)).thenThrow(
                new ResponseStatusException(
                        HttpStatus.UNPROCESSABLE_ENTITY,
                        message
                )
        );

        mockMvc.perform(post("/api/auth/offline-grant"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.message").value(message))
                .andExpect(jsonPath("$.trace").doesNotExist())
                .andExpect(jsonPath("$.cause").doesNotExist());
    }
}
