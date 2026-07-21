package com.projeto.cortex.auth.identity;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Legacy constant-query CPF lookup used only outside PostgreSQL profiles. */
@Repository
@Profile("!postgresql-common")
public class MysqlAuthIdentityChallengeLookup
        implements AuthenticationChallengeLookup {

    private static final String AMBIGUOUS_IDENTITY_MESSAGE =
            "Identidade de autenticação ambígua.";

    private final JdbcTemplate jdbcTemplate;
    private final CpfLookupDigestService digestService;

    public MysqlAuthIdentityChallengeLookup(
            JdbcTemplate jdbcTemplate,
            CpfLookupDigestService digestService
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.digestService = digestService;
    }

    @Override
    public Optional<AuthIdentity> find(String identifier) {
        AuthChallengeLookupMaterial material =
                digestService.challengeLookup(identifier);
        CpfLookupDigest current = material.candidates().get(0);
        CpfLookupDigest previous = material.candidates().size() == 2
                ? material.candidates().get(1)
                : current;
        List<Candidate> candidates = jdbcTemplate.query("""
                SELECT
                    colaborador.id AS colaborador_id,
                    colaborador.nome,
                    colaborador.papel_acesso,
                    colaborador.ativo,
                    colaborador.deletado_em,
                    identity.email_autenticacao,
                    identity.status AS identity_status
                FROM colaborador
                LEFT JOIN auth_identity identity
                    ON identity.colaborador_id = colaborador.id
                WHERE (
                    identity.cpf_lookup_key_id = ?
                    AND identity.cpf_lookup_hmac = ?
                ) OR (
                    identity.cpf_lookup_key_id = ?
                    AND identity.cpf_lookup_hmac = ?
                ) OR colaborador.cpf_hash = ?
                LIMIT 3
                """,
                (resultSet, rowNumber) -> new Candidate(
                        new AuthIdentity(
                                resultSet.getString("colaborador_id"),
                                resultSet.getString("nome"),
                                resultSet.getString("email_autenticacao"),
                                resultSet.getString("papel_acesso")
                        ),
                        resultSet.getBoolean("ativo"),
                        resultSet.getTimestamp("deletado_em") != null,
                        resultSet.getString("identity_status")
                ),
                current.keyId(),
                current.value(),
                previous.keyId(),
                previous.value(),
                material.legacyDigest()
        );

        Map<String, Candidate> owners = new LinkedHashMap<>();
        for (Candidate candidate : candidates) {
            owners.putIfAbsent(candidate.identity().colaboradorId(), candidate);
        }
        if (owners.size() > 1) {
            throw new AmbiguousAuthIdentityException(AMBIGUOUS_IDENTITY_MESSAGE);
        }
        return owners.values().stream()
                .filter(Candidate::eligible)
                .map(Candidate::identity)
                .findFirst();
    }

    private record Candidate(
            AuthIdentity identity,
            boolean active,
            boolean deleted,
            String identityStatus
    ) {

        boolean eligible() {
            return active && !deleted && !"BLOQUEADA".equals(identityStatus);
        }
    }
}
