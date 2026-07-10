package com.projeto.cortex.obras;

import com.projeto.cortex.memory.CortexOperationalMemoryService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
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

        ArgumentCaptor<String> entidadeId =
                ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> obraId =
                ArgumentCaptor.forClass(String.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> payload =
                ArgumentCaptor.forClass(Map.class);
        verify(memory).registrarEventoDetalhado(
                isNull(),
                eq("OBRA"),
                entidadeId.capture(),
                eq("OBRA_ATUALIZADA"),
                eq("OBRAS"),
                obraId.capture(),
                isNull(),
                isNull(),
                anyList(),
                eq("ONLINE"),
                eq("SYNCED"),
                isNull(),
                any(LocalDateTime.class),
                eq(1),
                payload.capture()
        );

        assertEquals(entidadeId.getValue(), obraId.getValue());
        assertEquals("Obra Nova", payload.getValue().get("nome"));
        assertEquals("BR-262", payload.getValue().get("rodovia"));
        assertFalse(payload.getValue().containsKey("valorContratual"));
    }

    @Test
    void criarObraRegistraObjetoNaOntologia() {
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

        verify(memory).registrarObjeto(
                eq("OBRA"),
                any(String.class),
                eq("CT-1"),
                eq("Obra Nova"),
                eq("ATIVA"),
                eq("OBRAS")
        );

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> campos =
                ArgumentCaptor.forClass(Map.class);
        verify(memory).registrarEvidencias(
                eq("OBRA"),
                any(String.class),
                eq("OBRAS"),
                campos.capture()
        );
        assertEquals("CT-1", campos.getValue().get("codigo_contrato"));
        assertEquals("Campo Grande", campos.getValue().get("cidade"));
        assertEquals("BR-262", campos.getValue().get("rodovia"));
        assertEquals("MS", campos.getValue().get("uf"));
    }
}
