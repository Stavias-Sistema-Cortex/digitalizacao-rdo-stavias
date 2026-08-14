package com.projeto.cortex.financeiro;

import com.projeto.cortex.memory.CortexOperationalMemoryService;
import com.projeto.cortex.obras.Obra;
import com.projeto.cortex.obras.ObraOperabilityGuard;
import com.projeto.cortex.obras.ObraRepository;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ItemContratualServiceTest {

    private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    private final ObraRepository obraRepository = mock(ObraRepository.class);
    private final CortexOperationalMemoryService memoryService =
            mock(CortexOperationalMemoryService.class);
    private final ObraOperabilityGuard obraOperabilityGuard =
            mock(ObraOperabilityGuard.class);
    private final ItemContratualService service = new ItemContratualService(
            jdbcTemplate,
            obraRepository,
            memoryService,
            obraOperabilityGuard
    );

    /**
     * O item contratual forma o teto do contrato — e o PDOR só recalcula
     * quando um evento vinculado à obra é publicado.
     *
     * <p>Registrar o objeto e a relação não acorda ninguém: nenhum dos dois
     * publica observação. O item entrava no banco e a projeção na tela
     * continuava sendo a do contrato anterior, sem prazo para sair de lá.
     */
    @Test
    void publishesAWorksiteBoundEventSoThePdorRecalculates() {
        Obra obra = obra("CW-001");
        ItemContratualRequest request = new ItemContratualRequest(
                "CT-001", "ITEM-001", "Pavimentação", "m2",
                new BigDecimal("10"), new BigDecimal("12.50"),
                null, null, 1, "ATIVO", "MANUAL"
        );
        when(obraRepository.findByIdentificador("CW-001")).thenReturn(List.of(obra));

        service.criar("CW-001", request);

        verify(memoryService).registrarEventoDetalhado(
                isNull(),
                eq("ITEM_CONTRATUAL"),
                anyString(),
                eq("ITEM_CONTRATUAL_REGISTRADO"),
                anyString(),
                eq(obra.getId()),
                isNull(),
                isNull(),
                any(),
                anyString(),
                anyString(),
                isNull(),
                any(),
                eq(1),
                any()
        );
    }

    @Test
    void archivedWorksiteBlocksContractItemBeforeInsertAndMemoryPublication() {
        Obra obra = obra("CW-001");
        ItemContratualRequest request = new ItemContratualRequest(
                "CT-001",
                "ITEM-001",
                "Pavimentação",
                "m2",
                new BigDecimal("10"),
                new BigDecimal("12.50"),
                null,
                null,
                1,
                "ATIVO",
                "MANUAL"
        );
        when(obraRepository.findByIdentificador("CW-001")).thenReturn(List.of(obra));
        doThrow(new ResponseStatusException(
                HttpStatus.NOT_FOUND,
                "Obra não encontrada ou arquivada."
        )).when(obraOperabilityGuard).requireWritable(obra.getId());

        assertThatThrownBy(() -> service.criar("CW-001", request))
                .isInstanceOfSatisfying(ResponseStatusException.class, exception -> {
                    assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
                    assertThat(exception.getReason()).isEqualTo("Obra não encontrada ou arquivada.");
                });

        verify(obraOperabilityGuard).requireWritable(obra.getId());
        verify(jdbcTemplate, never()).update(anyString(), any(Object[].class));
        verify(memoryService, never()).registrarObjeto(
                any(), any(), any(), any(), any(), any()
        );
    }

    @Test
    void historicalListingResolvesArchivedWorksiteWithoutWriteGuard() {
        Obra obra = obra("CW-001");
        obra.arquivar();
        when(obraRepository.findByIdentificador("CW-001")).thenReturn(List.of(obra));
        when(jdbcTemplate.query(
                anyString(),
                any(RowMapper.class),
                eq(obra.getId())
        )).thenReturn(List.of());
        doThrow(new ResponseStatusException(
                HttpStatus.NOT_FOUND,
                "Obra não encontrada ou arquivada."
        )).when(obraOperabilityGuard).requireWritable(obra.getId());

        assertThat(service.listar("CW-001")).isEmpty();

        verify(obraRepository).findByIdentificador("CW-001");
        verify(obraRepository, never()).findAtivasByIdentificador(anyString());
        verify(obraOperabilityGuard, never()).requireWritable(any());
    }

    private Obra obra(String identifier) {
        return Obra.criar(
                identifier,
                identifier,
                identifier,
                "Obra " + identifier,
                "Cliente",
                null,
                "São Paulo",
                "SP",
                "SP-000",
                "ATIVA",
                "TESTE",
                null,
                null
        );
    }
}
