package com.projeto.cortex.auth.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.projeto.cortex.colaboradores.CpfHasher;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
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

        AuthIdentity identity = repository
                .findActiveByCpf(SYNTHETIC_CPF)
                .orElseThrow();

        assertThat(identity.emailAutenticacao())
                .isEqualTo("alfa@example.invalid");
        verify(jdbc).update(
                contains("INSERT INTO auth_identity"),
                eq("alfa-sintetico"),
                eq(CURRENT.value()),
                eq(CURRENT.keyId())
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

        AuthIdentity identity = repository
                .findActiveByCpf(SYNTHETIC_CPF)
                .orElseThrow();

        assertThat(identity.colaboradorId()).isEqualTo("alfa-sintetico");
        verify(jdbc).update(
                contains("INSERT INTO auth_identity"),
                eq("alfa-sintetico"),
                eq(CURRENT.value()),
                eq(CURRENT.keyId())
        );
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

        repository.upsertAcademyIdentity(
                "alfa-sintetico",
                SYNTHETIC_CPF,
                "academy@example.invalid"
        );

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).update(
                sql.capture(),
                eq("alfa-sintetico"),
                eq(CURRENT.value()),
                eq(CURRENT.keyId()),
                eq("academy@example.invalid")
        );
        assertThat(sql.getValue())
                .contains("email_verificado_em IS NOT NULL")
                .contains("email_fonte = 'MANUAL_VERIFICADO'")
                .doesNotContain("cpf_hash");
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
