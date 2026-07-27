package com.projeto.cortex.auth.identity;

import com.projeto.cortex.auth.otp.AuthenticationIdentifierNormalizer;
import com.projeto.cortex.email.EmailMessage;
import java.util.List;
import java.util.Optional;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** PostgreSQL-only e-mail lookup. It never queries protected CPF material. */
@Repository
@Profile("postgresql-activation")
public class PostgresqlEmailOtpIdentityLookup
        implements AuthenticationChallengeLookup {

    private static final String AMBIGUOUS_IDENTITY_MESSAGE =
            "Identidade de autenticação ambígua.";

    private final JdbcTemplate jdbcTemplate;

    public PostgresqlEmailOtpIdentityLookup(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public Optional<AuthIdentity> find(String identifier) {
        if (!isCanonicalEmail(identifier)) {
            return Optional.empty();
        }
        List<AuthIdentity> identities = jdbcTemplate.query("""
                SELECT
                    colaborador.id AS colaborador_id,
                    colaborador.nome,
                    identity.email_autenticacao,
                    colaborador.papel_acesso
                FROM auth_identity identity
                INNER JOIN colaborador
                    ON colaborador.id = identity.colaborador_id
                WHERE lower(identity.email_autenticacao) = ?
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
                identifier
        );
        if (identities.size() > 1) {
            throw new AmbiguousAuthIdentityException(AMBIGUOUS_IDENTITY_MESSAGE);
        }
        return identities.stream().findFirst();
    }

    private boolean isCanonicalEmail(String value) {
        return value != null
                && !AuthenticationIdentifierNormalizer.INVALID_VALUE.equals(value)
                && value.length() <= 320
                && value.equals(value.strip())
                && value.equals(value.toLowerCase(java.util.Locale.ROOT))
                && EmailMessage.isValidRecipient(value);
    }
}
