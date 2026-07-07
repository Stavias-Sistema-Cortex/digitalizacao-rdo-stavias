package com.projeto.cortex.obras;

import com.projeto.cortex.memory.CortexOperationalMemoryService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ObraServiceTest {

    @Test
    void criarObraEmiteEventoDeSincronizacao() {
        ObraRepository repository = mock(ObraRepository.class);
        CortexOperationalMemoryService memory =
                mock(CortexOperationalMemoryService.class);
        when(repository.existsByCodigoContrato("CT-1")).thenReturn(false);
        when(repository.save(any(Obra.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        ObraService service = new ObraService(repository, memory);
        service.criarObra(new ObraRequest(
                "CT-1", null, "Obra Nova", "DNIT", null,
                "Campo Grande", "MS", "BR-262",
                null, null, null, "Obs"
        ));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> payload =
                ArgumentCaptor.forClass(Map.class);
        verify(memory).registrarEvento(
                eq("OBRA"),
                any(String.class),
                eq("OBRA_ATUALIZADA"),
                eq("OBRAS"),
                payload.capture()
        );

        assertEquals("Obra Nova", payload.getValue().get("nome"));
        assertEquals("BR-262", payload.getValue().get("rodovia"));
        assertFalse(payload.getValue().containsKey("valorContratual"));
    }
}
