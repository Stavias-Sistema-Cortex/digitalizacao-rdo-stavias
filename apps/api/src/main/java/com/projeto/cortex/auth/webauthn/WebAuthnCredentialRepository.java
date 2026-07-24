package com.projeto.cortex.auth.webauthn;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.projeto.cortex.auth.PapelAcesso;
import com.projeto.cortex.auth.otp.AuthenticatedIdentity;
import com.yubico.webauthn.CredentialRepository;
import com.yubico.webauthn.RegisteredCredential;
import com.yubico.webauthn.data.AuthenticatorTransport;
import com.yubico.webauthn.data.ByteArray;
import com.yubico.webauthn.data.PublicKeyCredentialDescriptor;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** JDBC persistence and Yubico credential lookup for WebAuthn ceremonies. */
@Repository
public class WebAuthnCredentialRepository implements CredentialRepository {

    private static final TypeReference<List<String>> STRING_LIST =
            new TypeReference<>() { };

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public WebAuthnCredentialRepository(
            JdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper
    ) {
        this.jdbcTemplate = java.util.Objects.requireNonNull(jdbcTemplate);
        this.objectMapper = java.util.Objects.requireNonNull(objectMapper);
    }

    public void createChallenge(
            String id,
            String collaboratorId,
            WebAuthnCeremony ceremony,
            ByteArray challenge,
            String requestJson,
            int ttlSeconds
    ) {
        requireUuid(id, "Challenge WebAuthn inválido.");
        if (collaboratorId != null) {
            requireUuid(collaboratorId, "Colaborador WebAuthn inválido.");
        }
        if (ceremony == null
                || challenge == null
                || challenge.isEmpty()
                || requestJson == null
                || requestJson.isBlank()
                || ttlSeconds != WebAuthnProperties.REQUIRED_CHALLENGE_TTL_SECONDS) {
            throw new IllegalArgumentException("Challenge WebAuthn inválido.");
        }
        int inserted = jdbcTemplate.update(
                """
                INSERT INTO auth_webauthn_challenge (
                    id,
                    colaborador_id,
                    ceremony,
                    challenge_hash,
                    request_json,
                    expira_em
                ) VALUES (?, ?, ?, ?, ?::jsonb,
                    CURRENT_TIMESTAMP(6) + (? * INTERVAL '1 second'))
                """,
                id,
                collaboratorId,
                ceremony.name(),
                sha256(challenge.getBytes()),
                requestJson,
                ttlSeconds
        );
        if (inserted != 1) {
            throw new IllegalStateException(
                    "Não foi possível persistir o challenge WebAuthn."
            );
        }
    }

    /**
     * Commits consumption in its own transaction so later parser or signature
     * failures cannot make a response replayable.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<StoredWebAuthnChallenge> consumeChallenge(
            String id,
            WebAuthnCeremony ceremony
    ) {
        if (WebAuthnUserHandle.parseCanonical(id).isEmpty()
                || ceremony == null) {
            return Optional.empty();
        }
        int consumed = jdbcTemplate.update(
                """
                UPDATE auth_webauthn_challenge
                SET consumido_em = CURRENT_TIMESTAMP(6)
                WHERE id = ?
                  AND ceremony = ?
                  AND consumido_em IS NULL
                  AND expira_em > CURRENT_TIMESTAMP(6)
                """,
                id,
                ceremony.name()
        );
        if (consumed != 1) {
            return Optional.empty();
        }
        return jdbcTemplate.query(
                """
                SELECT id, colaborador_id, ceremony, request_json
                FROM auth_webauthn_challenge
                WHERE id = ? AND ceremony = ?
                LIMIT 1
                """,
                (rs, rowNum) -> new StoredWebAuthnChallenge(
                        rs.getString("id"),
                        rs.getString("colaborador_id"),
                        WebAuthnCeremony.valueOf(rs.getString("ceremony")),
                        rs.getString("request_json")
                ),
                id,
                ceremony.name()
        ).stream().findFirst();
    }

    public Optional<AuthenticatedIdentity> findActiveIdentity(
            String collaboratorId
    ) {
        if (WebAuthnUserHandle.parseCanonical(collaboratorId).isEmpty()) {
            return Optional.empty();
        }
        return jdbcTemplate.query(
                """
                SELECT colaborador.id, colaborador.nome,
                       colaborador.papel_acesso
                FROM colaborador
                JOIN auth_identity identidade
                  ON identidade.colaborador_id = colaborador.id
                WHERE colaborador.id = ?
                  AND colaborador.ativo = TRUE
                  AND colaborador.deletado_em IS NULL
                  AND colaborador.papel_acesso IN ('ALFA', 'BETA')
                  AND identidade.status = 'ATIVA'
                LIMIT 1
                """,
                (rs, rowNum) -> new AuthenticatedIdentity(
                        rs.getString("id"),
                        rs.getString("nome"),
                        PapelAcesso.fromPersistedExact(
                                rs.getString("papel_acesso")
                        ).orElseThrow()
                ),
                collaboratorId
        ).stream().findFirst();
    }

    public Optional<String> findActiveCredentialOwner(ByteArray credentialId) {
        if (credentialId == null || credentialId.isEmpty()) {
            return Optional.empty();
        }
        return jdbcTemplate.query(
                """
                SELECT credencial.colaborador_id
                FROM auth_webauthn_credential credencial
                JOIN colaborador
                  ON colaborador.id = credencial.colaborador_id
                JOIN auth_identity identidade
                  ON identidade.colaborador_id = colaborador.id
                WHERE credencial.credential_id_hash = ?
                  AND credencial.credential_id = ?
                  AND credencial.revogado_em IS NULL
                  AND colaborador.ativo = TRUE
                  AND colaborador.deletado_em IS NULL
                  AND colaborador.papel_acesso IN ('ALFA', 'BETA')
                  AND identidade.status = 'ATIVA'
                LIMIT 1
                """,
                (rs, rowNum) -> rs.getString("colaborador_id"),
                sha256(credentialId.getBytes()),
                credentialId.getBytes()
        ).stream().findFirst();
    }

    public Instant saveCredential(
            String id,
            String collaboratorId,
            NewWebAuthnCredential credential,
            String name
    ) {
        requireUuid(id, "Identificador de passkey inválido.");
        requireUuid(collaboratorId, "Colaborador WebAuthn inválido.");
        java.util.Objects.requireNonNull(credential, "Passkey obrigatória.");
        String transports = writeTransports(credential.transports());
        int inserted = jdbcTemplate.update(
                """
                INSERT INTO auth_webauthn_credential (
                    id,
                    colaborador_id,
                    credential_id_hash,
                    credential_id,
                    user_handle,
                    public_key_cose,
                    signature_count,
                    transports_json,
                    aaguid,
                    discoverable,
                    backed_up,
                    nome
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                id,
                collaboratorId,
                sha256(credential.credentialId().getBytes()),
                credential.credentialId().getBytes(),
                credential.userHandle().getBytes(),
                credential.publicKeyCose().getBytes(),
                credential.signatureCount(),
                transports,
                credential.aaguid(),
                credential.discoverable(),
                credential.backedUp(),
                normalizeName(name)
        );
        if (inserted != 1) {
            throw new IllegalStateException(
                    "Não foi possível persistir a passkey."
            );
        }
        Timestamp createdAt = jdbcTemplate.queryForObject(
                """
                SELECT criado_em
                FROM auth_webauthn_credential
                WHERE id = ?
                """,
                Timestamp.class,
                id
        );
        if (createdAt == null) {
            throw new IllegalStateException(
                    "Criação da passkey não confirmada."
            );
        }
        return createdAt.toInstant();
    }

    public boolean recordAuthentication(
            ByteArray credentialId,
            String collaboratorId,
            long signatureCount,
            boolean backedUp
    ) {
        if (credentialId == null
                || credentialId.isEmpty()
                || WebAuthnUserHandle.parseCanonical(collaboratorId).isEmpty()
                || signatureCount < 0) {
            return false;
        }
        return jdbcTemplate.update(
                """
                UPDATE auth_webauthn_credential
                SET signature_count = GREATEST(signature_count, ?),
                    backed_up = ?,
                    usado_em = CURRENT_TIMESTAMP(6)
                WHERE credential_id_hash = ?
                  AND credential_id = ?
                  AND colaborador_id = ?
                  AND revogado_em IS NULL
                """,
                signatureCount,
                backedUp,
                sha256(credentialId.getBytes()),
                credentialId.getBytes(),
                collaboratorId
        ) == 1;
    }

    @Override
    public Set<PublicKeyCredentialDescriptor> getCredentialIdsForUsername(
            String username
    ) {
        if (WebAuthnUserHandle.parseCanonical(username).isEmpty()) {
            return Set.of();
        }
        List<PublicKeyCredentialDescriptor> credentials = jdbcTemplate.query(
                """
                SELECT credencial.credential_id,
                       credencial.transports_json
                FROM auth_webauthn_credential credencial
                JOIN colaborador
                  ON colaborador.id = credencial.colaborador_id
                JOIN auth_identity identidade
                  ON identidade.colaborador_id = colaborador.id
                WHERE credencial.colaborador_id = ?
                  AND credencial.revogado_em IS NULL
                  AND colaborador.ativo = TRUE
                  AND colaborador.deletado_em IS NULL
                  AND colaborador.papel_acesso IN ('ALFA', 'BETA')
                  AND identidade.status = 'ATIVA'
                """,
                (rs, rowNum) -> PublicKeyCredentialDescriptor.builder()
                        .id(new ByteArray(rs.getBytes("credential_id")))
                        .transports(readTransports(
                                rs.getString("transports_json")
                        ))
                        .build(),
                username
        );
        return Set.copyOf(credentials);
    }

    @Override
    public Optional<ByteArray> getUserHandleForUsername(String username) {
        return findActiveIdentity(username).map(identity ->
                WebAuthnUserHandle.fromCollaboratorId(
                        identity.colaboradorId()
                )
        );
    }

    @Override
    public Optional<String> getUsernameForUserHandle(ByteArray userHandle) {
        return WebAuthnUserHandle.toCollaboratorId(userHandle)
                .flatMap(this::findActiveIdentity)
                .map(AuthenticatedIdentity::colaboradorId);
    }

    @Override
    public Optional<RegisteredCredential> lookup(
            ByteArray credentialId,
            ByteArray userHandle
    ) {
        if (credentialId == null
                || credentialId.isEmpty()
                || userHandle == null
                || userHandle.isEmpty()) {
            return Optional.empty();
        }
        return queryCredentials(
                """
                SELECT credencial.credential_id,
                       credencial.user_handle,
                       credencial.public_key_cose,
                       credencial.signature_count,
                       credencial.transports_json,
                       credencial.backed_up
                FROM auth_webauthn_credential credencial
                JOIN colaborador
                  ON colaborador.id = credencial.colaborador_id
                JOIN auth_identity identidade
                  ON identidade.colaborador_id = colaborador.id
                WHERE credencial.credential_id_hash = ?
                  AND credencial.credential_id = ?
                  AND credencial.user_handle = ?
                  AND credencial.revogado_em IS NULL
                  AND colaborador.ativo = TRUE
                  AND colaborador.deletado_em IS NULL
                  AND colaborador.papel_acesso IN ('ALFA', 'BETA')
                  AND identidade.status = 'ATIVA'
                LIMIT 1
                """,
                sha256(credentialId.getBytes()),
                credentialId.getBytes(),
                userHandle.getBytes()
        ).stream().findFirst();
    }

    @Override
    public Set<RegisteredCredential> lookupAll(ByteArray credentialId) {
        if (credentialId == null || credentialId.isEmpty()) {
            return Set.of();
        }
        return new LinkedHashSet<>(queryCredentials(
                """
                SELECT credencial.credential_id,
                       credencial.user_handle,
                       credencial.public_key_cose,
                       credencial.signature_count,
                       credencial.transports_json,
                       credencial.backed_up
                FROM auth_webauthn_credential credencial
                JOIN colaborador
                  ON colaborador.id = credencial.colaborador_id
                JOIN auth_identity identidade
                  ON identidade.colaborador_id = colaborador.id
                WHERE credencial.credential_id_hash = ?
                  AND credencial.credential_id = ?
                  AND credencial.revogado_em IS NULL
                  AND colaborador.ativo = TRUE
                  AND colaborador.deletado_em IS NULL
                  AND colaborador.papel_acesso IN ('ALFA', 'BETA')
                  AND identidade.status = 'ATIVA'
                """,
                sha256(credentialId.getBytes()),
                credentialId.getBytes()
        ));
    }

    private List<RegisteredCredential> queryCredentials(
            String sql,
            Object... arguments
    ) {
        return jdbcTemplate.query(sql, this::mapCredential, arguments);
    }

    private RegisteredCredential mapCredential(ResultSet rs, int rowNum)
            throws SQLException {
        return RegisteredCredential.builder()
                .credentialId(new ByteArray(rs.getBytes("credential_id")))
                .userHandle(new ByteArray(rs.getBytes("user_handle")))
                .publicKeyCose(new ByteArray(rs.getBytes("public_key_cose")))
                .signatureCount(rs.getLong("signature_count"))
                .transports(readTransports(rs.getString("transports_json")))
                .backupState(rs.getBoolean("backed_up"))
                .build();
    }

    private Set<AuthenticatorTransport> readTransports(String json)
            throws SQLException {
        try {
            List<String> values = objectMapper.readValue(json, STRING_LIST);
            LinkedHashSet<AuthenticatorTransport> transports =
                    new LinkedHashSet<>();
            for (String value : values) {
                transports.add(AuthenticatorTransport.of(value));
            }
            return Set.copyOf(transports);
        } catch (JsonProcessingException | RuntimeException exception) {
            throw new SQLException("Transportes WebAuthn inválidos.", exception);
        }
    }

    private String writeTransports(Set<AuthenticatorTransport> transports) {
        try {
            return objectMapper.writeValueAsString(
                    transports.stream()
                            .map(AuthenticatorTransport::getId)
                            .sorted()
                            .toList()
            );
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(
                    "Não foi possível serializar transportes WebAuthn.",
                    exception
            );
        }
    }

    private String normalizeName(String name) {
        if (name == null || name.isBlank()) {
            return "Passkey";
        }
        String normalized = name.strip();
        if (normalized.length() > 120) {
            throw new IllegalArgumentException("Nome da passkey inválido.");
        }
        return normalized;
    }

    private String sha256(byte[] material) {
        if (material == null || material.length == 0) {
            throw new IllegalArgumentException("Material WebAuthn inválido.");
        }
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(material)
            );
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(
                    "SHA-256 indisponível para WebAuthn.",
                    exception
            );
        }
    }

    private void requireUuid(String value, String message) {
        if (WebAuthnUserHandle.parseCanonical(value).isEmpty()) {
            throw new IllegalArgumentException(message);
        }
    }
}
