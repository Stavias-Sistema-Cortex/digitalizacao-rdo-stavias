package com.projeto.cortex.auth.bootstrap;

import com.projeto.cortex.auth.identity.CpfLookupDigest;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Canonical PostgreSQL writes for the initial ALFA. Callers must already hold
 * the sole transaction so a receipt claim, its capability, and its Memory
 * event either all persist or all roll back together.
 */
@Component
@Profile("postgresql-bootstrap")
public final class PostgresqlInitialAlfaBootstrapRepository {

    static final String ACADEMY_DATABASE = "dbstavias_acad";
    static final String ACADEMY_TABLE = "usuarios";
    static final String CAPABILITY = "ADMINISTRAR_PAPEIS";
    static final String CAPABILITY_RATIONALE =
            "Bootstrap inicial ALFA autorizado por operação segura.";

    private static final String SELECT_BY_ID_FOR_UPDATE = """
            SELECT id, banco_origem, tabela_origem, pk_origem, cpf_hash,
                   nome, email, papel_acesso, ativo, deletado_em
            FROM colaborador
            WHERE id = ?
            FOR UPDATE
            """;
    private static final String SELECT_BY_SOURCE_FOR_UPDATE = """
            SELECT id, banco_origem, tabela_origem, pk_origem, cpf_hash,
                   nome, email, papel_acesso, ativo, deletado_em
            FROM colaborador
            WHERE banco_origem = ? AND tabela_origem = ? AND pk_origem = ?
            FOR UPDATE
            """;
    private static final String INSERT_COLLABORATOR = """
            INSERT INTO colaborador (
                id, banco_origem, tabela_origem, pk_origem, nome, email,
                id_grupo_origem, nome_grupo, id_perfil_origem, nome_perfil,
                papel_acesso, ativo, criado_em_origem
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'ALFA', TRUE, ?)
            """;
    private static final String SELECT_IDENTITY_FOR_UPDATE = """
            SELECT colaborador_id, cpf_lookup_hmac, cpf_lookup_key_id,
                   email_autenticacao, status, email_fonte
            FROM auth_identity
            WHERE colaborador_id = ?
            FOR UPDATE
            """;
    private static final String SELECT_EMAIL_OWNER_FOR_UPDATE = """
            SELECT colaborador_id
            FROM auth_identity
            WHERE lower(email_autenticacao) = lower(?)
            FOR UPDATE
            """;
    private static final String INSERT_IDENTITY = """
            INSERT INTO auth_identity (
                colaborador_id, cpf_lookup_hmac, cpf_lookup_key_id,
                email_autenticacao, email_fonte, status
            ) VALUES (?, ?, ?, ?, 'ACADEMY', 'PENDENTE')
            """;
    private static final String SELECT_CAPABILITY_FOR_UPDATE = """
            SELECT ativa, concedida_por, justificativa_concessao, revogada_em
            FROM auth_capacidade_administrativa
            WHERE colaborador_id = ? AND capacidade = 'ADMINISTRAR_PAPEIS'
            FOR UPDATE
            """;
    private static final String INSERT_CAPABILITY = """
            INSERT INTO auth_capacidade_administrativa (
                colaborador_id, capacidade, ativa, concedida_por,
                justificativa_concessao
            ) VALUES (?, 'ADMINISTRAR_PAPEIS', TRUE, ?, ?)
            """;
    private static final String SELECT_RECEIPT_FOR_UPDATE = """
            SELECT identidades_processadas
            FROM auth_provisioning_receipt
            WHERE arquivo_digest = ?
            FOR UPDATE
            """;
    private static final String SERIALIZE_RECEIPT_SQL = """
            SELECT pg_advisory_xact_lock(hashtextextended(?, 0))
            """;
    private static final String CLAIM_RECEIPT = """
            INSERT INTO auth_provisioning_receipt (
                arquivo_digest, identidades_processadas
            ) VALUES (?, 1)
            ON CONFLICT (arquivo_digest) DO NOTHING
            """;

    private final JdbcTemplate jdbcTemplate;
    private final PostgresqlBootstrapMemoryAuditRepository memoryAudit;

    public PostgresqlInitialAlfaBootstrapRepository(
            JdbcTemplate jdbcTemplate,
            PostgresqlBootstrapMemoryAuditRepository memoryAudit
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.memoryAudit = memoryAudit;
    }

    PostgresqlInitialAlfaBootstrapService.BootstrapResult bootstrap(
            BootstrapProjection projection,
            CpfLookupDigest protectedCpf,
            String receiptDigest
    ) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Bootstrap PostgreSQL exige transação ativa.");
        }

        // The receipt is derived from a protected HMAC, never raw identity data.
        jdbcTemplate.queryForObject(
                SERIALIZE_RECEIPT_SQL,
                (rows, index) -> Boolean.TRUE,
                receiptDigest
        );

        List<SourceOwner> byId = jdbcTemplate.query(
                SELECT_BY_ID_FOR_UPDATE,
                (rows, index) -> readOwner(rows),
                projection.collaboratorId()
        );
        List<SourceOwner> bySource = jdbcTemplate.query(
                SELECT_BY_SOURCE_FOR_UPDATE,
                (rows, index) -> readOwner(rows),
                ACADEMY_DATABASE,
                ACADEMY_TABLE,
                projection.sourcePrimaryKey()
        );
        validateOwnerShape(byId, bySource, projection);
        List<Integer> receipts = jdbcTemplate.query(
                SELECT_RECEIPT_FOR_UPDATE,
                (rows, index) -> rows.getInt("identidades_processadas"),
                receiptDigest
        );
        if (receipts.size() > 1 || (!receipts.isEmpty() && receipts.getFirst() != 1)) {
            throw conflict();
        }
        if (!receipts.isEmpty()) {
            if (byId.isEmpty()) {
                throw conflict();
            }
            validateExistingIdentity(projection, protectedCpf);
            validateExistingCapability(projection.collaboratorId());
            return PostgresqlInitialAlfaBootstrapService.BootstrapResult.ALREADY_APPLIED;
        }
        if (byId.isEmpty()) {
            jdbcTemplate.update(
                    INSERT_COLLABORATOR,
                    projection.collaboratorId(),
                    ACADEMY_DATABASE,
                    ACADEMY_TABLE,
                    projection.sourcePrimaryKey(),
                    projection.name(),
                    projection.email(),
                    projection.groupId(),
                    projection.groupName(),
                    projection.profileId(),
                    projection.profileName(),
                    timestamp(projection.sourceCreatedAt())
            );
        }

        validateOrInsertIdentity(projection, protectedCpf);
        validateOrInsertCapability(projection.collaboratorId());

        int claimed = jdbcTemplate.update(CLAIM_RECEIPT, receiptDigest);
        if (claimed == 0) {
            throw conflict();
        }

        memoryAudit.recordInitialAlfaBootstrap(projection.collaboratorId());
        return PostgresqlInitialAlfaBootstrapService.BootstrapResult.CREATED;
    }

    private void validateOwnerShape(
            List<SourceOwner> byId,
            List<SourceOwner> bySource,
            BootstrapProjection projection
    ) {
        if (byId.size() > 1 || bySource.size() > 1) {
            throw conflict();
        }
        if (byId.isEmpty() != bySource.isEmpty()) {
            throw conflict();
        }
        if (byId.isEmpty()) {
            return;
        }
        SourceOwner owner = byId.getFirst();
        if (!owner.id().equals(bySource.getFirst().id())
                || !owner.id().equals(projection.collaboratorId())
                || !ACADEMY_DATABASE.equals(owner.sourceDatabase())
                || !ACADEMY_TABLE.equals(owner.sourceTable())
                || !projection.sourcePrimaryKey().equals(owner.sourcePrimaryKey())
                || owner.cpfHash() != null
                || !projection.name().equals(owner.name())
                || !projection.email().equalsIgnoreCase(owner.email())
                || !"ALFA".equals(owner.role())
                || !owner.active()
                || owner.deletedAt() != null) {
            throw conflict();
        }
    }

    private void validateOrInsertIdentity(
            BootstrapProjection projection,
            CpfLookupDigest protectedCpf
    ) {
        validateEmailOwner(projection);
        List<IdentityOwner> existing = findIdentity(projection.collaboratorId());
        if (existing.size() > 1) {
            throw conflict();
        }
        if (existing.isEmpty()) {
            jdbcTemplate.update(
                    INSERT_IDENTITY,
                    projection.collaboratorId(),
                    protectedCpf.value(),
                    protectedCpf.keyId(),
                    projection.email()
            );
            return;
        }
        validateIdentity(existing.getFirst(), projection, protectedCpf);
    }

    private void validateExistingIdentity(
            BootstrapProjection projection,
            CpfLookupDigest protectedCpf
    ) {
        validateEmailOwner(projection);
        List<IdentityOwner> existing = findIdentity(projection.collaboratorId());
        if (existing.size() != 1) {
            throw conflict();
        }
        validateIdentity(existing.getFirst(), projection, protectedCpf);
    }

    private void validateEmailOwner(BootstrapProjection projection) {
        List<String> emailOwners = jdbcTemplate.query(
                SELECT_EMAIL_OWNER_FOR_UPDATE,
                (rows, index) -> rows.getString("colaborador_id"),
                projection.email()
        );
        if (emailOwners.size() > 1
                || (!emailOwners.isEmpty()
                    && !projection.collaboratorId().equals(emailOwners.getFirst()))) {
            throw conflict();
        }
    }

    private List<IdentityOwner> findIdentity(String collaboratorId) {
        return jdbcTemplate.query(
                SELECT_IDENTITY_FOR_UPDATE,
                (rows, index) -> new IdentityOwner(
                        rows.getString("colaborador_id"),
                        rows.getString("cpf_lookup_hmac"),
                        rows.getString("cpf_lookup_key_id"),
                        rows.getString("email_autenticacao"),
                        rows.getString("status"),
                        rows.getString("email_fonte")
                ),
                collaboratorId
        );
    }

    private void validateIdentity(
            IdentityOwner identity,
            BootstrapProjection projection,
            CpfLookupDigest protectedCpf
    ) {
        if (!projection.collaboratorId().equals(identity.collaboratorId())
                || !protectedCpf.value().equals(identity.cpfLookupHmac())
                || !protectedCpf.keyId().equals(identity.cpfLookupKeyId())
                || !projection.email().equalsIgnoreCase(identity.email())
                || !("PENDENTE".equals(identity.status()) || "ATIVA".equals(identity.status()))
                || !"ACADEMY".equals(identity.emailSource())) {
            throw conflict();
        }
    }

    private void validateOrInsertCapability(String collaboratorId) {
        List<CapabilityGrant> existing = jdbcTemplate.query(
                SELECT_CAPABILITY_FOR_UPDATE,
                (rows, index) -> new CapabilityGrant(
                        rows.getBoolean("ativa"),
                        rows.getString("concedida_por"),
                        rows.getString("justificativa_concessao"),
                        rows.getTimestamp("revogada_em")
                ),
                collaboratorId
        );
        if (existing.size() > 1) {
            throw conflict();
        }
        if (existing.isEmpty()) {
            jdbcTemplate.update(
                    INSERT_CAPABILITY,
                    collaboratorId,
                    collaboratorId,
                    CAPABILITY_RATIONALE
            );
            return;
        }

        validateCapability(existing.getFirst(), collaboratorId);
    }

    private void validateExistingCapability(String collaboratorId) {
        List<CapabilityGrant> existing = jdbcTemplate.query(
                SELECT_CAPABILITY_FOR_UPDATE,
                (rows, index) -> new CapabilityGrant(
                        rows.getBoolean("ativa"),
                        rows.getString("concedida_por"),
                        rows.getString("justificativa_concessao"),
                        rows.getTimestamp("revogada_em")
                ),
                collaboratorId
        );
        if (existing.size() != 1) {
            throw conflict();
        }
        validateCapability(existing.getFirst(), collaboratorId);
    }

    private void validateCapability(CapabilityGrant capability, String collaboratorId) {
        if (!capability.active()
                || !collaboratorId.equals(capability.grantedBy())
                || !CAPABILITY_RATIONALE.equals(capability.rationale())
                || capability.revokedAt() != null) {
            throw conflict();
        }
    }

    private static SourceOwner readOwner(java.sql.ResultSet rows) throws java.sql.SQLException {
        return new SourceOwner(
                rows.getString("id"),
                rows.getString("banco_origem"),
                rows.getString("tabela_origem"),
                rows.getString("pk_origem"),
                rows.getString("cpf_hash"),
                rows.getString("nome"),
                rows.getString("email"),
                rows.getString("papel_acesso"),
                rows.getBoolean("ativo"),
                rows.getTimestamp("deletado_em")
        );
    }

    private static Timestamp timestamp(LocalDateTime value) {
        return value == null ? null : Timestamp.valueOf(value);
    }

    private static BootstrapConflictException conflict() {
        return new BootstrapConflictException();
    }

    private record SourceOwner(
            String id,
            String sourceDatabase,
            String sourceTable,
            String sourcePrimaryKey,
            String cpfHash,
            String name,
            String email,
            String role,
            boolean active,
            Timestamp deletedAt
    ) {
    }

    private record IdentityOwner(
            String collaboratorId,
            String cpfLookupHmac,
            String cpfLookupKeyId,
            String email,
            String status,
            String emailSource
    ) {
    }

    private record CapabilityGrant(
            boolean active,
            String grantedBy,
            String rationale,
            Timestamp revokedAt
    ) {
    }

    static final class BootstrapConflictException extends IllegalStateException {

        private BootstrapConflictException() {
            super("Bootstrap inicial do ALFA não pôde ser concluído.");
        }
    }
}

record BootstrapProjection(
        String collaboratorId,
        String sourcePrimaryKey,
        String name,
        String email,
        String groupId,
        String groupName,
        String profileId,
        String profileName,
        LocalDateTime sourceCreatedAt
) {
}
