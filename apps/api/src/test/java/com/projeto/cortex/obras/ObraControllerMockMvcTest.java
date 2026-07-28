package com.projeto.cortex.obras;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.projeto.cortex.auth.CurrentUserService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.server.ResponseStatusException;

import java.util.stream.Stream;

@WebMvcTest(ObraController.class)
@AutoConfigureMockMvc(addFilters = false)
class ObraControllerMockMvcTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private ObraService service;

    @MockBean
    private CurrentUserService currentUser;

    @MockBean
    private ObrasRelacionadasService related;

    @Test
    void betaCannotCreateWorksiteByCallingTheEndpointDirectly()
            throws Exception {
        doThrow(new ResponseStatusException(HttpStatus.FORBIDDEN, "Alfa"))
                .when(currentUser).requireAlfa();

        mockMvc.perform(post("/api/obras")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"codigoContrato":"CW-1","nome":"Obra 1"}
                                """))
                .andExpect(status().isForbidden());

        verify(service, never()).criarObra(any(), any());
    }

    @Test
    void alfaCreatesWorksiteWithTheAuthenticatedActor() throws Exception {
        when(currentUser.requireUserId()).thenReturn("alfa-1");

        mockMvc.perform(post("/api/obras")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"codigoContrato":"CW-1","nome":"Obra 1"}
                                """))
                .andExpect(status().isCreated());

        verify(currentUser).requireAlfa();
        verify(service).criarObra(any(ObraRequest.class), eq("alfa-1"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("betaLifecycleRequests")
    void betaCannotUseAnyLifecycleEndpoint(
            String operation,
            MockHttpServletRequestBuilder request
    ) throws Exception {
        doThrow(new ResponseStatusException(HttpStatus.FORBIDDEN, "Alfa"))
                .when(currentUser).requireAlfa();

        mockMvc.perform(request)
                .andExpect(status().isForbidden());

        verifyNoInteractions(service);
    }

    @Test
    void alfaUpdatesWorksiteWithTheAuthenticatedActor() throws Exception {
        when(currentUser.requireUserId()).thenReturn("alfa-1");

        mockMvc.perform(patch("/api/obras/obra-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "codigoContrato":"CW-2",
                                  "nome":"Obra Editada",
                                  "baseVersion":1
                                }
                                """))
                .andExpect(status().isOk());

        verify(currentUser).requireAlfa();
        verify(service).atualizarObra(
                eq("obra-1"),
                any(ObraUpdateRequest.class),
                eq("alfa-1")
        );
    }

    @Test
    void alfaExecutesLifecycleTransitionsWithTheAuthenticatedActor()
            throws Exception {
        when(currentUser.requireUserId()).thenReturn("alfa-1");

        mockMvc.perform(post("/api/obras/obra-1/desativar")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"baseVersion":1}
                                """))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/obras/obra-1/arquivar")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"baseVersion":2}
                                """))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/obras/obra-1/restaurar")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"baseVersion":3}
                                """))
                .andExpect(status().isOk());

        verify(currentUser, times(3)).requireAlfa();
        verify(service).desativarObra(
                eq("obra-1"), any(ObraVersionRequest.class), eq("alfa-1")
        );
        verify(service).arquivarObra(
                eq("obra-1"), any(ObraVersionRequest.class), eq("alfa-1")
        );
        verify(service).restaurarObra(
                eq("obra-1"), any(ObraVersionRequest.class), eq("alfa-1")
        );
    }

    @Test
    void alfaListsArchivedWorksitesWithTheAuthenticatedActor()
            throws Exception {
        when(currentUser.requireUserId()).thenReturn("alfa-1");

        mockMvc.perform(get("/api/obras/arquivadas").param("query", "ponte"))
                .andExpect(status().isOk());

        verify(currentUser).requireAlfa();
        verify(service).listarObrasArquivadas("ponte", "alfa-1");
    }

    @Test
    void divergentVersionIsExposedAsConflict() throws Exception {
        when(currentUser.requireUserId()).thenReturn("alfa-1");
        when(service.arquivarObra(
                eq("obra-1"),
                any(ObraVersionRequest.class),
                eq("alfa-1")
        )).thenThrow(new ResponseStatusException(
                HttpStatus.CONFLICT,
                "A obra foi alterada por outra operação."
        ));

        mockMvc.perform(post("/api/obras/obra-1/arquivar")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"baseVersion":0}
                                """))
                .andExpect(status().isConflict());

        verify(service).arquivarObra(
                eq("obra-1"),
                argThat(request -> request.baseVersion() == 0L),
                eq("alfa-1")
        );
    }

    @Test
    void missingBaseVersionIsExposedAsBadRequest() throws Exception {
        when(currentUser.requireUserId()).thenReturn("alfa-1");
        when(service.desativarObra(
                eq("obra-1"),
                argThat(request -> request.baseVersion() == null),
                eq("alfa-1")
        )).thenThrow(new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "baseVersion é obrigatório."
        ));

        mockMvc.perform(post("/api/obras/obra-1/desativar")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void negativeBaseVersionIsExposedAsBadRequest() throws Exception {
        when(currentUser.requireUserId()).thenReturn("alfa-1");
        when(service.arquivarObra(
                eq("obra-1"),
                argThat(request -> request.baseVersion() == -1L),
                eq("alfa-1")
        )).thenThrow(new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "baseVersion não pode ser negativo."
        ));

        mockMvc.perform(post("/api/obras/obra-1/arquivar")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"baseVersion":-1}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void invalidRestoreTransitionIsExposedAsConflict() throws Exception {
        when(currentUser.requireUserId()).thenReturn("alfa-1");
        when(service.restaurarObra(
                eq("obra-1"),
                argThat(request -> request.baseVersion() == 1L),
                eq("alfa-1")
        )).thenThrow(new ResponseStatusException(
                HttpStatus.CONFLICT,
                "A obra não está arquivada."
        ));

        mockMvc.perform(post("/api/obras/obra-1/restaurar")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"baseVersion":1}
                                """))
                .andExpect(status().isConflict());
    }

    private static Stream<Arguments> betaLifecycleRequests() {
        return Stream.of(
                Arguments.of(
                        "PATCH /api/obras/{obraId}",
                        patch("/api/obras/obra-1")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {
                                          "codigoContrato":"CW-2",
                                          "nome":"Obra Editada",
                                          "baseVersion":1
                                        }
                                        """)
                ),
                Arguments.of(
                        "POST /api/obras/{obraId}/desativar",
                        versionedPost("/api/obras/obra-1/desativar")
                ),
                Arguments.of(
                        "POST /api/obras/{obraId}/arquivar",
                        versionedPost("/api/obras/obra-1/arquivar")
                ),
                Arguments.of(
                        "POST /api/obras/{obraId}/restaurar",
                        versionedPost("/api/obras/obra-1/restaurar")
                ),
                Arguments.of(
                        "GET /api/obras/arquivadas",
                        get("/api/obras/arquivadas")
                )
        );
    }

    private static MockHttpServletRequestBuilder versionedPost(String path) {
        return post(path)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"baseVersion":1}
                        """);
    }
}
