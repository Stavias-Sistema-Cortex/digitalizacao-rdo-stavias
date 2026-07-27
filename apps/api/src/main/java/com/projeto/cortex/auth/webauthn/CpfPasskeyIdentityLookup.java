package com.projeto.cortex.auth.webauthn;

import com.projeto.cortex.auth.identity.AuthChallengeLookupMaterial;
import com.projeto.cortex.auth.identity.CpfLookupDigest;
import com.projeto.cortex.auth.identity.CpfLookupDigestService;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Read-only CPF lookup for binding discoverable authentication challenges. */
@Repository
public class CpfPasskeyIdentityLookup {

    private final JdbcTemplate jdbcTemplate;
    private final CpfLookupDigestService digestService;

    public CpfPasskeyIdentityLookup(
            JdbcTemplate jdbcTemplate,
            CpfLookupDigestService digestService
    ) {
        this.jdbcTemplate = java.util.Objects.requireNonNull(jdbcTemplate);
        this.digestService = java.util.Objects.requireNonNull(digestService);
    }

    public Optional<String> resolveOrDecoy(String cpf) {
        AuthChallengeLookupMaterial material =
                digestService.challengeLookup(cpf);
        CpfLookupDigest current = material.candidates().get(0);
        CpfLookupDigest previous = material.candidates().size() == 2
                ? material.candidates().get(1)
                : current;
        List<String> collaboratorIds = jdbcTemplate.query(
                """
                SELECT colaborador.id
                FROM auth_identity identity
                INNER JOIN colaborador
                    ON colaborador.id = identity.colaborador_id
                WHERE (
                    (
                        identity.cpf_lookup_key_id = ?
                        AND identity.cpf_lookup_hmac = ?
                    ) OR (
                        identity.cpf_lookup_key_id = ?
                        AND identity.cpf_lookup_hmac = ?
                    )
                )
                AND colaborador.ativo = TRUE
                AND colaborador.deletado_em IS NULL
                AND colaborador.papel_acesso IN ('ALFA', 'BETA')
                AND identity.status = 'ATIVA'
                AND EXISTS (
                    SELECT 1
                    FROM auth_webauthn_credential credential
                    WHERE credential.colaborador_id = colaborador.id
                      AND credential.revogado_em IS NULL
                )
                LIMIT 2
                """,
                (resultSet, rowNumber) -> resultSet.getString("id"),
                current.keyId(),
                current.value(),
                previous.keyId(),
                previous.value()
        );
        if (collaboratorIds.size() != 1) {
            return Optional.empty();
        }
        String collaboratorId = collaboratorIds.get(0);
        return WebAuthnUserHandle.parseCanonical(collaboratorId)
                .map(ignored -> collaboratorId);
    }
}
