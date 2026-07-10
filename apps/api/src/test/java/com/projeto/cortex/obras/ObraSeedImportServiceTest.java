package com.projeto.cortex.obras;

import com.projeto.cortex.memory.CortexOperationalMemoryService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ObraSeedImportServiceTest {

    @Test
    void importarRegistraObjetoEEventoNaOntologia(@TempDir Path tempDir) throws Exception {
        ObraRepository repository = mock(ObraRepository.class);
        CortexOperationalMemoryService memory =
                mock(CortexOperationalMemoryService.class);
        when(repository.existsByCodigoContrato("CW1234")).thenReturn(false);
        when(repository.save(any(Obra.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        Path seed = tempDir.resolve("obras_seed.csv");
        Files.write(
                seed,
                "codigo_contrato,nome,cidade,uf\nCW1234,Obra Seed,Campo Grande,MS\n"
                        .getBytes(StandardCharsets.UTF_8)
        );

        ObraSeedImportService service =
                new ObraSeedImportService(repository, memory);
        ObraSeedImportResult resultado = service.importar(seed);

        assertEquals(1, resultado.registrosInseridos());

        // O objeto precisa entrar em cortex_objeto para a StavIA enxergar a obra
        // no grafo da ontologia (JdbcOntologyReader parte de nós tipo OBRA).
        verify(memory).registrarObjeto(
                eq("OBRA"),
                any(String.class),
                eq("CW1234"),
                eq("Obra Seed"),
                eq("ATIVA"),
                eq("OBRAS")
        );
        verify(memory).registrarEvidencias(
                eq("OBRA"),
                any(String.class),
                eq("OBRAS"),
                any()
        );
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
        assertEquals("Obra Seed", payload.getValue().get("nome"));
    }
}
