package com.projeto.cortex.auth.identity;

import com.projeto.cortex.colaboradores.CpfHasher;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Locates authentication identities and upgrades lookup material. Returning an
 * identity from this repository does not authenticate the collaborator; OTP or
 * another verifiable challenge remains mandatory.
 */
@Repository
public class AuthIdentityRepository {

    private static final RowMapper<AuthIdentity> IDENTITY_ROW_MAPPER =
            (resultSet, rowNumber) -> new AuthIdentity(
                    resultSet.getString("colaborador_id"),
                    resultSet.getString("nome"),
                    resultSet.getString("email_autenticacao"),
                    resultSet.getString("papel_acesso")
            );

    private final JdbcTemplate jdbcTemplate;
    private final CpfLookupDigestService digestService;

    public AuthIdentityRepository(
            JdbcTemplate jdbcTemplate,
            CpfLookupDigestService digestService
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.digestService = digestService;
    }

    @Transactional
    public Optional<AuthIdentity> findActiveByCpf(String cpfRaw) {
        List<CpfLookupDigest> candidates = digestService.candidates(cpfRaw);
        if (candidates.isEmpty()) {
            throw new IllegalStateException(
                    "Configuração de CPF HMAC sem chave atual."
            );
        }

        CpfLookupDigest current = candidates.get(0);
        for (int index = 0; index < candidates.size(); index++) {
            Optional<AuthIdentity> identity = findByDigest(
                    candidates.get(index)
            );
            if (identity.isPresent()) {
                if (index > 0) {
                    upgradeToCurrent(
                            identity.orElseThrow().colaboradorId(),
                            current
                    );
                }
                return identity;
            }
        }

        String digits = CpfNormalizer.requireValid(cpfRaw);
        Optional<AuthIdentity> legacy = findByLegacySha(
                CpfHasher.hashDeDigitos(digits)
        );
        legacy.ifPresent(identity -> upgradeToCurrent(
                identity.colaboradorId(),
                current
        ));
        return legacy;
    }

    @Transactional
    public void upsertAcademyIdentity(
            String colaboradorId,
            String cpfRaw,
            String academyEmail
    ) {
        CpfLookupDigest digest = digestService.current(cpfRaw);
        jdbcTemplate.update("""
                INSERT INTO auth_identity (
                    colaborador_id,
                    cpf_lookup_hmac,
                    cpf_lookup_key_id,
                    email_autenticacao,
                    email_verificado_em,
                    email_fonte,
                    status
                ) VALUES (?, ?, ?, NULLIF(TRIM(?), ''), NULL, 'ACADEMY', 'PENDENTE')
                ON DUPLICATE KEY UPDATE
                    cpf_lookup_hmac = VALUES(cpf_lookup_hmac),
                    cpf_lookup_key_id = VALUES(cpf_lookup_key_id),
                    email_autenticacao = CASE
                        WHEN email_verificado_em IS NOT NULL
                          OR email_fonte = 'MANUAL_VERIFICADO'
                            THEN email_autenticacao
                        ELSE COALESCE(
                            NULLIF(TRIM(VALUES(email_autenticacao)), ''),
                            email_autenticacao
                        )
                    END,
                    email_fonte = CASE
                        WHEN email_verificado_em IS NOT NULL
                          OR email_fonte = 'MANUAL_VERIFICADO'
                            THEN email_fonte
                        WHEN NULLIF(TRIM(VALUES(email_autenticacao)), '') IS NOT NULL
                            THEN 'ACADEMY'
                        ELSE email_fonte
                    END,
                    versao_linha = versao_linha + 1
                """,
                colaboradorId,
                digest.value(),
                digest.keyId(),
                academyEmail
        );
    }

    private Optional<AuthIdentity> findByDigest(CpfLookupDigest digest) {
        return unique(jdbcTemplate.query("""
                SELECT
                    colaborador.id AS colaborador_id,
                    colaborador.nome,
                    identity.email_autenticacao,
                    colaborador.papel_acesso
                FROM auth_identity identity
                INNER JOIN colaborador
                    ON colaborador.id = identity.colaborador_id
                WHERE identity.cpf_lookup_key_id = ?
                  AND identity.cpf_lookup_hmac = ?
                  AND identity.status <> 'BLOQUEADA'
                  AND colaborador.ativo = 1
                  AND colaborador.deletado_em IS NULL
                LIMIT 2
                """,
                IDENTITY_ROW_MAPPER,
                digest.keyId(),
                digest.value()
        ));
    }

    private Optional<AuthIdentity> findByLegacySha(String legacySha) {
        return unique(jdbcTemplate.query("""
                SELECT
                    colaborador.id AS colaborador_id,
                    colaborador.nome,
                    identity.email_autenticacao,
                    colaborador.papel_acesso
                FROM colaborador
                LEFT JOIN auth_identity identity
                    ON identity.colaborador_id = colaborador.id
                WHERE colaborador.cpf_hash = ?
                  AND colaborador.ativo = 1
                  AND colaborador.deletado_em IS NULL
                  AND (
                      identity.status IS NULL
                      OR identity.status <> 'BLOQUEADA'
                  )
                LIMIT 2
                """,
                IDENTITY_ROW_MAPPER,
                legacySha
        ));
    }

    private Optional<AuthIdentity> unique(List<AuthIdentity> identities) {
        if (identities.size() > 1) {
            throw new IllegalStateException(
                    "Identidade de autenticação ambígua."
            );
        }
        return identities.stream().findFirst();
    }

    private void upgradeToCurrent(
            String colaboradorId,
            CpfLookupDigest current
    ) {
        jdbcTemplate.update("""
                INSERT INTO auth_identity (
                    colaborador_id,
                    cpf_lookup_hmac,
                    cpf_lookup_key_id,
                    status
                ) VALUES (?, ?, ?, 'PENDENTE')
                ON DUPLICATE KEY UPDATE
                    cpf_lookup_hmac = VALUES(cpf_lookup_hmac),
                    cpf_lookup_key_id = VALUES(cpf_lookup_key_id),
                    versao_linha = versao_linha + 1
                """,
                colaboradorId,
                current.value(),
                current.keyId()
        );
    }
}
