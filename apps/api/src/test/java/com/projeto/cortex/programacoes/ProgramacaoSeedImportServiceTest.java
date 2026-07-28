package com.projeto.cortex.programacoes;

import com.projeto.cortex.obras.Obra;
import com.projeto.cortex.obras.ObraOperabilityGuard;
import com.projeto.cortex.obras.ObraRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProgramacaoSeedImportServiceTest {

    private final ProgramacaoOperacionalRepository programacaoRepository =
            mock(ProgramacaoOperacionalRepository.class);
    private final ObraRepository obraRepository = mock(ObraRepository.class);
    private final ObraOperabilityGuard operabilityGuard =
            mock(ObraOperabilityGuard.class);
    private final ProgramacaoSeedImportService service =
            new ProgramacaoSeedImportService(
                    programacaoRepository,
                    obraRepository,
                    operabilityGuard
            );

    @TempDir
    Path tempDir;

    @Test
    void identicalReplaySkipsGuardEvenAfterWorksiteArchive() throws Exception {
        Path seed = seedFile();
        mockWorksite();
        ProgramacaoOperacional existing = mock(ProgramacaoOperacional.class);
        when(existing.temMesmoHashOrigem(anyString())).thenReturn(true);
        when(programacaoRepository.findByChaveNegocio(anyString()))
                .thenReturn(Optional.of(existing));

        ProgramacaoSeedImportResult result = service.importar(seed);

        assertThat(result.registrosIgnorados()).isEqualTo(1);
        assertThat(result.registrosComErro()).isZero();
        verify(operabilityGuard, never()).requireWritable(anyString());
        verify(programacaoRepository, never()).save(any());
    }

    @Test
    void changedReplayForArchivedWorksiteIsRejectedWithoutUpdate()
            throws Exception {
        Path seed = seedFile();
        mockWorksite();
        ProgramacaoOperacional existing = mock(ProgramacaoOperacional.class);
        when(existing.temMesmoHashOrigem(anyString())).thenReturn(false);
        when(programacaoRepository.findByChaveNegocio(anyString()))
                .thenReturn(Optional.of(existing));
        rejectArchivedWorksite();

        ProgramacaoSeedImportResult result = service.importar(seed);

        assertThat(result.registrosAtualizados()).isZero();
        assertThat(result.registrosComErro()).isEqualTo(1);
        verify(existing, never()).atualizarDeSeed(
                any(), any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any()
        );
        verify(programacaoRepository, never()).save(any());
    }

    @Test
    void newSeedRowForArchivedWorksiteIsRejectedWithoutInsert()
            throws Exception {
        Path seed = seedFile();
        mockWorksite();
        when(programacaoRepository.findByChaveNegocio(anyString()))
                .thenReturn(Optional.empty());
        rejectArchivedWorksite();

        ProgramacaoSeedImportResult result = service.importar(seed);

        assertThat(result.registrosInseridos()).isZero();
        assertThat(result.registrosComErro()).isEqualTo(1);
        verify(programacaoRepository, never()).save(any());
    }

    @Test
    void writableIncludingInactiveWorksiteCanBeImported() throws Exception {
        Path seed = seedFile();
        mockWorksite();
        when(programacaoRepository.findByChaveNegocio(anyString()))
                .thenReturn(Optional.empty());

        ProgramacaoSeedImportResult result = service.importar(seed);

        assertThat(result.registrosInseridos()).isEqualTo(1);
        assertThat(result.registrosComErro()).isZero();
        verify(operabilityGuard).requireWritable("obra-1");
        verify(programacaoRepository).save(any());
    }

    private Path seedFile() throws Exception {
        Path seed = tempDir.resolve("programacoes.csv");
        Files.writeString(
                seed,
                """
                codigo_contrato,data_programacao,equipe,servico
                CTR-1,2026-07-28,Equipe A,Pavimentacao
                """
        );
        return seed;
    }

    private void mockWorksite() {
        Obra obra = mock(Obra.class);
        when(obra.getId()).thenReturn("obra-1");
        when(obra.getCodigoContrato()).thenReturn("CTR-1");
        when(obra.getNome()).thenReturn("Obra 1");
        when(obraRepository.findByCodigoContrato("CTR-1"))
                .thenReturn(Optional.of(obra));
    }

    private void rejectArchivedWorksite() {
        doThrow(new ResponseStatusException(HttpStatus.NOT_FOUND))
                .when(operabilityGuard)
                .requireWritable("obra-1");
    }
}
