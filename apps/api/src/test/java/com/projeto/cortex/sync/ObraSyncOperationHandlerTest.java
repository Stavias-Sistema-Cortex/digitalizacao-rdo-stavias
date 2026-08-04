package com.projeto.cortex.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.projeto.cortex.auth.CurrentUserService;
import com.projeto.cortex.obras.ObraResponse;
import com.projeto.cortex.obras.ObraService;
import com.projeto.cortex.obras.ObraUpdateRequest;
import com.projeto.cortex.obras.ObraVersionRequest;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class ObraSyncOperationHandlerTest {

    private static final String OBRA_ID =
            "10000000-0000-4000-8000-000000000001";
    private static final String ACTOR_ID =
            "20000000-0000-4000-8000-000000000002";
    private static final String DEVICE_ID =
            "30000000-0000-4000-8000-000000000003";
    private static final long BASE_VERSION = 7L;

    private final ObjectMapper mapper =
            new ObjectMapper().findAndRegisterModules();

    @Test
    void updateRevalidatesAlfaAndDelegatesTheCompleteRequestToTheDomain() {
        ObraService service = mock(ObraService.class);
        CurrentUserService currentUser = mock(CurrentUserService.class);
        ObraResponse authoritative = response("ATIVA", null, 8L);
        when(service.atualizarObra(
                eq(OBRA_ID),
                any(ObraUpdateRequest.class),
                eq(ACTOR_ID)
        )).thenReturn(authoritative);
        ObraSyncOperationHandler handler =
                new ObraSyncOperationHandler(service, currentUser, mapper);

        AppliedSyncMutation applied = handler.apply(
                mutation(
                        "ATUALIZAR_OBRA",
                        "UPDATE",
                        BASE_VERSION,
                        updatePayload()
                ),
                new SyncMutationContext(ACTOR_ID, DEVICE_ID)
        );

        verify(currentUser).requireAlfa();
        verify(service).atualizarObra(
                eq(OBRA_ID),
                eq(new ObraUpdateRequest(
                        "CW-2026-009",
                        "INT-09",
                        "Contorno Norte",
                        "DER",
                        "Recuperação funcional",
                        "Campinas",
                        "SP",
                        "SP-101",
                        "obras-2026.csv",
                        "Frente prioritária",
                        BASE_VERSION
                )),
                eq(ACTOR_ID)
        );
        assertThat(applied.entityType()).isEqualTo("OBRA");
        assertThat(applied.entityId()).isEqualTo(OBRA_ID);
        assertThat(applied.result()).isEqualTo(mapper.valueToTree(authoritative));
        assertThat(applied.result().path("versaoLinha").asLong()).isEqualTo(8L);
        assertThat(applied.authoritativeEvent().eventType())
                .isEqualTo("OBRA_ATUALIZADA");
        assertThat(applied.authoritativeEvent().relatedEntities()).isEmpty();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("transitions")
    void transitionsRevalidateAlfaAndReturnTheAuthoritativeDomainSnapshot(
            String operation,
            String canonicalOperation,
            String expectedEvent,
            String status,
            LocalDateTime archivedAt,
            long authoritativeVersion
    ) {
        ObraService service = mock(ObraService.class);
        CurrentUserService currentUser = mock(CurrentUserService.class);
        ObraResponse authoritative =
                response(status, archivedAt, authoritativeVersion);
        stubTransition(service, operation, authoritative);
        ObraSyncOperationHandler handler =
                new ObraSyncOperationHandler(service, currentUser, mapper);

        AppliedSyncMutation applied = handler.apply(
                mutation(
                        operation,
                        canonicalOperation,
                        BASE_VERSION,
                        transitionPayload(status, archivedAt)
                ),
                new SyncMutationContext(ACTOR_ID, DEVICE_ID)
        );

        verify(currentUser).requireAlfa();
        verifyTransition(service, operation);
        assertThat(applied.result()).isEqualTo(mapper.valueToTree(authoritative));
        assertThat(applied.authoritativeEvent().eventType())
                .isEqualTo(expectedEvent);
        assertThat(applied.result().path("status").asText()).isEqualTo(status);
        assertThat(applied.result().path("versaoLinha").asLong())
                .isEqualTo(authoritativeVersion);
    }

    @ParameterizedTest(name = "{0} exige baseVersion")
    @MethodSource("operations")
    void everyOperationRejectsMissingBaseVersionBeforeAuthorizationOrDomainWrite(
            String operation,
            String canonicalOperation
    ) {
        ObraService service = mock(ObraService.class);
        CurrentUserService currentUser = mock(CurrentUserService.class);
        ObraSyncOperationHandler handler =
                new ObraSyncOperationHandler(service, currentUser, mapper);

        assertThatThrownBy(() -> handler.apply(
                mutation(
                        operation,
                        canonicalOperation,
                        null,
                        "ATUALIZAR_OBRA".equals(operation)
                                ? updatePayload()
                                : transitionPayload("ATIVA", null)
                ),
                new SyncMutationContext(ACTOR_ID, DEVICE_ID)
        )).isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("baseVersion");

        verifyNoInteractions(currentUser, service);
        assertThat(handler.requiresBaseVersion(operation)).isTrue();
    }

    @Test
    void revokedAlfaPermissionStopsReplayBeforeCallingTheDomain() {
        ObraService service = mock(ObraService.class);
        CurrentUserService currentUser = mock(CurrentUserService.class);
        doThrow(new ResponseStatusException(
                HttpStatus.FORBIDDEN,
                "A operação exige perfil administrativo (Alfa)."
        )).when(currentUser).requireAlfa();
        ObraSyncOperationHandler handler =
                new ObraSyncOperationHandler(service, currentUser, mapper);

        assertThatThrownBy(() -> handler.apply(
                mutation(
                        "ARQUIVAR_OBRA",
                        "DELETE",
                        BASE_VERSION,
                        transitionPayload("ATIVA", LocalDateTime.now())
                ),
                new SyncMutationContext(ACTOR_ID, DEVICE_ID)
        )).isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Alfa");

        verify(service, never()).arquivarObra(
                any(), any(ObraVersionRequest.class), any()
        );
    }

    @Test
    void payloadIdentityCannotSelectAWorksiteDifferentFromTheEnvelope() {
        ObraService service = mock(ObraService.class);
        CurrentUserService currentUser = mock(CurrentUserService.class);
        ObjectNode payload = updatePayload();
        payload.put("obraId", "90000000-0000-4000-8000-000000000009");
        ObraSyncOperationHandler handler =
                new ObraSyncOperationHandler(service, currentUser, mapper);

        assertThatThrownBy(() -> handler.apply(
                mutation(
                        "ATUALIZAR_OBRA",
                        "UPDATE",
                        BASE_VERSION,
                        payload
                ),
                new SyncMutationContext(ACTOR_ID, DEVICE_ID)
        )).isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("obraId");

        verifyNoInteractions(currentUser, service);
    }

    @Test
    void exposesExactlyTheFiveCanonicalWorksiteOperations() {
        ObraSyncOperationHandler handler = new ObraSyncOperationHandler(
                mock(ObraService.class),
                mock(CurrentUserService.class),
                mapper
        );

        assertThat(handler.entityType()).isEqualTo("OBRA");
        assertThat(handler.operations()).containsExactlyInAnyOrderElementsOf(
                Set.of(
                        "ATUALIZAR_OBRA",
                        "DESATIVAR_OBRA",
                        // Desativar era porta de mão única: nada devolvia o
                        // status, e restaurar só desfaz o arquivamento.
                        "ATIVAR_OBRA",
                        "ARQUIVAR_OBRA",
                        "RESTAURAR_OBRA"
                )
        );
    }

    private void stubTransition(
            ObraService service,
            String operation,
            ObraResponse response
    ) {
        switch (operation) {
            case "DESATIVAR_OBRA" -> when(service.desativarObra(
                    eq(OBRA_ID), any(ObraVersionRequest.class), eq(ACTOR_ID)
            )).thenReturn(response);
            case "ARQUIVAR_OBRA" -> when(service.arquivarObra(
                    eq(OBRA_ID), any(ObraVersionRequest.class), eq(ACTOR_ID)
            )).thenReturn(response);
            case "RESTAURAR_OBRA" -> when(service.restaurarObra(
                    eq(OBRA_ID), any(ObraVersionRequest.class), eq(ACTOR_ID)
            )).thenReturn(response);
            default -> throw new IllegalArgumentException(operation);
        }
    }

    private void verifyTransition(ObraService service, String operation) {
        ObraVersionRequest request = new ObraVersionRequest(BASE_VERSION);
        switch (operation) {
            case "DESATIVAR_OBRA" -> verify(service).desativarObra(
                    OBRA_ID, request, ACTOR_ID
            );
            case "ARQUIVAR_OBRA" -> verify(service).arquivarObra(
                    OBRA_ID, request, ACTOR_ID
            );
            case "RESTAURAR_OBRA" -> verify(service).restaurarObra(
                    OBRA_ID, request, ACTOR_ID
            );
            default -> throw new IllegalArgumentException(operation);
        }
    }

    private SyncPushRequest.MutacaoCliente mutation(
            String operation,
            String canonicalOperation,
            Long baseVersion,
            ObjectNode payload
    ) {
        return new SyncPushRequest.MutacaoCliente(
                "40000000-0000-4000-8000-000000000004",
                "OBRA",
                OBRA_ID,
                operation,
                baseVersion,
                payload,
                LocalDateTime.of(2026, 7, 28, 12, 0),
                "50000000-0000-4000-8000-000000000005",
                13,
                DEVICE_ID,
                ACTOR_ID,
                OBRA_ID,
                "OBRA",
                OBRA_ID,
                canonicalOperation,
                baseVersion,
                List.of(),
                "2026-07-28T15:00:00.000Z",
                null,
                null,
                List.of(),
                List.of()
        );
    }

    private ObjectNode updatePayload() {
        return mapper.createObjectNode()
                .put("id", OBRA_ID)
                .put("obraId", OBRA_ID)
                .put("codigoContrato", "CW-2026-009")
                .put("codigoInterno", "INT-09")
                .put("nome", "Contorno Norte")
                .put("cliente", "DER")
                .put("descricao", "Recuperação funcional")
                .put("cidade", "Campinas")
                .put("uf", "SP")
                .put("rodovia", "SP-101")
                .put("fonteArquivo", "obras-2026.csv")
                .put("observacoes", "Frente prioritária");
    }

    private ObjectNode transitionPayload(
            String status,
            LocalDateTime archivedAt
    ) {
        ObjectNode payload = mapper.createObjectNode()
                .put("id", OBRA_ID)
                .put("obraId", OBRA_ID)
                .put("status", status);
        if (archivedAt == null) {
            payload.putNull("arquivadoEm");
        } else {
            payload.put("arquivadoEm", archivedAt.toString());
        }
        return payload;
    }

    private ObraResponse response(
            String status,
            LocalDateTime archivedAt,
            long version
    ) {
        return new ObraResponse(
                OBRA_ID,
                "CW-2026-009",
                "CW2026009",
                "INT-09",
                "Contorno Norte",
                "DER",
                "Recuperação funcional",
                "Campinas",
                "SP",
                "SP-101",
                new BigDecimal("-22.9000"),
                new BigDecimal("-47.0600"),
                status,
                "MANUAL",
                "obras-2026.csv",
                "Frente prioritária",
                LocalDateTime.of(2026, 7, 20, 8, 0),
                LocalDateTime.of(2026, 7, 28, 12, 0),
                archivedAt,
                version
        );
    }

    private static Stream<Arguments> transitions() {
        LocalDateTime archivedAt =
                LocalDateTime.of(2026, 7, 28, 12, 0);
        return Stream.of(
                Arguments.of(
                        "DESATIVAR_OBRA",
                        "TRANSITION",
                        "OBRA_DESATIVADA",
                        "INATIVA",
                        null,
                        8L
                ),
                Arguments.of(
                        "ARQUIVAR_OBRA",
                        "DELETE",
                        "OBRA_ARQUIVADA",
                        "ATIVA",
                        archivedAt,
                        8L
                ),
                Arguments.of(
                        "RESTAURAR_OBRA",
                        "TRANSITION",
                        "OBRA_RESTAURADA",
                        "INATIVA",
                        null,
                        8L
                )
        );
    }

    private static Stream<Arguments> operations() {
        return Stream.of(
                Arguments.of("ATUALIZAR_OBRA", "UPDATE"),
                Arguments.of("DESATIVAR_OBRA", "TRANSITION"),
                Arguments.of("ARQUIVAR_OBRA", "DELETE"),
                Arguments.of("RESTAURAR_OBRA", "TRANSITION")
        );
    }
}
