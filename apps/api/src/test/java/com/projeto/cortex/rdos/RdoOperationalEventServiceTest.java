package com.projeto.cortex.rdos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.projeto.cortex.memory.CortexOperationalMemoryService;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class RdoOperationalEventServiceTest {

    private static final String RDO_ID =
            "ed708c91-dd1e-4688-a007-a26705b5f598";
    private static final String OBRA_ID =
            "21500000-0000-4000-8000-000000000215";

    @Test
    @SuppressWarnings("unchecked")
    void shouldNormalizeCompositeOfflineEntityIdBeforePersistingEvent() {
        CortexOperationalMemoryService memoryService =
                mock(CortexOperationalMemoryService.class);
        RdoOperationalEventService service =
                new RdoOperationalEventService(memoryService);
        String localEquipmentId =
                "1856b59a-5124-430f-9887-bcd1e9523b4a";
        String compositeId = RDO_ID + ":equipamento:" + localEquipmentId;
        LocalDateTime occurredAt =
                LocalDateTime.of(2026, 7, 3, 12, 11, 22);
        RdoCreateRequest.OperationalEventItem event =
                new RdoCreateRequest.OperationalEventItem(
                        "d7fbc562-f726-4ffa-bdac-67a452e24cec",
                        "EQUIPAMENTO_ASSOCIADO_RDO",
                        new RdoCreateRequest.OperationalEntityRef(
                                "RDO_EQUIPAMENTO",
                                compositeId,
                                "Escavadeira"
                        ),
                        List.of(),
                        OBRA_ID,
                        RDO_ID,
                        null,
                        occurredAt,
                        null,
                        "OFFLINE",
                        null,
                        null,
                        Map.of("prefixo", "EQ-01"),
                        "PENDING_SYNC",
                        1
                );
        ArgumentCaptor<Map<String, Object>> payloadCaptor =
                ArgumentCaptor.forClass(Map.class);

        service.registrarEventosCliente(RDO_ID, OBRA_ID, List.of(event));

        verify(memoryService).registrarEventoDetalhado(
                eq("d7fbc562-f726-4ffa-bdac-67a452e24cec"),
                eq("RDO_EQUIPAMENTO"),
                eq(localEquipmentId),
                eq("EQUIPAMENTO_ASSOCIADO_RDO"),
                eq("RDO_SYNC_CLIENTE"),
                eq(OBRA_ID),
                eq(RDO_ID),
                isNull(),
                anyList(),
                eq("OFFLINE"),
                eq("SYNCED"),
                eq(occurredAt),
                any(LocalDateTime.class),
                eq(1),
                payloadCaptor.capture()
        );

        Map<String, Object> payload = payloadCaptor.getValue();
        assertThat(payload.get("prefixo")).isEqualTo("EQ-01");
        assertThat(payload.get("clientEventId"))
                .isEqualTo("d7fbc562-f726-4ffa-bdac-67a452e24cec");
        assertThat(payload.get("originalPrincipalEntity"))
                .isInstanceOf(Map.class);
        assertThat((Map<String, Object>) payload.get("originalPrincipalEntity"))
                .containsEntry("tipo", "RDO_EQUIPAMENTO")
                .containsEntry("id", compositeId)
                .containsEntry("nome", "Escavadeira");
    }

    @Test
    void shouldFallbackToRdoWhenOfflineEntityIdHasNoUuid() {
        CortexOperationalMemoryService memoryService =
                mock(CortexOperationalMemoryService.class);
        RdoOperationalEventService service =
                new RdoOperationalEventService(memoryService);
        RdoCreateRequest.OperationalEventItem event =
                new RdoCreateRequest.OperationalEventItem(
                        "a2e510e4-c0f5-4824-93f7-321a551cc7dd",
                        "EQUIPAMENTO_ASSOCIADO_RDO",
                        new RdoCreateRequest.OperationalEntityRef(
                                "RDO_EQUIPAMENTO",
                                "equipamento-sem-uuid-local",
                                null
                        ),
                        List.of(),
                        OBRA_ID,
                        RDO_ID,
                        null,
                        null,
                        null,
                        "OFFLINE",
                        null,
                        null,
                        Map.of(),
                        "PENDING_SYNC",
                        1
                );

        service.registrarEventosCliente(RDO_ID, OBRA_ID, List.of(event));

        verify(memoryService).registrarEventoDetalhado(
                eq("a2e510e4-c0f5-4824-93f7-321a551cc7dd"),
                eq("RDO"),
                eq(RDO_ID),
                eq("EQUIPAMENTO_ASSOCIADO_RDO"),
                eq("RDO_SYNC_CLIENTE"),
                eq(OBRA_ID),
                eq(RDO_ID),
                isNull(),
                anyList(),
                eq("OFFLINE"),
                eq("SYNCED"),
                isNull(),
                any(LocalDateTime.class),
                eq(1),
                any()
        );
    }

    @Test
    void shouldNeverPersistClientProvidedWorksiteOrRdoScope() {
        CortexOperationalMemoryService memoryService =
                mock(CortexOperationalMemoryService.class);
        RdoOperationalEventService service =
                new RdoOperationalEventService(memoryService);
        RdoCreateRequest.OperationalEventItem event =
                new RdoCreateRequest.OperationalEventItem(
                        "b2f2e604-8b13-48ad-98d0-dc345384d4a8",
                        "RDO_EDITADO",
                        null,
                        List.of(),
                        "obra-forjada",
                        "rdo-forjado",
                        null,
                        null,
                        null,
                        "OFFLINE",
                        "responsavel-forjado",
                        "Nome forjado",
                        Map.of(),
                        "PENDING_SYNC",
                        1
                );

        service.registrarEventosCliente(RDO_ID, OBRA_ID, List.of(event));

        verify(memoryService).registrarEventoDetalhado(
                eq("b2f2e604-8b13-48ad-98d0-dc345384d4a8"),
                eq("RDO"),
                eq(RDO_ID),
                eq("RDO_EDITADO"),
                eq("RDO_SYNC_CLIENTE"),
                eq(OBRA_ID),
                eq(RDO_ID),
                isNull(),
                anyList(),
                eq("OFFLINE"),
                eq("SYNCED"),
                isNull(),
                any(LocalDateTime.class),
                eq(1),
                any()
        );
    }
}
