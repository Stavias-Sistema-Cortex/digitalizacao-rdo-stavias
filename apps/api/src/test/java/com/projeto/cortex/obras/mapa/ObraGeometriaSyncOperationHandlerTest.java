package com.projeto.cortex.obras.mapa;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.projeto.cortex.sync.AppliedSyncMutation;
import com.projeto.cortex.sync.SyncMutationContext;
import com.projeto.cortex.sync.SyncOperationRegistry;
import com.projeto.cortex.sync.SyncPushRequest;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ObraGeometriaSyncOperationHandlerTest {

    private static final String OBRA_ID = "10000000-0000-4000-8000-000000000001";
    private static final String FEATURE_ID = "60000000-0000-4000-8000-000000000006";
    private static final String ACTOR_ID = "20000000-0000-4000-8000-000000000002";
    private static final String DEVICE_ID = "30000000-0000-4000-8000-000000000003";

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final ObraMapaService service = mock(ObraMapaService.class);
    private final ObraGeometriaSyncOperationHandler handler =
            new ObraGeometriaSyncOperationHandler(service, mapper);

    private ObraGeometriaResponse persisted(String categoria, String fonte, long versao) {
        try {
            return new ObraGeometriaResponse(
                    FEATURE_ID, categoria, "RDO", "rdo-1",
                    mapper.readTree(
                            "{\"type\":\"Point\",\"coordinates\":[-54.65,-20.44]}"
                    ),
                    Map.of(), fonte, "ATIVA",
                    LocalDateTime.of(2026, 7, 28, 12, 0), null, null, versao,
                    ACTOR_ID, ACTOR_ID,
                    LocalDateTime.of(2026, 7, 28, 12, 0),
                    LocalDateTime.of(2026, 7, 28, 12, 0)
            );
        } catch (Exception error) {
            throw new IllegalStateException(error);
        }
    }

    private ObjectNode payload() {
        ObjectNode payload = mapper.createObjectNode();
        payload.put("obraId", OBRA_ID);
        payload.put("categoria", "PONTO_OPERACIONAL");
        payload.put("objetoTipo", "RDO");
        payload.put("objetoId", "rdo-1");
        ObjectNode geometry = payload.putObject("geometry");
        geometry.put("type", "Point");
        geometry.putArray("coordinates").add(-54.65).add(-20.44);
        payload.putObject("properties").put("precisaoM", 4.5);
        return payload;
    }

    private SyncPushRequest.MutacaoCliente mutation(
            String operation,
            Long baseVersion,
            String entityId,
            ObjectNode payload
    ) {
        return new SyncPushRequest.MutacaoCliente(
                "40000000-0000-4000-8000-000000000004",
                "GEOMETRIA_OBRA", entityId, operation, baseVersion, payload,
                LocalDateTime.of(2026, 7, 28, 12, 0),
                "50000000-0000-4000-8000-000000000005",
                13, DEVICE_ID, ACTOR_ID, OBRA_ID,
                "GEOMETRIA_OBRA", entityId, operation, baseVersion,
                List.of(), "2026-07-28T15:00:00.000Z", null, null,
                List.of(), List.of()
        );
    }

    @Test
    void registersTheHandlerUnderCanonicalGeometryOperations() {
        SyncOperationRegistry registry = new SyncOperationRegistry(List.of(handler));

        assertThat(registry.operations()).containsExactlyInAnyOrder(
                "REGISTRAR_GEOMETRIA_OBRA",
                "REGISTRAR_GEOMETRIA_CAMPO",
                "ATUALIZAR_GEOMETRIA_OBRA",
                "ENCERRAR_GEOMETRIA_OBRA"
        );
        assertThat(handler.entityType()).isEqualTo("GEOMETRIA_OBRA");
        assertThat(handler.requiresBaseVersion("ATUALIZAR_GEOMETRIA_OBRA")).isTrue();
        assertThat(handler.requiresBaseVersion("ENCERRAR_GEOMETRIA_OBRA")).isTrue();
        assertThat(handler.requiresBaseVersion("REGISTRAR_GEOMETRIA_CAMPO")).isFalse();
    }

    @Test
    void fieldCaptureQueuedOfflineReachesTheScopedDomainMethod() {
        when(service.registrarCapturaCampo(eq(OBRA_ID), any()))
                .thenReturn(persisted("PONTO_OPERACIONAL", "CAPTURA_CAMPO", 0L));

        AppliedSyncMutation applied = handler.apply(
                mutation("REGISTRAR_GEOMETRIA_CAMPO", null, null, payload()),
                new SyncMutationContext(ACTOR_ID, DEVICE_ID)
        );

        ArgumentCaptor<ObraGeometriaRequest> captor =
                ArgumentCaptor.forClass(ObraGeometriaRequest.class);
        verify(service).registrarCapturaCampo(eq(OBRA_ID), captor.capture());
        verify(service, never()).criar(any(), any());
        assertThat(captor.getValue().categoria()).isEqualTo("PONTO_OPERACIONAL");
        assertThat(captor.getValue().objetoTipo()).isEqualTo("RDO");
        assertThat(captor.getValue().geometry().path("type").asText())
                .isEqualTo("Point");
        assertThat(applied.entityType()).isEqualTo("GEOMETRIA_OBRA");
        assertThat(applied.entityId()).isEqualTo(FEATURE_ID);
        assertThat(applied.authoritativeEvent().eventType()).isEqualTo("GEOMETRIA_CRIADA");
        assertThat(applied.authoritativeEvent().relatedEntities())
                .containsExactly(new AppliedSyncMutation.AuthoritativeRelatedEntity(
                        "OBRA", OBRA_ID
                ));
    }

    @Test
    void drawnStretchGoesThroughTheAlfaGatedDomainMethod() {
        ObjectNode payload = payload();
        payload.put("categoria", "TRECHO");
        payload.put("objetoTipo", "TRECHO");
        payload.put("objetoId", "trecho-1");
        when(service.criar(eq(OBRA_ID), any()))
                .thenReturn(persisted("TRECHO", "GESTAO_MAPA", 0L));

        handler.apply(
                mutation("REGISTRAR_GEOMETRIA_OBRA", null, null, payload),
                new SyncMutationContext(ACTOR_ID, DEVICE_ID)
        );

        verify(service).criar(eq(OBRA_ID), any());
        verify(service, never()).registrarCapturaCampo(any(), any());
    }

    @Test
    void updateForwardsBaseVersionSoTheDomainCanRejectStaleWrites() {
        when(service.atualizar(eq(OBRA_ID), eq(FEATURE_ID), any()))
                .thenReturn(persisted("PONTO_OPERACIONAL", "CAPTURA_CAMPO", 8L));
        ObjectNode payload = payload();
        payload.put("id", FEATURE_ID);
        payload.put("motivo", "Correção topográfica");

        AppliedSyncMutation applied = handler.apply(
                mutation("ATUALIZAR_GEOMETRIA_OBRA", 7L, FEATURE_ID, payload),
                new SyncMutationContext(ACTOR_ID, DEVICE_ID)
        );

        ArgumentCaptor<ObraGeometriaRequest> captor =
                ArgumentCaptor.forClass(ObraGeometriaRequest.class);
        verify(service).atualizar(eq(OBRA_ID), eq(FEATURE_ID), captor.capture());
        assertThat(captor.getValue().baseVersao()).isEqualTo(7L);
        assertThat(captor.getValue().motivo()).isEqualTo("Correção topográfica");
        assertThat(applied.authoritativeEvent().eventType())
                .isEqualTo("GEOMETRIA_ATUALIZADA");
    }

    @Test
    void closureForwardsReasonAndValidityEnd() {
        when(service.encerrar(eq(OBRA_ID), eq(FEATURE_ID), any()))
                .thenReturn(persisted("TRECHO", "GESTAO_MAPA", 9L));
        ObjectNode payload = payload();
        payload.put("id", FEATURE_ID);
        payload.put("motivo", "Trecho concluído");
        payload.put("validoAte", "2026-07-30T18:00:00");

        handler.apply(
                mutation("ENCERRAR_GEOMETRIA_OBRA", 8L, FEATURE_ID, payload),
                new SyncMutationContext(ACTOR_ID, DEVICE_ID)
        );

        ArgumentCaptor<ObraGeometriaEndRequest> captor =
                ArgumentCaptor.forClass(ObraGeometriaEndRequest.class);
        verify(service).encerrar(eq(OBRA_ID), eq(FEATURE_ID), captor.capture());
        assertThat(captor.getValue().baseVersao()).isEqualTo(8L);
        assertThat(captor.getValue().motivo()).isEqualTo("Trecho concluído");
        assertThat(captor.getValue().validoAte())
                .isEqualTo(LocalDateTime.of(2026, 7, 30, 18, 0));
    }

    /**
     * A PWA carimba as datas com `Date#toISOString`, sempre em UTC com sufixo
     * `Z`. Recusar esse formato inutilizaria toda a escrita offline.
     */
    @Test
    void acceptsTheUtcInstantThePwaActuallySends() {
        when(service.registrarCapturaCampo(eq(OBRA_ID), any()))
                .thenReturn(persisted("PONTO_OPERACIONAL", "CAPTURA_CAMPO", 0L));
        ObjectNode payload = payload();
        payload.put("validoDesde", "2026-07-28T15:30:00.000Z");

        handler.apply(
                mutation("REGISTRAR_GEOMETRIA_CAMPO", null, null, payload),
                new SyncMutationContext(ACTOR_ID, DEVICE_ID)
        );

        ArgumentCaptor<ObraGeometriaRequest> captor =
                ArgumentCaptor.forClass(ObraGeometriaRequest.class);
        verify(service).registrarCapturaCampo(eq(OBRA_ID), captor.capture());
        assertThat(captor.getValue().validoDesde())
                .isEqualTo(LocalDateTime.of(2026, 7, 28, 15, 30));
    }

    @Test
    void convertsAnOffsetInstantToUtcBeforePersisting() {
        when(service.registrarCapturaCampo(eq(OBRA_ID), any()))
                .thenReturn(persisted("PONTO_OPERACIONAL", "CAPTURA_CAMPO", 0L));
        ObjectNode payload = payload();
        payload.put("validoDesde", "2026-07-28T12:30:00-03:00");

        handler.apply(
                mutation("REGISTRAR_GEOMETRIA_CAMPO", null, null, payload),
                new SyncMutationContext(ACTOR_ID, DEVICE_ID)
        );

        ArgumentCaptor<ObraGeometriaRequest> captor =
                ArgumentCaptor.forClass(ObraGeometriaRequest.class);
        verify(service).registrarCapturaCampo(eq(OBRA_ID), captor.capture());
        assertThat(captor.getValue().validoDesde())
                .isEqualTo(LocalDateTime.of(2026, 7, 28, 15, 30));
    }

    @Test
    void stillAcceptsAnInstantWithoutZone() {
        when(service.encerrar(eq(OBRA_ID), eq(FEATURE_ID), any()))
                .thenReturn(persisted("TRECHO", "GESTAO_MAPA", 9L));
        ObjectNode payload = payload();
        payload.put("id", FEATURE_ID);
        payload.put("motivo", "Trecho concluído");
        payload.put("validoAte", "2026-07-30T18:00:00");

        handler.apply(
                mutation("ENCERRAR_GEOMETRIA_OBRA", 8L, FEATURE_ID, payload),
                new SyncMutationContext(ACTOR_ID, DEVICE_ID)
        );

        ArgumentCaptor<ObraGeometriaEndRequest> captor =
                ArgumentCaptor.forClass(ObraGeometriaEndRequest.class);
        verify(service).encerrar(eq(OBRA_ID), eq(FEATURE_ID), captor.capture());
        assertThat(captor.getValue().validoAte())
                .isEqualTo(LocalDateTime.of(2026, 7, 30, 18, 0));
    }

    @Test
    void rejectsATimestampThatIsNotAnInstant() {
        ObjectNode payload = payload();
        payload.put("validoDesde", "ontem de manhã");
        SyncPushRequest.MutacaoCliente mutation =
                mutation("REGISTRAR_GEOMETRIA_CAMPO", null, null, payload);

        assertThatThrownBy(() ->
                handler.apply(mutation, new SyncMutationContext(ACTOR_ID, DEVICE_ID)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("payload.validoDesde");
    }

    @Test
    void rejectsAPayloadIdentityThatDivergesFromTheEnvelope() {
        ObjectNode payload = payload();
        payload.put("id", "outro-id");
        SyncPushRequest.MutacaoCliente mutation =
                mutation("ATUALIZAR_GEOMETRIA_OBRA", 7L, FEATURE_ID, payload);

        assertThatThrownBy(() ->
                handler.apply(mutation, new SyncMutationContext(ACTOR_ID, DEVICE_ID)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("diverge do envelope canônico");

        verify(service, never()).atualizar(any(), any(), any());
    }

    @Test
    void rejectsAMutationWithoutAWorksite() {
        ObjectNode payload = payload();
        payload.remove("obraId");
        SyncPushRequest.MutacaoCliente mutation =
                mutation("REGISTRAR_GEOMETRIA_CAMPO", null, null, payload);

        assertThatThrownBy(() ->
                handler.apply(mutation, new SyncMutationContext(ACTOR_ID, DEVICE_ID)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("payload.obraId");
    }

    @Test
    void rejectsAMutationWithoutGeoJson() {
        ObjectNode payload = payload();
        payload.remove("geometry");
        SyncPushRequest.MutacaoCliente mutation =
                mutation("REGISTRAR_GEOMETRIA_CAMPO", null, null, payload);

        assertThatThrownBy(() ->
                handler.apply(mutation, new SyncMutationContext(ACTOR_ID, DEVICE_ID)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("payload.geometry");

        verify(service, never()).registrarCapturaCampo(any(), any());
    }

    @Test
    void surfacesTheDomainConflictInsteadOfSwallowingIt() {
        when(service.atualizar(eq(OBRA_ID), eq(FEATURE_ID), any()))
                .thenThrow(new ResponseStatusException(
                        HttpStatus.CONFLICT,
                        "A geometria foi alterada por outra operação."
                ));
        ObjectNode payload = payload();
        payload.put("id", FEATURE_ID);
        SyncPushRequest.MutacaoCliente mutation =
                mutation("ATUALIZAR_GEOMETRIA_OBRA", 7L, FEATURE_ID, payload);

        assertThatThrownBy(() ->
                handler.apply(mutation, new SyncMutationContext(ACTOR_ID, DEVICE_ID)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("alterada por outra operação");
    }
}
