package com.projeto.cortex.colaboradores;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.projeto.cortex.auth.identity.AuthIdentityRepository;
import com.projeto.cortex.integracoes.AcademySourceAdapter;
import com.projeto.cortex.integracoes.AcademyUserSnapshot;
import com.projeto.cortex.memory.CortexOperationalMemoryService;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

class ColaboradorImportServiceTest {

    private static final String FIRST_SYNTHETIC_CPF = "11144477735";
    private static final String SECOND_SYNTHETIC_CPF = "90000007935";

    @Test
    void neverPublishesCpfRepresentationsOrDigestsToOperationalMemory() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        AcademySourceAdapter academy = mock(AcademySourceAdapter.class);
        CortexOperationalMemoryService memory =
                mock(CortexOperationalMemoryService.class);
        AuthIdentityRepository authIdentities =
                mock(AuthIdentityRepository.class);

        when(academy.fetchCompleteSnapshot(anyInt())).thenReturn(
                AcademyUserSnapshot.complete(List.of(
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
        )));
        when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class)))
                .thenReturn(List.of());

        ColaboradorImportService service = service(
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
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> objectPayloads =
                ArgumentCaptor.forClass(Map.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> eventPayloads =
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
        verify(memory, times(2)).registrarObjeto(
                anyString(),
                anyString(),
                anyString(),
                anyString(),
                anyString(),
                anyString(),
                anyString(),
                objectPayloads.capture()
        );
        verify(memory, times(2)).registrarEvento(
                anyString(),
                anyString(),
                anyString(),
                anyString(),
                eventPayloads.capture()
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
        List<Object> memoryCallArgumentsAndPayloads = new ArrayList<>();
        for (var invocation : mockingDetails(memory).getInvocations()) {
            memoryCallArgumentsAndPayloads.addAll(
                    Arrays.asList(invocation.getArguments())
            );
        }
        assertThat(containsCpfRepresentation(
                memoryCallArgumentsAndPayloads
        ))
                .as("memory calls must not expose CPF representations")
                .isFalse();
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
        when(academy.fetchCompleteSnapshot(anyInt())).thenReturn(
                AcademyUserSnapshot.complete(List.of(first)),
                AcademyUserSnapshot.complete(List.of(second))
        );
        when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class)))
                .thenReturn(List.of());

        ColaboradorImportService service = service(
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

        when(academy.fetchCompleteSnapshot(anyInt())).thenReturn(
                AcademyUserSnapshot.complete(List.of(
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
        )));

        ColaboradorImportService service = service(
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

    @Test
    void importsInactiveAcademyUserWithoutCreatingAnAuthenticationIdentity() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        AcademySourceAdapter academy = mock(AcademySourceAdapter.class);
        CortexOperationalMemoryService memory =
                mock(CortexOperationalMemoryService.class);
        AuthIdentityRepository authIdentities =
                mock(AuthIdentityRepository.class);

        when(academy.fetchCompleteSnapshot(anyInt())).thenReturn(
                AcademyUserSnapshot.complete(List.of(
                new AcademySourceAdapter.UsuarioAcademyRecord(
                        900_000_004,
                        "529.982.247-25",
                        "Colaborador Inativo Sintético",
                        "inativo@example.invalid",
                        false,
                        "grupo-teste",
                        "Operacional",
                        "perfil-teste",
                        "Operacional",
                        LocalDateTime.of(2026, 1, 1, 0, 0)
                )
        )));

        ColaboradorImportService service = service(
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
    }

    @Test
    void rejectsDuplicateSourceIdsBeforeDomainWrites() {
        assertSnapshotRejectedBeforeDomainWrites(
                List.of(
                        academyUser(
                                900_000_011,
                                FIRST_SYNTHETIC_CPF,
                                "first@example.invalid",
                                true
                        ),
                        academyUser(
                                900_000_011,
                                SECOND_SYNTHETIC_CPF,
                                "second@example.invalid",
                                true
                        )
                ),
                "2 conflitos de id de origem"
        );
    }

    @Test
    void rejectsNonPositiveSourceIdsBeforeDomainWrites() {
        assertSnapshotRejectedBeforeDomainWrites(
                List.of(
                        academyUser(
                                0,
                                FIRST_SYNTHETIC_CPF,
                                "first@example.invalid",
                                true
                        ),
                        academyUser(
                                -1,
                                SECOND_SYNTHETIC_CPF,
                                "second@example.invalid",
                                true
                        )
                ),
                "2 conflitos de id de origem"
        );
    }

    @Test
    void rejectsDuplicateValidActiveCpfsBeforeDomainWrites() {
        assertSnapshotRejectedBeforeDomainWrites(
                List.of(
                        academyUser(
                                900_000_012,
                                "111.444.777-35",
                                "first@example.invalid",
                                true
                        ),
                        academyUser(
                                900_000_013,
                                FIRST_SYNTHETIC_CPF,
                                "second@example.invalid",
                                true
                        )
                ),
                "2 conflitos de CPF ativo"
        );
    }

    @Test
    void rejectsDuplicateNormalizedEligibleEmailsBeforeDomainWrites() {
        assertSnapshotRejectedBeforeDomainWrites(
                List.of(
                        academyUser(
                                900_000_014,
                                FIRST_SYNTHETIC_CPF,
                                " Duplicate@Example.Invalid ",
                                true
                        ),
                        academyUser(
                                900_000_015,
                                SECOND_SYNTHETIC_CPF,
                                "duplicate@example.invalid",
                                true
                        )
                ),
                "2 conflitos de email de autenticacao"
        );
    }

    @Test
    void rejectsIncompleteSnapshotBeforeDomainWrites() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        AcademySourceAdapter academy = mock(AcademySourceAdapter.class);
        CortexOperationalMemoryService memory =
                mock(CortexOperationalMemoryService.class);
        AuthIdentityRepository authIdentities =
                mock(AuthIdentityRepository.class);
        TransactionTemplate transactions = immediateTransactions();
        when(academy.fetchCompleteSnapshot(anyInt())).thenReturn(
                new AcademyUserSnapshot(List.of(), false)
        );

        ColaboradorImportService service = new ColaboradorImportService(
                jdbc,
                academy,
                memory,
                authIdentities,
                transactions,
                24
        );

        assertThatThrownBy(service::importarUsuariosDaAcademy)
                .isInstanceOf(RuntimeException.class)
                .hasMessage("Falha ao importar colaboradores da Academy.")
                .hasNoCause();

        verify(transactions, never()).execute(any());
        verify(authIdentities, never()).upsertAcademyIdentity(
                anyString(),
                anyString(),
                anyString()
        );
    }

    @Test
    void sourceFailureMarksRunFailedWithoutStartingDomainTransaction() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        AcademySourceAdapter academy = mock(AcademySourceAdapter.class);
        CortexOperationalMemoryService memory =
                mock(CortexOperationalMemoryService.class);
        AuthIdentityRepository authIdentities =
                mock(AuthIdentityRepository.class);
        TransactionTemplate transactions = immediateTransactions();
        when(academy.fetchCompleteSnapshot(anyInt())).thenThrow(
                new IllegalStateException(
                        "driver detail: " + FIRST_SYNTHETIC_CPF
                )
        );

        ColaboradorImportService service = new ColaboradorImportService(
                jdbc,
                academy,
                memory,
                authIdentities,
                transactions,
                24
        );

        assertThatThrownBy(service::importarUsuariosDaAcademy)
                .isInstanceOf(RuntimeException.class)
                .hasMessage("Falha ao importar colaboradores da Academy.")
                .hasNoCause();

        verify(transactions, never()).execute(any());
        verify(authIdentities, never()).upsertAcademyIdentity(
                anyString(),
                anyString(),
                anyString()
        );
        assertThat(mockingDetails(memory).getInvocations())
                .allSatisfy(invocation ->
                        assertThat(Arrays.asList(invocation.getArguments()))
                                .doesNotContain(FIRST_SYNTHETIC_CPF)
                );
    }

    private void assertSnapshotRejectedBeforeDomainWrites(
            List<AcademySourceAdapter.UsuarioAcademyRecord> users,
            String safeConflictMessage
    ) {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        AcademySourceAdapter academy = mock(AcademySourceAdapter.class);
        CortexOperationalMemoryService memory =
                mock(CortexOperationalMemoryService.class);
        AuthIdentityRepository authIdentities =
                mock(AuthIdentityRepository.class);
        TransactionTemplate transactions = immediateTransactions();
        when(academy.fetchCompleteSnapshot(anyInt())).thenReturn(
                AcademyUserSnapshot.complete(users)
        );

        ColaboradorImportService service = new ColaboradorImportService(
                jdbc,
                academy,
                memory,
                authIdentities,
                transactions,
                24
        );

        assertThatThrownBy(service::importarUsuariosDaAcademy)
                .isInstanceOf(RuntimeException.class)
                .hasMessage("Falha ao importar colaboradores da Academy.")
                .hasNoCause();

        verify(transactions, never()).execute(any());
        verify(authIdentities, never()).upsertAcademyIdentity(
                anyString(),
                anyString(),
                anyString()
        );
        assertThat(mockingDetails(jdbc).getInvocations())
                .noneMatch(invocation -> {
                    Object[] arguments = invocation.getArguments();
                    return arguments.length > 0
                            && arguments[0] instanceof String sql
                            && sql.contains("INTO colaborador");
                });

        List<Object> persistedArguments = new ArrayList<>();
        for (var invocation : mockingDetails(jdbc).getInvocations()) {
            persistedArguments.addAll(Arrays.asList(invocation.getArguments()));
        }
        assertThat(persistedArguments.toString())
                .contains(safeConflictMessage)
                .doesNotContain(FIRST_SYNTHETIC_CPF)
                .doesNotContain(SECOND_SYNTHETIC_CPF)
                .doesNotContain("Duplicate@Example.Invalid")
                .doesNotContain("duplicate@example.invalid");
    }

    private ColaboradorImportService service(
            JdbcTemplate jdbc,
            AcademySourceAdapter academy,
            CortexOperationalMemoryService memory,
            AuthIdentityRepository authIdentities
    ) {
        return new ColaboradorImportService(
                jdbc,
                academy,
                memory,
                authIdentities,
                immediateTransactions(),
                24
        );
    }

    private TransactionTemplate immediateTransactions() {
        TransactionTemplate transactions = mock(TransactionTemplate.class);
        doAnswer(invocation -> invocation
                .<TransactionCallback<?>>getArgument(0)
                .doInTransaction(null))
                .when(transactions).execute(any());
        return transactions;
    }

    private AcademySourceAdapter.UsuarioAcademyRecord academyUser(String cpf) {
        return academyUser(
                900_000_003,
                cpf,
                "colaborador@example.invalid",
                true
        );
    }

    private AcademySourceAdapter.UsuarioAcademyRecord academyUser(
            int id,
            String cpf,
            String email,
            boolean active
    ) {
        return new AcademySourceAdapter.UsuarioAcademyRecord(
                id,
                cpf,
                "Colaborador Sintético",
                email,
                active,
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

    private boolean containsCpfRepresentation(Object value) {
        if (value instanceof String text) {
            return text.contains("111.444.777-35")
                    || text.contains(FIRST_SYNTHETIC_CPF);
        }
        if (value instanceof Map<?, ?> map) {
            return map.entrySet().stream().anyMatch(entry ->
                    containsCpfRepresentation(entry.getKey())
                            || containsCpfRepresentation(entry.getValue())
            );
        }
        if (value instanceof Iterable<?> values) {
            for (Object item : values) {
                if (containsCpfRepresentation(item)) {
                    return true;
                }
            }
        }
        return false;
    }
}
