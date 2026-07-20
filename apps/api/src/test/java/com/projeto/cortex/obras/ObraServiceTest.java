package com.projeto.cortex.obras;

import com.projeto.cortex.financeiro.unit.FinancialUnitService;
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
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
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
        FinancialUnitService financialUnits = mock(FinancialUnitService.class);
        when(repository.existsByCodigoContrato("CT-1")).thenReturn(false);
        when(repository.saveAndFlush(any(Obra.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        ObraService service = new ObraService(repository, memory, financialUnits);
        ObraResponse created = service.criarObra(new ObraRequest(
                "CT-1", null, "Obra Nova", "DNIT", null,
                "Campo Grande", "MS", "BR-262",
                null, null, null, "Obs"
        ), "alfa-1");

        verify(financialUnits).ensureWorksiteUnit(created.id(), "alfa-1");
        verify(memory).registrarRelacaoAtiva(
                "PESSOA",
                "alfa-1",
                "OBRA",
                created.id(),
                "CRIOU",
                "OBRAS",
                "Criação manual de obra autenticada."
        );

        ArgumentCaptor<String> entidadeId =
                ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> obraId =
                ArgumentCaptor.forClass(String.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> payload =
                ArgumentCaptor.forClass(Map.class);
        verify(memory).registrarEventoAuditado(
                isNull(),
                eq("OBRA"),
                entidadeId.capture(),
                eq("OBRA_ATUALIZADA"),
                eq("OBRAS"),
                obraId.capture(),
                isNull(),
                eq("alfa-1"),
                anyList(),
                eq("ONLINE"),
                eq("SYNCED"),
                any(LocalDateTime.class),
                any(LocalDateTime.class),
                eq(1),
                payload.capture(),
                eq("alfa-1"),
                isNull(),
                anyString(),
                isNull(),
                anyMap(),
                anyMap(),
                eq("SUCESSO"),
                isNull()
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
        FinancialUnitService financialUnits = mock(FinancialUnitService.class);
        when(repository.existsByCodigoContrato("CT-1")).thenReturn(false);
        when(repository.saveAndFlush(any(Obra.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        ObraService service = new ObraService(repository, memory, financialUnits);
        service.criarObra(new ObraRequest(
                "CT-1", null, "Obra Nova", "DNIT", null,
                "Campo Grande", "MS", "BR-262",
                null, null, null, "Obs"
        ), "alfa-1");

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
