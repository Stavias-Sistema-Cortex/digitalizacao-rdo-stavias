package com.projeto.cortex.colaboradores;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.projeto.cortex.auth.identity.AuthIdentityRepository;
import com.projeto.cortex.integracoes.AcademySourceAdapter;
import com.projeto.cortex.memory.CortexOperationalMemoryService;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class ColaboradorImportServiceTest {

    @Test
    void neverPublishesCpfHashToOperationalEvidenceOrLegacySnapshot() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        AcademySourceAdapter academy = mock(AcademySourceAdapter.class);
        CortexOperationalMemoryService memory =
                mock(CortexOperationalMemoryService.class);
        AuthIdentityRepository authIdentities =
                mock(AuthIdentityRepository.class);

        when(academy.fetchUsers(anyInt())).thenReturn(List.of(
                new AcademySourceAdapter.UsuarioAcademyRecord(
                        900_000_001,
                        "111.444.777-35",
                        "Colaborador Sintético",
                        "colaborador@example.invalid",
                        true,
                        "grupo-teste",
                        "Operacional",
                        "perfil-teste",
                        "Administrador legado",
                        LocalDateTime.of(2026, 1, 1, 0, 0)
                )
        ));
        when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class)))
                .thenReturn(List.of());

        ColaboradorImportService service =
                new ColaboradorImportService(
                        jdbc,
                        academy,
                        memory,
                        authIdentities
                );

        ColaboradorImportResult result = service.importarUsuariosDaAcademy();

        assertThat(result.status()).isEqualTo("SUCCESS");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> evidenceFields =
                ArgumentCaptor.forClass(Map.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> legacySnapshot =
                ArgumentCaptor.forClass(Map.class);

        verify(memory).registrarEvidencias(
                eq("COLABORADOR"),
                anyString(),
                eq("IMPORTACAO_LEGADO"),
                evidenceFields.capture()
        );
        verify(memory).registrarMapeamentoLegado(
                eq("COLABORADOR"),
                anyString(),
                eq("dbstavias_acad"),
                eq("usuarios"),
                eq("900000001"),
                anyString(),
                anyString(),
                legacySnapshot.capture()
        );

        assertThat(evidenceFields.getValue())
                .containsKey("cpf_mascarado")
                .doesNotContainKeys("cpf_hash", "cpf_lookup_hmac");
        assertThat(legacySnapshot.getValue())
                .containsKey("cpf_mascarado")
                .doesNotContainKeys("cpf_hash", "cpf_lookup_hmac");
        verify(authIdentities).upsertAcademyIdentity(
                anyString(),
                eq("11144477735"),
                eq("colaborador@example.invalid")
        );
    }

    @Test
    void refusesInvalidAcademyCpfBeforeIdentityOrOperationalProjection() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        AcademySourceAdapter academy = mock(AcademySourceAdapter.class);
        CortexOperationalMemoryService memory =
                mock(CortexOperationalMemoryService.class);
        AuthIdentityRepository authIdentities =
                mock(AuthIdentityRepository.class);

        when(academy.fetchUsers(anyInt())).thenReturn(List.of(
                new AcademySourceAdapter.UsuarioAcademyRecord(
                        900_000_002,
                        "000.000.000-00",
                        "Colaborador Inválido Sintético",
                        "invalido@example.invalid",
                        true,
                        "grupo-teste",
                        "Operacional",
                        "perfil-teste",
                        "Operacional",
                        LocalDateTime.of(2026, 1, 1, 0, 0)
                )
        ));

        ColaboradorImportService service = new ColaboradorImportService(
                jdbc,
                academy,
                memory,
                authIdentities
        );

        assertThat(org.assertj.core.api.Assertions.catchThrowable(
                service::importarUsuariosDaAcademy
        )).isInstanceOf(RuntimeException.class)
                .hasMessage("Falha ao importar colaboradores da Academy.");

        verify(authIdentities, never()).upsertAcademyIdentity(
                anyString(),
                anyString(),
                anyString()
        );
        verify(memory, never()).registrarEvidencias(
                eq("COLABORADOR"),
                anyString(),
                anyString(),
                any(Map.class)
        );
    }
}
