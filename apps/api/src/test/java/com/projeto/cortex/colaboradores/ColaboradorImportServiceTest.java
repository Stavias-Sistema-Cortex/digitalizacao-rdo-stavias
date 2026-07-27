package com.projeto.cortex.colaboradores;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
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

    private static final String FIRST_SYNTHETIC_CPF = "11144477735";
    private static final String SECOND_SYNTHETIC_CPF = "90000007935";

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
                .doesNotContainKeys("cpf_hash", "cpf_lookup_hmac")
                .doesNotContainValue(
                        CpfHasher.hashDeDigitos(FIRST_SYNTHETIC_CPF)
                );
        assertThat(legacySnapshot.getValue())
                .containsKey("cpf_mascarado")
                .doesNotContainKeys("cpf_hash", "cpf_lookup_hmac")
                .doesNotContainValue(
                        CpfHasher.hashDeDigitos(FIRST_SYNTHETIC_CPF)
                );
        verify(authIdentities).upsertAcademyIdentity(
                anyString(),
                eq("11144477735"),
                eq("colaborador@example.invalid")
        );
    }

    @Test
    void sourceProjectionDoesNotChangeWhenOnlySyntheticCpfChanges() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        AcademySourceAdapter academy = mock(AcademySourceAdapter.class);
        CortexOperationalMemoryService memory =
                mock(CortexOperationalMemoryService.class);
        AuthIdentityRepository authIdentities =
                mock(AuthIdentityRepository.class);

        AcademySourceAdapter.UsuarioAcademyRecord first = academyUser(
                FIRST_SYNTHETIC_CPF
        );
        AcademySourceAdapter.UsuarioAcademyRecord second = academyUser(
                SECOND_SYNTHETIC_CPF
        );
        when(academy.fetchUsers(anyInt()))
                .thenReturn(List.of(first), List.of(second));
        when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class)))
                .thenReturn(List.of());

        ColaboradorImportService service = new ColaboradorImportService(
                jdbc,
                academy,
                memory,
                authIdentities
        );

        service.importarUsuariosDaAcademy();
        service.importarUsuariosDaAcademy();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> evidenceFields =
                ArgumentCaptor.forClass(Map.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> legacySnapshots =
                ArgumentCaptor.forClass(Map.class);

        verify(memory, times(2)).registrarEvidencias(
                eq("COLABORADOR"),
                anyString(),
                eq("IMPORTACAO_LEGADO"),
                evidenceFields.capture()
        );
        verify(memory, times(2)).registrarMapeamentoLegado(
                eq("COLABORADOR"),
                anyString(),
                eq("dbstavias_acad"),
                eq("usuarios"),
                eq("900000003"),
                anyString(),
                anyString(),
                legacySnapshots.capture()
        );

        assertSourceProjectionIgnoresCpfDigests(evidenceFields.getAllValues());
        assertSourceProjectionIgnoresCpfDigests(legacySnapshots.getAllValues());
    }

    @Test
    void importsInvalidAcademyCpfWithoutCreatingAnAuthenticationIdentity() {
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

        ColaboradorImportResult result =
                service.importarUsuariosDaAcademy();

        assertThat(result.status()).isEqualTo("SUCCESS");
        assertThat(result.registrosLidos()).isEqualTo(1);
        assertThat(result.registrosProcessados()).isEqualTo(1);

        verify(authIdentities, never()).upsertAcademyIdentity(
                anyString(),
                anyString(),
                anyString()
        );
        verify(memory).registrarEvidencias(
                eq("COLABORADOR"),
                anyString(),
                eq("IMPORTACAO_LEGADO"),
                any(Map.class)
        );
    }

    private AcademySourceAdapter.UsuarioAcademyRecord academyUser(String cpf) {
        return new AcademySourceAdapter.UsuarioAcademyRecord(
                900_000_003,
                cpf,
                "Colaborador Sintético",
                "colaborador@example.invalid",
                true,
                "grupo-teste",
                "Operacional",
                "perfil-teste",
                "Administrador legado",
                LocalDateTime.of(2026, 1, 1, 0, 0)
        );
    }

    private void assertSourceProjectionIgnoresCpfDigests(
            List<Map<String, Object>> projections
    ) {
        assertThat(projections).hasSize(2);
        assertThat(projections.get(0).get("cpf_mascarado"))
                .isEqualTo(projections.get(1).get("cpf_mascarado"));
        assertThat(projections.get(0).get("source_hash"))
                .isEqualTo(projections.get(1).get("source_hash"));

        List<String> cpfDigests = List.of(
                CpfHasher.hashDeDigitos(FIRST_SYNTHETIC_CPF),
                CpfHasher.hashDeDigitos(SECOND_SYNTHETIC_CPF)
        );
        for (Map<String, Object> projection : projections) {
            assertThat(projection)
                    .doesNotContainKeys("cpf_hash", "cpf_lookup_hmac");
            assertThat(projection.values())
                    .doesNotContainAnyElementsOf(cpfDigests);
        }
    }
}
