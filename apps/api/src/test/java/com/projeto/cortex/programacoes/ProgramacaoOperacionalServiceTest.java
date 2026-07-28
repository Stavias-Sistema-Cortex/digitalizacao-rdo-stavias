package com.projeto.cortex.programacoes;

import com.projeto.cortex.obras.Obra;
import com.projeto.cortex.obras.ObraOperabilityGuard;
import com.projeto.cortex.obras.ObraRepository;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProgramacaoOperacionalServiceTest {

    private final ProgramacaoOperacionalRepository programacaoRepository =
            mock(ProgramacaoOperacionalRepository.class);
    private final ObraRepository obraRepository = mock(ObraRepository.class);
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final ObraOperabilityGuard operabilityGuard =
            mock(ObraOperabilityGuard.class);
    private final ProgramacaoOperacionalService service =
            new ProgramacaoOperacionalService(
                    programacaoRepository,
                    obraRepository,
                    jdbc,
                    operabilityGuard
            );

    @Test
    void createsProgramForWritableWorksite() {
        Obra obra = mock(Obra.class);
        when(obra.getId()).thenReturn("obra-1");
        when(obra.getCodigoContrato()).thenReturn("CTR-1");
        when(obra.getNome()).thenReturn("Obra 1");
        when(obraRepository.findById("obra-1"))
                .thenReturn(Optional.of(obra));
        when(programacaoRepository.save(any()))
                .thenAnswer(invocation -> invocation.getArgument(0));

        ProgramacaoOperacionalResponse response =
                service.criarProgramacao(validRequest());

        verify(operabilityGuard).requireWritable("obra-1");
        verify(programacaoRepository).save(any());
        assertThat(response.obraId()).isEqualTo("obra-1");
    }

    @Test
    void archivedWorksiteIsRejectedBeforeLoadingOrSavingProgram() {
        doThrow(new ResponseStatusException(HttpStatus.NOT_FOUND))
                .when(operabilityGuard)
                .requireWritable("obra-1");

        assertThatThrownBy(() -> service.criarProgramacao(validRequest()))
                .isInstanceOf(ResponseStatusException.class);

        verify(obraRepository, never()).findById(any());
        verify(programacaoRepository, never()).save(any());
    }

    private ProgramacaoOperacionalRequest validRequest() {
        return new ProgramacaoOperacionalRequest(
                "obra-1",
                LocalDate.of(2026, 7, 28),
                "Equipe A",
                null,
                null,
                null,
                null,
                null,
                "Pavimentação",
                null,
                null,
                "SP",
                "SP-270",
                null,
                null,
                null,
                "10",
                "11",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                "PLANEJADA",
                "MANUAL",
                null,
                null,
                null
        );
    }
}
