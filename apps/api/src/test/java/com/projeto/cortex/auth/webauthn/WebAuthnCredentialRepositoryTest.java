package com.projeto.cortex.auth.webauthn;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yubico.webauthn.RegisteredCredential;
import com.yubico.webauthn.data.ByteArray;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class WebAuthnCredentialRepositoryTest {

    private static final String COLLABORATOR_ID =
            "10000000-0000-0000-0000-000000000001";
    private static final String CHALLENGE_ID =
            "20000000-0000-0000-0000-000000000002";
    private static final String CLIENT_INSTANCE_HASH = "a".repeat(64);

    @Test
    void persistsOnlyChallengeHashAndUsesDatabaseClockForExpiry() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        WebAuthnCredentialRepository repository = repository(jdbc);
        ByteArray challenge = new ByteArray(new byte[]{1, 2, 3, 4});
        when(jdbc.update(
                anyString(),
                eq(CHALLENGE_ID),
                eq(COLLABORATOR_ID),
                eq("REGISTRATION"),
                anyString(),
                eq("{\"request\":true}"),
                eq(CLIENT_INSTANCE_HASH),
                eq(300)
        )).thenReturn(1);

        repository.createChallenge(
                CHALLENGE_ID,
                COLLABORATOR_ID,
                WebAuthnCeremony.REGISTRATION,
                challenge,
                "{\"request\":true}",
                CLIENT_INSTANCE_HASH,
                300
        );

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> hash = ArgumentCaptor.forClass(String.class);
        verify(jdbc).update(
                sql.capture(),
                eq(CHALLENGE_ID),
                eq(COLLABORATOR_ID),
                eq("REGISTRATION"),
                hash.capture(),
                eq("{\"request\":true}"),
                eq(CLIENT_INSTANCE_HASH),
                eq(300)
        );
        assertThat(compact(sql.getValue()))
                .contains("CURRENT_TIMESTAMP(6) + (? * INTERVAL '1 second')");
        assertThat(compact(sql.getValue()))
                .contains("client_instance_bound");
        assertThat(hash.getValue())
                .matches("[0-9a-f]{64}")
                .doesNotContain(challenge.getBase64Url());
    }

    @Test
    @SuppressWarnings("unchecked")
    void atomicallyConsumesOneLiveChallengeBeforeReturningIt() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        WebAuthnCredentialRepository repository = repository(jdbc);
        StoredWebAuthnChallenge stored = new StoredWebAuthnChallenge(
                CHALLENGE_ID,
                COLLABORATOR_ID,
                WebAuthnCeremony.REGISTRATION,
                "{\"request\":true}"
        );
        when(jdbc.update(
                anyString(),
                eq(CHALLENGE_ID),
                eq("REGISTRATION"),
                eq(CLIENT_INSTANCE_HASH)
        )).thenReturn(1);
        when(jdbc.query(
                anyString(),
                any(RowMapper.class),
                eq(CHALLENGE_ID),
                eq("REGISTRATION"),
                eq(CLIENT_INSTANCE_HASH)
        )).thenReturn(List.of(stored));

        assertThat(repository.consumeChallenge(
                CHALLENGE_ID,
                WebAuthnCeremony.REGISTRATION,
                CLIENT_INSTANCE_HASH
        )).containsSame(stored);

        ArgumentCaptor<String> updateSql =
                ArgumentCaptor.forClass(String.class);
        verify(jdbc).update(
                updateSql.capture(),
                eq(CHALLENGE_ID),
                eq("REGISTRATION"),
                eq(CLIENT_INSTANCE_HASH)
        );
        assertThat(compact(updateSql.getValue()))
                .contains("consumido_em = CURRENT_TIMESTAMP(6)")
                .contains("consumido_em IS NULL")
                .contains("expira_em > CURRENT_TIMESTAMP(6)")
                .contains("client_instance_hash = ?");
    }

    @Test
    void replayNeverReturnsTheStoredRequest() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        WebAuthnCredentialRepository repository = repository(jdbc);
        when(jdbc.update(
                anyString(),
                eq(CHALLENGE_ID),
                eq("AUTHENTICATION"),
                eq(CLIENT_INSTANCE_HASH)
        )).thenReturn(0);

        assertThat(repository.consumeChallenge(
                CHALLENGE_ID,
                WebAuthnCeremony.AUTHENTICATION,
                CLIENT_INSTANCE_HASH
        )).isEmpty();

        verify(jdbc, never()).query(
                anyString(),
                any(RowMapper.class),
                any(Object[].class)
        );
    }

    @Test
    @SuppressWarnings("unchecked")
    void credentialLookupRequiresExactOwnerAndCanonicalActiveUser() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        WebAuthnCredentialRepository repository = repository(jdbc);
        ByteArray credentialId = new ByteArray(new byte[]{5, 6, 7});
        ByteArray userHandle = WebAuthnUserHandle.fromCollaboratorId(
                COLLABORATOR_ID
        );
        RegisteredCredential credential = RegisteredCredential.builder()
                .credentialId(credentialId)
                .userHandle(userHandle)
                .publicKeyCose(new ByteArray(new byte[]{8, 9, 10}))
                .signatureCount(4)
                .build();
        when(jdbc.query(
                anyString(),
                any(RowMapper.class),
                anyString(),
                eq(credentialId.getBytes()),
                eq(userHandle.getBytes())
        )).thenReturn(List.of(credential));

        assertThat(repository.lookup(credentialId, userHandle))
                .containsSame(credential);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).query(
                sql.capture(),
                any(RowMapper.class),
                anyString(),
                eq(credentialId.getBytes()),
                eq(userHandle.getBytes())
        );
        assertThat(compact(sql.getValue()))
                .contains("JOIN colaborador")
                .contains("credencial.revogado_em IS NULL")
                .contains("colaborador.ativo = TRUE")
                .contains("colaborador.deletado_em IS NULL")
                .contains("colaborador.papel_acesso IN ('ALFA', 'BETA')")
                .contains("credencial.credential_id = ?")
                .contains("credencial.user_handle = ?");
    }

    private WebAuthnCredentialRepository repository(JdbcTemplate jdbc) {
        return new WebAuthnCredentialRepository(jdbc, new ObjectMapper());
    }

    private String compact(String sql) {
        return sql.replaceAll("\\s+", " ").trim();
    }
}
