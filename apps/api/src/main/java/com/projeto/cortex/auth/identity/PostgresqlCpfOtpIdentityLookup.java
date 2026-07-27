package com.projeto.cortex.auth.identity;

import java.util.List;
import java.util.Optional;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Fixed-shape PostgreSQL-only CPF lookup for issuing an e-mail OTP.
 * Possession of a CPF is never sufficient to authenticate: this lookup only
 * locates the recipient of the separately verified, single-use challenge.
 */
@Repository
@Profile("postgresql")
public class PostgresqlCpfOtpIdentityLookup
        implements AuthenticationChallengeLookup {

    private final JdbcTemplate jdbcTemplate;
    private final CpfLookupDigestService digestService;

    public PostgresqlCpfOtpIdentityLookup(
            JdbcTemplate jdbcTemplate,
            CpfLookupDigestService digestService
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.digestService = digestService;
    }

    @Override
    public Optional<AuthIdentity> find(String identifier) {
        AuthChallengeLookupMaterial material = digestService.challengeLookup(
                identifier
        );
        CpfLookupDigest current = material.candidates().getFirst();
        CpfLookupDigest previous = material.candidates().size() == 2
                ? material.candidates().get(1)
                : current;
        List<AuthIdentity> identities = jdbcTemplate.query("""
                SELECT
                    colaborador.id AS colaborador_id,
                    colaborador.nome,
                    identity.email_autenticacao,
                    colaborador.papel_acesso
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
                  AND identity.status <> 'BLOQUEADA'
                LIMIT 2
                """,
                (resultSet, rowNumber) -> new AuthIdentity(
                        resultSet.getString("colaborador_id"),
                        resultSet.getString("nome"),
                        resultSet.getString("email_autenticacao"),
                        resultSet.getString("papel_acesso")
                ),
                current.keyId(),
                current.value(),
                previous.keyId(),
                previous.value()
        );
        return identities.size() == 1
                ? Optional.of(identities.getFirst())
                : Optional.empty();
    }
}
