package com.projeto.cortex.obras;

import com.projeto.cortex.financeiro.unit.FinancialUnitService;
import com.projeto.cortex.memory.CortexOperationalMemoryService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ObraServiceTest {

    @Test
    void novaObraComecaNaVersaoUm() {
        Obra obra = novaObra("ATIVA");

        assertEquals(1L, obra.getVersaoLinha());
    }

    @Test
    void edicaoAtualizaCadastroPreservaStatusEIncrementaVersao() {
        Obra obra = novaObra("ATIVA");

        obra.editar(
                "CW-2",
                "CW2",
                "INT-2",
                "Obra Editada",
                "Cliente Editado",
                "Descrição editada",
                "Dourados",
                "MS",
                "BR-163",
                "origem.xlsx",
                "Observação editada"
        );

        assertEquals("CW-2", obra.getCodigoContrato());
        assertEquals("CW2", obra.getCodigoCw());
        assertEquals("INT-2", obra.getCodigoInterno());
        assertEquals("Obra Editada", obra.getNome());
        assertEquals("Cliente Editado", obra.getCliente());
        assertEquals("Descrição editada", obra.getDescricao());
        assertEquals("Dourados", obra.getCidade());
        assertEquals("MS", obra.getUf());
        assertEquals("BR-163", obra.getRodovia());
        assertEquals("origem.xlsx", obra.getFonteArquivo());
        assertEquals("Observação editada", obra.getObservacoes());
        assertEquals("ATIVA", obra.getStatus());
        assertEquals("MANUAL", obra.getFonteCriacao());
        assertEquals(2L, obra.getVersaoLinha());
    }

    @Test
    void desativacaoDefineStatusInativaEIncrementaVersao() {
        Obra obra = novaObra("ATIVA");

        obra.desativar();

        assertEquals("INATIVA", obra.getStatus());
        assertEquals(2L, obra.getVersaoLinha());
    }

    @Test
    void arquivamentoPreservaStatusEIncrementaVersao() {
        Obra obra = novaObra("INATIVA");

        obra.arquivar();

        assertEquals("INATIVA", obra.getStatus());
        assertNotNull(obra.getArquivadoEm());
        assertEquals(2L, obra.getVersaoLinha());
    }

    @Test
    void restauracaoLimpaArquivamentoPreservaStatusEIncrementaVersao() {
        Obra obra = novaObra("INATIVA");
        obra.arquivar();

        obra.restaurar();

        assertEquals("INATIVA", obra.getStatus());
        assertNull(obra.getArquivadoEm());
        assertEquals(3L, obra.getVersaoLinha());
    }

    @Test
    void obraArquivadaRejeitaEdicao() {
        Obra obra = novaObra("ATIVA");
        obra.arquivar();

        IllegalStateException error = assertThrows(
                IllegalStateException.class,
                () -> obra.editar(
                        "CT-2", null, null, "Outra", null, null,
                        null, null, null, null, null
                )
        );

        assertTrue(error.getMessage().contains("arquivada"));
        assertEquals(2L, obra.getVersaoLinha());
    }

    @Test
    void obraArquivadaRejeitaDesativacao() {
        Obra obra = novaObra("ATIVA");
        obra.arquivar();

        IllegalStateException error = assertThrows(
                IllegalStateException.class,
                obra::desativar
        );

        assertTrue(error.getMessage().contains("arquivada"));
        assertEquals(2L, obra.getVersaoLinha());
    }

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

        assertEquals(1L, created.versaoLinha());
        assertNull(created.arquivadoEm());
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
        assertEquals("alfa-1", payload.getValue().get("actorId"));
        assertEquals(1L, payload.getValue().get("versaoLinha"));
        assertNull(payload.getValue().get("arquivadoEm"));
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

    @Test
    void quatroMutacoesEmitemUmEventoAuditadoCadaComAtorVersaoEEstadoSeguro() {
        ObraRepository repository = mock(ObraRepository.class);
        CortexOperationalMemoryService memory =
                mock(CortexOperationalMemoryService.class);
        FinancialUnitService financialUnits = mock(FinancialUnitService.class);
        Obra obra = novaObra("ATIVA");
        when(repository.findByIdForUpdate(obra.getId()))
                .thenReturn(Optional.of(obra));
        when(repository.saveAndFlush(any(Obra.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        ObraService service = new ObraService(repository, memory, financialUnits);
        service.atualizarObra(obra.getId(), new ObraUpdateRequest(
                "CW-2", "INT-2", "Obra Editada", "Cliente", "Descrição",
                "Dourados", "MS", "BR-163", "origem.xlsx", "Obs", 1L
        ), "alfa-1");
        service.desativarObra(
                obra.getId(), new ObraVersionRequest(2L), "alfa-1"
        );
        service.arquivarObra(
                obra.getId(), new ObraVersionRequest(3L), "alfa-1"
        );
        service.restaurarObra(
                obra.getId(), new ObraVersionRequest(4L), "alfa-1"
        );

        ArgumentCaptor<String> tipoEvento = ArgumentCaptor.forClass(String.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> payload =
                ArgumentCaptor.forClass(Map.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> estadoAnterior =
                ArgumentCaptor.forClass(Map.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> estadoNovo =
                ArgumentCaptor.forClass(Map.class);
        verify(memory, times(4)).registrarEventoAuditado(
                isNull(),
                eq("OBRA"),
                eq(obra.getId()),
                tipoEvento.capture(),
                eq("OBRAS"),
                eq(obra.getId()),
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
                estadoAnterior.capture(),
                estadoNovo.capture(),
                eq("SUCESSO"),
                isNull()
        );

        assertEquals(List.of(
                "OBRA_ATUALIZADA",
                "OBRA_DESATIVADA",
                "OBRA_ARQUIVADA",
                "OBRA_RESTAURADA"
        ), tipoEvento.getAllValues());
        assertEquals(List.of(2L, 3L, 4L, 5L), payload.getAllValues().stream()
                .map(event -> event.get("versaoLinha"))
                .toList());
        assertTrue(payload.getAllValues().stream()
                .allMatch(event -> "alfa-1".equals(event.get("actorId"))));
        assertNotNull(payload.getAllValues().get(2).get("arquivadoEm"));
        assertNull(payload.getAllValues().get(3).get("arquivadoEm"));
        assertEquals("ATIVA", estadoAnterior.getAllValues().get(0).get("status"));
        assertEquals("Obra Editada", estadoNovo.getAllValues().get(0).get("nome"));
        assertTrue(payload.getAllValues().stream().noneMatch(event ->
                event.containsKey("cpf")
                        || event.containsKey("hmac")
                        || event.containsKey("credencial")
                        || event.containsKey("segredo")
        ));
    }

    @Test
    void versaoDivergenteRetornaConflitoSemPersistirNemAuditar() {
        ObraRepository repository = mock(ObraRepository.class);
        CortexOperationalMemoryService memory =
                mock(CortexOperationalMemoryService.class);
        FinancialUnitService financialUnits = mock(FinancialUnitService.class);
        Obra obra = novaObra("ATIVA");
        when(repository.findByIdForUpdate(obra.getId()))
                .thenReturn(Optional.of(obra));

        ObraService service = new ObraService(repository, memory, financialUnits);
        org.springframework.web.server.ResponseStatusException error = assertThrows(
                org.springframework.web.server.ResponseStatusException.class,
                () -> service.arquivarObra(
                        obra.getId(), new ObraVersionRequest(0L), "alfa-1"
                )
        );

        assertEquals(org.springframework.http.HttpStatus.CONFLICT, error.getStatusCode());
        verify(repository, never()).saveAndFlush(any(Obra.class));
        verify(memory, never()).registrarEventoAuditado(
                any(), any(), any(), any(), any(), any(), any(), any(),
                anyList(), any(), any(), any(), any(), any(), anyMap(),
                any(), any(), any(), any(), anyMap(), anyMap(), any(), any()
        );
    }

    private Obra novaObra(String status) {
        return Obra.criar(
                "CT-1",
                null,
                "INT-1",
                "Obra Nova",
                "DNIT",
                "Descrição",
                "Campo Grande",
                "MS",
                "BR-262",
                status,
                "MANUAL",
                null,
                "Obs"
        );
    }
}
