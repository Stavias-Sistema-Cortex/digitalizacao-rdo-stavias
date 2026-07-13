package com.projeto.cortex.auth.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.when;

import com.projeto.cortex.colaboradores.CpfHasher;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class AuthIdentityRepositoryTest {

    private static final String SYNTHETIC_CPF = "11144477735";
    private static final CpfLookupDigest CURRENT =
            new CpfLookupDigest("k2026-07", "a".repeat(64));
    private static final CpfLookupDigest PREVIOUS =
            new CpfLookupDigest("k2026-06", "b".repeat(64));

    private JdbcTemplate jdbc;
    private CpfLookupDigestService digests;
    private AuthIdentityRepository repository;

    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcTemplate.class);
        digests = mock(CpfLookupDigestService.class);
        repository = new AuthIdentityRepository(jdbc, digests);
    }

    @Test
    @SuppressWarnings("unchecked")
    void returnsCurrentHmacIdentityWithoutAWriteOnLookup() {
        when(digests.candidates(SYNTHETIC_CPF)).thenReturn(List.of(CURRENT));
        when(jdbc.query(
                anyString(),
                any(RowMapper.class),
                eq(CURRENT.keyId()),
                eq(CURRENT.value())
        )).thenReturn(List.of(activeIdentity()));
        stubLegacyOwners(List.of(activeIdentity()));
        stubEligible("alfa-sintetico", activeIdentity());

        AuthIdentity identity = repository
                .findActiveByCpf(SYNTHETIC_CPF)
                .orElseThrow();

        assertThat(identity.colaboradorId()).isEqualTo("alfa-sintetico");
        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void locatesPreviousKeyIdentityAndUpgradesToCurrentHmac() {
        when(digests.candidates(SYNTHETIC_CPF))
                .thenReturn(List.of(CURRENT, PREVIOUS));
        when(jdbc.query(
                anyString(),
                any(RowMapper.class),
                eq(CURRENT.keyId()),
                eq(CURRENT.value())
        )).thenReturn(List.of());
        when(jdbc.query(
                anyString(),
                any(RowMapper.class),
                eq(PREVIOUS.keyId()),
                eq(PREVIOUS.value())
        )).thenReturn(List.of(activeIdentity()));
        stubEligible("alfa-sintetico", activeIdentity());
        stubDigestOwnerForUpdate(CURRENT, List.of());
        stubCollaboratorIdentityForUpdate("alfa-sintetico", true);

        AuthIdentity identity = repository
                .findActiveByCpf(SYNTHETIC_CPF)
                .orElseThrow();

        assertThat(identity.emailAutenticacao())
                .isEqualTo("alfa@example.invalid");
        verify(jdbc).update(
                contains("UPDATE auth_identity"),
                eq(CURRENT.value()),
                eq(CURRENT.keyId()),
                eq("alfa-sintetico")
        );
    }

    @Test
    @SuppressWarnings("unchecked")
    void locatesLegacyIdentityWithoutAuthenticatingAndUpgradesToCurrentHmac() {
        when(digests.candidates(SYNTHETIC_CPF)).thenReturn(List.of(CURRENT));
        when(jdbc.query(
                anyString(),
                any(RowMapper.class),
                eq(CURRENT.keyId()),
                eq(CURRENT.value())
        )).thenReturn(List.of());
        when(jdbc.query(
                anyString(),
                any(RowMapper.class),
                eq(CpfHasher.hashDeDigitos(SYNTHETIC_CPF))
        )).thenReturn(List.of(activeIdentity()));
        stubEligible("alfa-sintetico", activeIdentity());
        stubDigestOwnerForUpdate(CURRENT, List.of());
        stubCollaboratorIdentityForUpdate("alfa-sintetico", true);

        AuthIdentity identity = repository
                .findActiveByCpf(SYNTHETIC_CPF)
                .orElseThrow();

        assertThat(identity.colaboradorId()).isEqualTo("alfa-sintetico");
        verify(jdbc).update(
                contains("UPDATE auth_identity"),
                eq(CURRENT.value()),
                eq(CURRENT.keyId()),
                eq("alfa-sintetico")
        );
    }

    @Test
    @SuppressWarnings("unchecked")
    void checksEveryConfiguredDigestAndFailsForDifferentOwners() {
        AuthIdentity previousOwner = new AuthIdentity(
                "beta-sintetico",
                "Colaborador BETA Sintético",
                "beta@example.invalid",
                "BETA"
        );
        when(digests.candidates(SYNTHETIC_CPF))
                .thenReturn(List.of(CURRENT, PREVIOUS));
        when(jdbc.query(
                anyString(),
                any(RowMapper.class),
                eq(CURRENT.keyId()),
                eq(CURRENT.value())
        )).thenReturn(List.of(activeIdentity()));
        when(jdbc.query(
                anyString(),
                any(RowMapper.class),
                eq(PREVIOUS.keyId()),
                eq(PREVIOUS.value())
        )).thenReturn(List.of(previousOwner));

        assertThatThrownBy(() -> repository.findActiveByCpf(SYNTHETIC_CPF))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Identidade de autenticação ambígua.");
        verify(jdbc).query(
                anyString(),
                any(RowMapper.class),
                eq(PREVIOUS.keyId()),
                eq(PREVIOUS.value())
        );
        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void currentHmacAndLegacyDifferentOwnersAreGloballyAmbiguous() {
        AuthIdentity legacyOwner = new AuthIdentity(
                "bloqueado-sintetico",
                "Colaborador Bloqueado Sintético",
                "bloqueado@example.invalid",
                "BETA"
        );
        when(digests.candidates(SYNTHETIC_CPF)).thenReturn(List.of(CURRENT));
        when(jdbc.query(
                anyString(),
                any(RowMapper.class),
                eq(CURRENT.keyId()),
                eq(CURRENT.value())
        )).thenReturn(List.of(activeIdentity()));
        stubLegacyOwners(List.of(legacyOwner));

        assertThatThrownBy(() -> repository.findActiveByCpf(SYNTHETIC_CPF))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Identidade de autenticação ambígua.");

        verify(jdbc).query(
                anyString(),
                any(RowMapper.class),
                eq(CpfHasher.hashDeDigitos(SYNTHETIC_CPF))
        );
        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void legacyAmbiguityIsDetectedBeforeEligibilityFiltering() {
        AuthIdentity conflictingOwner = new AuthIdentity(
                "bloqueado-sintetico",
                "Colaborador Bloqueado Sintético",
                "bloqueado@example.invalid",
                "BETA"
        );
        when(digests.candidates(SYNTHETIC_CPF)).thenReturn(List.of(CURRENT));
        when(jdbc.query(
                anyString(),
                any(RowMapper.class),
                eq(CURRENT.keyId()),
                eq(CURRENT.value())
        )).thenReturn(List.of());
        when(jdbc.query(
                anyString(),
                any(RowMapper.class),
                eq(CpfHasher.hashDeDigitos(SYNTHETIC_CPF))
        )).thenReturn(List.of(activeIdentity(), conflictingOwner));

        assertThatThrownBy(() -> repository.findActiveByCpf(SYNTHETIC_CPF))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Identidade de autenticação ambígua.");

        ArgumentCaptor<String> legacySql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).query(
                legacySql.capture(),
                any(RowMapper.class),
                eq(CpfHasher.hashDeDigitos(SYNTHETIC_CPF))
        );
        assertThat(legacySql.getValue())
                .doesNotContain("colaborador.ativo = 1")
                .doesNotContain("colaborador.deletado_em IS NULL")
                .doesNotContain("BLOQUEADA");
    }

    @Test
    @SuppressWarnings("unchecked")
    void failsClosedForAmbiguousLegacyCpfMatches() {
        when(digests.candidates(SYNTHETIC_CPF)).thenReturn(List.of(CURRENT));
        when(jdbc.query(
                anyString(),
                any(RowMapper.class),
                eq(CURRENT.keyId()),
                eq(CURRENT.value())
        )).thenReturn(List.of());
        when(jdbc.query(
                anyString(),
                any(RowMapper.class),
                eq(CpfHasher.hashDeDigitos(SYNTHETIC_CPF))
        )).thenReturn(List.of(
                activeIdentity(),
                new AuthIdentity(
                        "beta-sintetico",
                        "Colaborador BETA Sintético",
                        "beta@example.invalid",
                        "BETA"
                )
        ));

        assertThatThrownBy(() -> repository.findActiveByCpf(SYNTHETIC_CPF))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Identidade de autenticação ambígua.");
        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }

    @Test
    void academyUpsertProtectsAnyVerifiedAuthenticationEmail() {
        when(digests.current(SYNTHETIC_CPF)).thenReturn(CURRENT);
        stubDigestOwnerForUpdate(CURRENT, List.of());
        stubCollaboratorIdentityForUpdate("alfa-sintetico", true);

        repository.upsertAcademyIdentity(
                "alfa-sintetico",
                SYNTHETIC_CPF,
                "academy@example.invalid"
        );

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).update(
                sql.capture(),
                eq(CURRENT.value()),
                eq(CURRENT.keyId()),
                eq("academy@example.invalid"),
                eq("academy@example.invalid"),
                eq("alfa-sintetico")
        );
        assertThat(sql.getValue())
                .startsWith("UPDATE auth_identity")
                .contains("email_verificado_em IS NOT NULL")
                .contains("'MANUAL_VERIFICADO'")
                .contains("'MANUAL_PENDENTE'")
                .contains("WHERE colaborador_id = ?")
                .doesNotContain("ON DUPLICATE KEY UPDATE")
                .doesNotContain("cpf_hash");
    }

    @Test
    void academyUpsertRefusesDigestOwnedByAnotherCollaborator() {
        when(digests.current(SYNTHETIC_CPF)).thenReturn(CURRENT);
        stubDigestOwnerForUpdate(CURRENT, List.of("outro-sintetico"));

        assertThatThrownBy(() -> repository.upsertAcademyIdentity(
                "alfa-sintetico",
                SYNTHETIC_CPF,
                "academy@example.invalid"
        )).isInstanceOf(IllegalStateException.class)
                .hasMessage("Conflito de identidade de autenticação.");

        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }

    @Test
    void academyInsertSanitizesConcurrentDigestConflict() {
        when(digests.current(SYNTHETIC_CPF)).thenReturn(CURRENT);
        stubDigestOwnerForUpdate(CURRENT, List.of());
        stubCollaboratorIdentityForUpdate("alfa-sintetico", false);
        when(jdbc.update(
                contains("INSERT INTO auth_identity"),
                eq("alfa-sintetico"),
                eq(CURRENT.value()),
                eq(CURRENT.keyId()),
                eq("academy@example.invalid")
        )).thenThrow(new DuplicateKeyException(
                "Duplicate entry with database details"
        ));

        assertThatThrownBy(() -> repository.upsertAcademyIdentity(
                "alfa-sintetico",
                SYNTHETIC_CPF,
                "academy@example.invalid"
        )).isInstanceOf(IllegalStateException.class)
                .hasMessage("Conflito de identidade de autenticação.")
                .hasMessageNotContaining("Duplicate entry")
                .hasNoCause();
    }

    @Test
    @SuppressWarnings("unchecked")
    void provisioningLocatesExistingActiveOwnerAndLeavesEmailPending() {
        when(digests.candidates(SYNTHETIC_CPF)).thenReturn(List.of(CURRENT));
        when(digests.current(SYNTHETIC_CPF)).thenReturn(CURRENT);
        when(jdbc.query(
                anyString(),
                any(RowMapper.class),
                eq(CURRENT.keyId()),
                eq(CURRENT.value())
        )).thenReturn(List.of(activeIdentity()));
        stubLegacyOwners(List.of(activeIdentity()));
        stubEligible("alfa-sintetico", activeIdentity());
        stubDigestOwnerForUpdate(CURRENT, List.of("alfa-sintetico"));
        stubCollaboratorIdentityForUpdate("alfa-sintetico", true);
        stubProvisioningEligible("alfa-sintetico", true);

        repository.upsertProvisionedIdentity(
                SYNTHETIC_CPF,
                "alfa@example.invalid",
                AuthIdentityRepository.MANUAL_PENDING_SOURCE
        );

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).update(
                sql.capture(),
                eq(CURRENT.value()),
                eq(CURRENT.keyId()),
                eq("alfa@example.invalid"),
                eq(AuthIdentityRepository.MANUAL_PENDING_SOURCE),
                eq("alfa-sintetico")
        );
        assertThat(sql.getValue())
                .startsWith("UPDATE auth_identity")
                .contains("email_verificado_em = NULL")
                .contains("status = 'PENDENTE'")
                .doesNotContain("papel_acesso")
                .doesNotContain("UPDATE colaborador")
                .doesNotContain("MANUAL_VERIFICADO");
    }

    @Test
    @SuppressWarnings("unchecked")
    void provisioningRevalidatesEligibilityUnderLockBeforeUpdate() {
        when(digests.candidates(SYNTHETIC_CPF)).thenReturn(List.of(CURRENT));
        when(digests.current(SYNTHETIC_CPF)).thenReturn(CURRENT);
        when(jdbc.query(
                anyString(),
                any(RowMapper.class),
                eq(CURRENT.keyId()),
                eq(CURRENT.value())
        )).thenReturn(List.of(activeIdentity()));
        stubLegacyOwners(List.of(activeIdentity()));
        stubEligible("alfa-sintetico", activeIdentity());
        stubDigestOwnerForUpdate(CURRENT, List.of("alfa-sintetico"));
        stubCollaboratorIdentityForUpdate("alfa-sintetico", true);
        stubProvisioningEligible("alfa-sintetico", false);

        assertThatThrownBy(() -> repository.upsertProvisionedIdentity(
                SYNTHETIC_CPF,
                "alfa@example.invalid",
                AuthIdentityRepository.MANUAL_PENDING_SOURCE
        )).isInstanceOf(IllegalStateException.class)
                .hasMessage("Identidade indisponível para provisionamento.");

        InOrder order = inOrder(jdbc);
        order.verify(jdbc).query(
                argThat(sql -> sql != null
                        && sql.contains("colaborador.ativo = 1")
                        && sql.contains("colaborador.deletado_em IS NULL")
                        && sql.contains("identity.status <> 'BLOQUEADA'")
                        && sql.contains("FOR UPDATE")),
                any(RowMapper.class),
                eq("alfa-sintetico")
        );
        verify(jdbc, never()).update(
                argThat(sql -> sql != null
                        && sql.startsWith("UPDATE auth_identity")),
                any(Object[].class)
        );
    }

    @Test
    @SuppressWarnings("unchecked")
    void provisioningRefusesUnknownIdentityWithoutWriting() {
        when(digests.candidates(SYNTHETIC_CPF)).thenReturn(List.of(CURRENT));
        when(jdbc.query(
                anyString(),
                any(RowMapper.class),
                eq(CURRENT.keyId()),
                eq(CURRENT.value())
        )).thenReturn(List.of());
        stubLegacyOwners(List.of());

        assertThatThrownBy(() -> repository.upsertProvisionedIdentity(
                SYNTHETIC_CPF,
                "alfa@example.invalid",
                AuthIdentityRepository.MANUAL_PENDING_SOURCE
        )).isInstanceOf(IllegalStateException.class)
                .hasMessage("Identidade indisponível para provisionamento.")
                .hasMessageNotContaining(SYNTHETIC_CPF)
                .hasMessageNotContaining("alfa@example.invalid");

        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }

    @SuppressWarnings("unchecked")
    private void stubEligible(String colaboradorId, AuthIdentity identity) {
        when(jdbc.query(
                argThat(sql -> sql != null
                        && sql.contains("WHERE colaborador.id = ?")),
                any(RowMapper.class),
                eq(colaboradorId)
        )).thenReturn(List.of(identity));
    }

    @SuppressWarnings("unchecked")
    private void stubLegacyOwners(List<AuthIdentity> owners) {
        when(jdbc.query(
                anyString(),
                any(RowMapper.class),
                eq(CpfHasher.hashDeDigitos(SYNTHETIC_CPF))
        )).thenReturn(owners);
    }

    @SuppressWarnings("unchecked")
    private void stubDigestOwnerForUpdate(
            CpfLookupDigest digest,
            List<String> owners
    ) {
        when(jdbc.query(
                argThat(sql -> sql != null
                        && sql.contains("cpf_lookup_key_id = ?")
                        && sql.contains("FOR UPDATE")),
                any(RowMapper.class),
                eq(digest.keyId()),
                eq(digest.value())
        )).thenReturn(owners);
    }

    @SuppressWarnings("unchecked")
    private void stubCollaboratorIdentityForUpdate(
            String colaboradorId,
            boolean exists
    ) {
        when(jdbc.query(
                argThat(sql -> sql != null
                        && sql.contains("WHERE colaborador_id = ?")
                        && sql.contains("FOR UPDATE")),
                any(RowMapper.class),
                eq(colaboradorId)
        )).thenReturn(exists ? List.of(colaboradorId) : List.of());
    }

    @SuppressWarnings("unchecked")
    private void stubProvisioningEligible(
            String colaboradorId,
            boolean eligible
    ) {
        when(jdbc.query(
                argThat(sql -> sql != null
                        && sql.contains("colaborador.ativo = 1")
                        && sql.contains("FOR UPDATE")),
                any(RowMapper.class),
                eq(colaboradorId)
        )).thenReturn(eligible ? List.of(colaboradorId) : List.of());
    }

    private AuthIdentity activeIdentity() {
        return new AuthIdentity(
                "alfa-sintetico",
                "Colaborador ALFA Sintético",
                "alfa@example.invalid",
                "ALFA"
        );
    }
}
