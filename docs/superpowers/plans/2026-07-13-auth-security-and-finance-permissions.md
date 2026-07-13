# Auth Security and Finance Permissions Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace CPF-only authentication with deploy-ready email OTP, revocable cookie sessions, WebAuthn/passkeys and PRF-gated offline access while preserving every explicit ALFA assignment and requiring explicit, worksite-scoped financial grants for BETA users.

**Architecture:** Keep Academy as the collaborator source but move authentication material into dedicated security tables. Resolve CPF through versioned HMAC keys loaded from mounted secret files, issue single-use email challenges through an `EmailGateway`, store only opaque session-token hashes, and use an audited WebAuthn server library for online passkeys; PRF unlocks a local encrypted authorization vault and never falls back to the legacy Bloom filter. Authorization remains server-side: `CurrentUserService` owns identity/role/worksite scope, while a focused `FinancialAccessService` composes worksite scope with explicit financial grants and is enforced in REST, sync and StavIA retrieval paths.

**Tech Stack:** Java 21, Spring Boot 3.3.5, Spring MVC/JdbcTemplate/JPA, MySQL 8.4, Flyway V27/V28, Spring Mail, Yubico `webauthn-server-core` 2.9.0, React 19, TypeScript 6, Vite 8, IndexedDB/idb 8, WebAuthn Level 3 PRF, JUnit 5/Mockito/MockMvc, Vitest.

## Global Constraints

- Run every Maven command with JDK 21; Java 25 is not supported by this repository's Mockito setup.
- Do not edit migrations V1-V26. All authentication, PII cleanup and role hardening schema changes belong to `V27__auth_security_and_pii_cleanup.sql`; all financial-grant schema changes belong to `V28__financial_permissions.sql`. Do not create any other migration number for this plan.
- Never store, return, log, commit or hardcode a real CPF, OTP, authentication e-mail, HMAC key, session secret, SMTP password or provisioning payload.
- Secrets must be accepted from mounted files (`*_FILE`) with environment-variable fallback only for local development; production readiness must reject inline/fake secrets and the fake e-mail provider.
- CPF remains an identifier only. Legacy SHA-256 may be used temporarily to locate an identity during migration, but never authenticates a request and never bypasses OTP/passkey verification.
- Existing explicit `papel_acesso='ALFA'` rows must remain ALFA. Null/unknown roles become BETA; runtime inference from Academy profile/group text must be removed.
- A release must refuse OTP cutover until at least one active ALFA has a verified authentication e-mail and an active authentication identity.
- OTP responses must not reveal whether a CPF exists, is active, has an e-mail or is ALFA. OTPs are single-use, expire, have shared-store rate limits and are never logged.
- Production sessions use opaque random tokens in `HttpOnly`, `Secure`, explicitly configured `SameSite` cookies. Unsafe requests require the matching CSRF header/cookie pair.
- WebAuthn registration/authentication is validated server-side by the pinned library. RP ID and allowed origins are configuration, exact-match and HTTPS-only outside loopback development.
- Offline unlock is available only when WebAuthn PRF succeeds. Unsupported browsers receive an explicit online-only state; there is no CPF, Bloom-filter, PIN or demo-user fallback.
- ALFA has implicit full financial access. BETA requires both an active worksite link and an active explicit grant for the requested financial capability.
- Financial authorization must be enforced before REST reads/writes, sync serialization and StavIA evidence retrieval; hiding navigation is not a security boundary.
- Tests and fixtures may use only reserved values such as CPF `11144477735`, domain `example.invalid`, and deterministic test secrets under `src/test`.
- Keep all behavior real-data-first: no fabricated financial records, no seeded production identities and no fake SMTP provider outside local/test.

---

## File Structure and Ownership

### API files to create

- `apps/api/src/main/resources/db/migration/V27__auth_security_and_pii_cleanup.sql` — authentication identities, challenges, sessions, WebAuthn credentials/challenges, rate-limit buckets, provisioning receipts, PII cleanup and explicit-role hardening.
- `apps/api/src/main/resources/db/migration/V28__financial_permissions.sql` — worksite-scoped BETA financial grants and audit columns.
- `apps/api/src/main/java/com/projeto/cortex/config/SecretMaterialLoader.java` — exclusive `_FILE`/inline secret resolution with production policy.
- `apps/api/src/main/java/com/projeto/cortex/auth/identity/CpfNormalizer.java` — CPF syntax/check-digit validation only.
- `apps/api/src/main/java/com/projeto/cortex/auth/identity/CpfLookupDigest.java` — versioned digest value.
- `apps/api/src/main/java/com/projeto/cortex/auth/identity/CpfLookupDigestService.java` — current/previous HMAC candidates.
- `apps/api/src/main/java/com/projeto/cortex/auth/identity/HmacCpfLookupDigestService.java` — HMAC-SHA-256 implementation backed by secret files.
- `apps/api/src/main/java/com/projeto/cortex/auth/identity/AuthIdentity.java` and `AuthIdentityRepository.java` — typed active identity, HMAC/legacy lookup and atomic lazy upgrade.
- `apps/api/src/main/java/com/projeto/cortex/auth/identity/AuthIdentityProvisioningRunner.java` and `ProvisioningReceiptRepository.java` — non-web, replay-safe one-shot secret-file provisioning.
- `apps/api/src/main/java/com/projeto/cortex/email/EmailGateway.java` and `EmailMessage.java` — provider-neutral outbound mail contract.
- `apps/api/src/main/java/com/projeto/cortex/email/FakeEmailGateway.java` — local/test capture provider.
- `apps/api/src/main/java/com/projeto/cortex/email/SmtpEmailGateway.java` and `EmailConfiguration.java` — real SMTP provider with secret-file password.
- `apps/api/src/main/java/com/projeto/cortex/auth/otp/EmailOtpChallengeService.java`, request/response records and `AuthRateLimiter.java` — generic, single-use OTP flow.
- `apps/api/src/main/java/com/projeto/cortex/auth/session/AuthSessionService.java`, `AuthSessionFilter.java`, `CsrfRequestFilter.java`, `AuthCookieService.java` — revocable server sessions and cookie/CSRF boundary.
- `apps/api/src/main/java/com/projeto/cortex/auth/webauthn/WebAuthnCredentialRepository.java`, `WebAuthnService.java`, `WebAuthnController.java`, `WebAuthnConfiguration.java` — audited online passkey ceremonies.
- `apps/api/src/main/java/com/projeto/cortex/auth/AuthReadinessIndicator.java` — fail-closed deploy/cutover readiness.
- `apps/api/src/main/java/com/projeto/cortex/financeiro/access/FinancialPermission.java`, `FinancialAccessService.java`, `FinancialGrantRepository.java`, `FinancialGrantService.java`, `FinancialGrantController.java` and DTOs — explicit grant management and checks.

### API files to modify or delete

- Modify `apps/api/pom.xml` — add Spring Mail and Yubico WebAuthn dependencies.
- Modify `apps/api/src/main/resources/application.yml` and `application-local.yml` — auth, cookie, OTP, SMTP, WebAuthn and exact CORS configuration.
- Modify `apps/api/src/main/java/com/projeto/cortex/auth/AuthController.java` — challenge/verify/session/logout endpoints.
- Modify `apps/api/src/main/java/com/projeto/cortex/auth/CurrentUserService.java` — explicit role only and active-user resolution.
- Delete `apps/api/src/main/java/com/projeto/cortex/auth/LoginRequest.java`, `CpfBloomFilter.java`, `CpfBloomFilterService.java` and `CpfFilterResponse.java` after frontend cutover.
- Delete `apps/api/src/main/java/com/projeto/cortex/auth/JwtAuthFilter.java` and `JwtService.java` after opaque-session tests pass.
- Modify `apps/api/src/main/java/com/projeto/cortex/colaboradores/Colaborador.java`, `ColaboradorRepository.java`, `ColaboradorImportService.java` and `CpfHasher.java` — HMAC identity sync, no auth digest in ontology, migration-only legacy SHA.
- Modify `apps/api/src/main/java/com/projeto/cortex/auth/PapelAcesso.java` — remove profile/group inference.
- Modify `apps/api/src/main/java/com/projeto/cortex/common/LocalCorsConfiguration.java` — credentials and exact production origins.
- Modify `apps/api/src/main/java/com/projeto/cortex/financeiro/ItemContratualController.java`, `PrevisaoFinanceiraController.java`, `apps/api/src/main/java/com/projeto/cortex/pdor/PdorController.java`, `apps/api/src/main/java/com/projeto/cortex/obras/ObrasRelacionadasService.java`, `apps/api/src/main/java/com/projeto/cortex/sync/SyncService.java`, `apps/api/src/main/java/com/projeto/cortex/intelligence/stavia/access/CortexStaviaAccessPolicy.java` and `apps/api/src/main/java/com/projeto/cortex/intelligence/stavia/knowledge/finance/FinancialForecastKnowledgeSource.java` — financial permission retrofit.

### Web files to create or modify

- Replace `apps/web/src/features/auth/authApi.ts`, `authService.ts`, `authSession.ts` and `LoginPage.tsx` with OTP/session/passkey flows.
- Delete `apps/web/src/features/auth/cpfFilter.ts` and purge `cortex.auth.cpfFilter`/raw-CPF session keys.
- Modify `apps/web/src/lib/api/apiClient.ts` — `credentials: 'include'`, CSRF header, no bearer token or silent CPF renewal.
- Create `apps/web/src/features/auth/passkeyApi.ts`, `webauthnCodec.ts`, `offlineVault.ts`, `offlineVault.types.ts`, `OfflineUnlockPage.tsx` and tests.
- Modify `apps/web/src/App.tsx`, `apps/web/src/lib/sync/useAutomaticSync.ts`, `syncEngine.ts` and `registerDevice.ts` — session state and vault gate instead of `session.token`.

### Documentation and deployment files

- Modify `.env.example`, `scripts/dev/run-api.sh`, `scripts/dev/run-api-docker.sh`, `scripts/dev/run-compose.sh`, `scripts/dev/smoke-stavia-sync.sh`, `docs/dev-runbook.md`, `docs/deploy-checklist.md`, `docs/architecture/autorizacao-alfa-beta.md` and `docs/adr/001-fase1.md`.

### Task 1: V27 Authentication Schema, PII Cleanup, and Explicit Role Hardening

**Files:**
- Create: `apps/api/src/main/resources/db/migration/V27__auth_security_and_pii_cleanup.sql`
- Create: `apps/api/src/test/java/com/projeto/cortex/auth/AuthSecurityMigrationTest.java`
- Test: `apps/api/src/test/java/com/projeto/cortex/pdor/PdorMysqlTestDatabase.java`

**Interfaces:**
- Consumes: V1-V26 schema, especially `colaborador(id, cpf_hash, papel_acesso, ativo, deletado_em)`, `cortex_evidencia_operacional` and `cortex_mapeamento_legado`.
- Produces: `auth_identity`, `auth_email_challenge`, `auth_rate_limit_bucket`, `auth_session`, `auth_webauthn_challenge`, `auth_webauthn_credential`, `auth_provisioning_receipt`; explicit non-null ALFA/BETA roles; legacy PII cleanup.

- [ ] **Step 1: Write the failing migration contract test**

```java
package com.projeto.cortex.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class AuthSecurityMigrationTest {

    private static final Path MIGRATION = Path.of(
            "src/main/resources/db/migration/V27__auth_security_and_pii_cleanup.sql"
    );

    @Test
    void createsSecurityTablesPreservesAlfaAndScrubsLegacyCpfEvidence() throws Exception {
        String sql = Files.readString(MIGRATION);

        assertThat(sql).contains("CREATE TABLE auth_identity");
        assertThat(sql).contains("CREATE TABLE auth_email_challenge");
        assertThat(sql).contains("CREATE TABLE auth_session");
        assertThat(sql).contains("CREATE TABLE auth_webauthn_credential");
        assertThat(sql).contains("WHERE papel_acesso IS NULL");
        assertThat(sql).doesNotContain("SET papel_acesso = 'ALFA'");
        assertThat(sql).contains("DELETE FROM cortex_evidencia_operacional");
        assertThat(sql).contains("JSON_REMOVE(snapshot_origem_json, '$.cpf_hash')");
        assertThat(sql).doesNotContain("DROP COLUMN cpf_hash");
    }
}
```

- [ ] **Step 2: Run the RED test**

Run:

```bash
cd apps/api
JAVA_HOME=$(/usr/libexec/java_home -v 21) mvn -Dtest=AuthSecurityMigrationTest test
```

Expected: FAIL because `V27__auth_security_and_pii_cleanup.sql` does not exist.

- [ ] **Step 3: Add the complete V27 migration**

```sql
-- Authentication identities are separate from the Academy mirror so verified
-- login e-mail and key rotation cannot be overwritten by source imports.
CREATE TABLE auth_identity (
    colaborador_id CHAR(36) NOT NULL,
    cpf_lookup_hmac CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NULL,
    cpf_lookup_key_id VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NULL,
    email_autenticacao VARCHAR(320) NULL,
    email_verificado_em DATETIME(6) NULL,
    email_fonte VARCHAR(32) NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDENTE',
    criado_em DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    atualizado_em DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
        ON UPDATE CURRENT_TIMESTAMP(6),
    versao_linha BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (colaborador_id),
    CONSTRAINT uq_auth_identity_cpf_lookup
        UNIQUE (cpf_lookup_key_id, cpf_lookup_hmac),
    CONSTRAINT fk_auth_identity_colaborador
        FOREIGN KEY (colaborador_id) REFERENCES colaborador(id),
    CONSTRAINT chk_auth_identity_status
        CHECK (status IN ('PENDENTE', 'ATIVA', 'BLOQUEADA'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE auth_email_challenge (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    colaborador_id CHAR(36) NULL,
    identifier_digest CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    codigo_digest CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    expira_em DATETIME(6) NOT NULL,
    tentativas SMALLINT NOT NULL DEFAULT 0,
    max_tentativas SMALLINT NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDENTE',
    criado_em DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    consumido_em DATETIME(6) NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_auth_email_challenge_colaborador
        FOREIGN KEY (colaborador_id) REFERENCES colaborador(id),
    CONSTRAINT chk_auth_email_challenge_status
        CHECK (status IN ('PENDENTE', 'CONSUMIDO', 'EXPIRADO', 'BLOQUEADO'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE INDEX idx_auth_email_challenge_identifier_time
    ON auth_email_challenge (identifier_digest, criado_em);
CREATE INDEX idx_auth_email_challenge_expiry
    ON auth_email_challenge (status, expira_em);

CREATE TABLE auth_rate_limit_bucket (
    bucket_key CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    janela_inicio DATETIME(6) NOT NULL,
    contador INT NOT NULL,
    bloqueado_ate DATETIME(6) NULL,
    atualizado_em DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
        ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (bucket_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE auth_session (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    colaborador_id CHAR(36) NOT NULL,
    token_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    csrf_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    criado_em DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    expira_em DATETIME(6) NOT NULL,
    visto_por_ultimo_em DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    revogado_em DATETIME(6) NULL,
    revogado_motivo VARCHAR(120) NULL,
    dispositivo_id CHAR(36) NULL,
    PRIMARY KEY (id),
    CONSTRAINT uq_auth_session_token_hash UNIQUE (token_hash),
    CONSTRAINT fk_auth_session_colaborador
        FOREIGN KEY (colaborador_id) REFERENCES colaborador(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE INDEX idx_auth_session_user_active
    ON auth_session (colaborador_id, revogado_em, expira_em);

CREATE TABLE auth_webauthn_challenge (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    colaborador_id CHAR(36) NULL,
    ceremony VARCHAR(20) NOT NULL,
    challenge_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    request_json JSON NOT NULL,
    criado_em DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    expira_em DATETIME(6) NOT NULL,
    consumido_em DATETIME(6) NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_auth_webauthn_challenge_colaborador
        FOREIGN KEY (colaborador_id) REFERENCES colaborador(id),
    CONSTRAINT chk_auth_webauthn_challenge_ceremony
        CHECK (ceremony IN ('REGISTRATION', 'AUTHENTICATION'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE auth_webauthn_credential (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    colaborador_id CHAR(36) NOT NULL,
    credential_id_hash CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    credential_id VARBINARY(1024) NOT NULL,
    user_handle VARBINARY(64) NOT NULL,
    public_key_cose BLOB NOT NULL,
    signature_count BIGINT NOT NULL DEFAULT 0,
    transports_json JSON NOT NULL,
    aaguid CHAR(36) NULL,
    discoverable TINYINT(1) NOT NULL DEFAULT 1,
    backed_up TINYINT(1) NOT NULL DEFAULT 0,
    nome VARCHAR(120) NULL,
    criado_em DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    usado_em DATETIME(6) NULL,
    revogado_em DATETIME(6) NULL,
    PRIMARY KEY (id),
    CONSTRAINT uq_auth_webauthn_credential_hash UNIQUE (credential_id_hash),
    CONSTRAINT fk_auth_webauthn_credential_colaborador
        FOREIGN KEY (colaborador_id) REFERENCES colaborador(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE INDEX idx_auth_webauthn_credential_user
    ON auth_webauthn_credential (colaborador_id, revogado_em);

CREATE TABLE auth_provisioning_receipt (
    arquivo_digest CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    processado_em DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    identidades_processadas INT NOT NULL,
    PRIMARY KEY (arquivo_digest)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Preserve every explicit ALFA. Only absent or invalid roles become BETA.
UPDATE colaborador
SET papel_acesso = 'BETA'
WHERE papel_acesso IS NULL
   OR papel_acesso NOT IN ('ALFA', 'BETA');

ALTER TABLE colaborador
    MODIFY papel_acesso VARCHAR(20) NOT NULL DEFAULT 'BETA';

-- Remove brute-forceable CPF digests duplicated into operational memory.
DELETE FROM cortex_evidencia_operacional
WHERE tipo_entidade = 'COLABORADOR'
  AND nome_campo = 'cpf_hash';

UPDATE cortex_mapeamento_legado
SET snapshot_origem_json = JSON_REMOVE(snapshot_origem_json, '$.cpf_hash')
WHERE JSON_CONTAINS_PATH(snapshot_origem_json, 'one', '$.cpf_hash');
```

- [ ] **Step 4: Run the contract test and the disposable MySQL migration suite**

Run:

```bash
cd apps/api
JAVA_HOME=$(/usr/libexec/java_home -v 21) mvn -Dtest=AuthSecurityMigrationTest test
JAVA_HOME=$(/usr/libexec/java_home -v 21) mvn -Dtest=PdorCw38386MysqlIntegrationTest test
```

Expected: both commands exit 0; Flyway applies V1-V27 without checksum changes to V1-V26.

- [ ] **Step 5: Commit the independently reviewable schema**

```bash
git add apps/api/src/main/resources/db/migration/V27__auth_security_and_pii_cleanup.sql \
  apps/api/src/test/java/com/projeto/cortex/auth/AuthSecurityMigrationTest.java
git commit -m "feat(auth): add secure identity schema"
```

### Task 2: Secret-Backed CPF HMAC and Academy Identity Upgrade

**Files:**
- Create: `apps/api/src/main/java/com/projeto/cortex/config/SecretMaterialLoader.java`
- Create: `apps/api/src/main/java/com/projeto/cortex/auth/identity/CpfNormalizer.java`
- Create: `apps/api/src/main/java/com/projeto/cortex/auth/identity/CpfLookupDigest.java`
- Create: `apps/api/src/main/java/com/projeto/cortex/auth/identity/CpfLookupDigestService.java`
- Create: `apps/api/src/main/java/com/projeto/cortex/auth/identity/HmacCpfLookupDigestService.java`
- Create: `apps/api/src/main/java/com/projeto/cortex/auth/identity/AuthIdentity.java`
- Create: `apps/api/src/main/java/com/projeto/cortex/auth/identity/AuthIdentityRepository.java`
- Modify: `apps/api/src/main/java/com/projeto/cortex/colaboradores/ColaboradorImportService.java`
- Modify: `apps/api/src/main/java/com/projeto/cortex/colaboradores/CpfHasher.java`
- Modify: `apps/api/src/main/resources/application.yml`
- Test: `apps/api/src/test/java/com/projeto/cortex/config/SecretMaterialLoaderTest.java`
- Test: `apps/api/src/test/java/com/projeto/cortex/auth/identity/HmacCpfLookupDigestServiceTest.java`
- Test: `apps/api/src/test/java/com/projeto/cortex/auth/identity/AuthIdentityRepositoryTest.java`
- Test: `apps/api/src/test/java/com/projeto/cortex/colaboradores/ColaboradorImportServiceTest.java`

**Interfaces:**
- Consumes: `auth_identity` from Task 1 and raw CPF held only in request/import memory.
- Produces: `CpfLookupDigestService.current(String): CpfLookupDigest`, `candidates(String): List<CpfLookupDigest>`, and `AuthIdentityRepository.findActiveByCpf(String): Optional<AuthIdentity>` with lazy legacy upgrade.

- [ ] **Step 1: Write RED tests for secret precedence and versioned HMAC**

```java
package com.projeto.cortex.auth.identity;

import com.projeto.cortex.colaboradores.CpfHasher;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HmacCpfLookupDigestServiceTest {

    @TempDir Path tempDir;

    @Test
    void readsMountedKeyAndProducesKeyedNonShaDigest() throws Exception {
        Path keyFile = tempDir.resolve("cpf-hmac-key");
        Files.writeString(keyFile, "test-only-cpf-hmac-key-with-32-bytes-minimum");

        HmacCpfLookupDigestService service = new HmacCpfLookupDigestService(
                "k2026-07", keyFile, null, null
        );

        CpfLookupDigest first = service.current("111.444.777-35");
        CpfLookupDigest second = service.current("11144477735");

        assertThat(first).isEqualTo(second);
        assertThat(first.keyId()).isEqualTo("k2026-07");
        assertThat(first.value()).hasSize(64);
        assertThat(first.value()).isNotEqualTo(CpfHasher.hashDeDigitos("11144477735"));
    }

    @Test
    void refusesMissingOrShortProductionKey() {
        assertThatThrownBy(() -> new HmacCpfLookupDigestService(
                "k2026-07", tempDir.resolve("missing"), null, null
        )).isInstanceOf(IllegalStateException.class);
    }
}
```

- [ ] **Step 2: Run the RED HMAC test**

Run:

```bash
cd apps/api
JAVA_HOME=$(/usr/libexec/java_home -v 21) mvn -Dtest=HmacCpfLookupDigestServiceTest test
```

Expected: FAIL because the identity package does not exist.

- [ ] **Step 3: Implement the focused interfaces and secret-file loader**

```java
package com.projeto.cortex.auth.identity;

public record CpfLookupDigest(String keyId, String value) {
    public CpfLookupDigest {
        if (keyId == null || keyId.isBlank() || value == null || value.length() != 64) {
            throw new IllegalArgumentException("Digest de CPF inválido.");
        }
    }
}
```

```java
package com.projeto.cortex.auth.identity;

import java.util.List;

public interface CpfLookupDigestService {
    CpfLookupDigest current(String cpfRaw);
    List<CpfLookupDigest> candidates(String cpfRaw);
}
```

```java
package com.projeto.cortex.auth.identity;

public record AuthIdentity(
        String colaboradorId,
        String nome,
        String emailAutenticacao,
        String papelAcesso
) {}
```

```java
package com.projeto.cortex.auth.identity;

public final class CpfNormalizer {
    private CpfNormalizer() {}

    public static String requireValid(String raw) {
        String digits = raw == null ? "" : raw.replaceAll("\\D", "");
        if (digits.length() != 11 || digits.chars().distinct().count() == 1) {
            throw new IllegalArgumentException("Identificador inválido.");
        }
        int first = checkDigit(digits, 9, 10);
        int second = checkDigit(digits, 10, 11);
        if (digits.charAt(9) - '0' != first || digits.charAt(10) - '0' != second) {
            throw new IllegalArgumentException("Identificador inválido.");
        }
        return digits;
    }

    private static int checkDigit(String digits, int length, int weight) {
        int sum = 0;
        for (int index = 0; index < length; index++) {
            sum += (digits.charAt(index) - '0') * (weight - index);
        }
        int value = 11 - (sum % 11);
        return value >= 10 ? 0 : value;
    }
}
```

```java
package com.projeto.cortex.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public final class SecretMaterialLoader {
    private SecretMaterialLoader() {}

    public static byte[] required(Path file, String inline, String label) {
        if (file != null && inline != null && !inline.isBlank()) {
            throw new IllegalStateException(label + " deve usar arquivo ou valor inline, nunca ambos.");
        }
        String value;
        try {
            value = file == null ? inline : Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new IllegalStateException("Não foi possível ler o secret file de " + label + ".", exception);
        }
        if (value == null || value.strip().length() < 32) {
            throw new IllegalStateException(label + " deve conter pelo menos 32 caracteres.");
        }
        return value.strip().getBytes(StandardCharsets.UTF_8);
    }
}
```

```java
package com.projeto.cortex.auth.identity;

import com.projeto.cortex.config.SecretMaterialLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HexFormat;
import java.util.List;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

public final class HmacCpfLookupDigestService implements CpfLookupDigestService {
    private final String currentKeyId;
    private final byte[] currentKey;
    private final String previousKeyId;
    private final byte[] previousKey;

    public HmacCpfLookupDigestService(
            String currentKeyId,
            Path currentKeyFile,
            String currentKeyInline,
            PreviousKey previous
    ) {
        this.currentKeyId = requireKeyId(currentKeyId);
        this.currentKey = SecretMaterialLoader.required(currentKeyFile, currentKeyInline, "CPF HMAC");
        this.previousKeyId = previous == null ? null : requireKeyId(previous.keyId());
        this.previousKey = previous == null ? null : SecretMaterialLoader.required(
                previous.file(), previous.inline(), "CPF HMAC anterior"
        );
    }

    @Override
    public CpfLookupDigest current(String cpfRaw) {
        return digest(currentKeyId, currentKey, CpfNormalizer.requireValid(cpfRaw));
    }

    @Override
    public List<CpfLookupDigest> candidates(String cpfRaw) {
        String cpf = CpfNormalizer.requireValid(cpfRaw);
        CpfLookupDigest active = digest(currentKeyId, currentKey, cpf);
        return previousKey == null
                ? List.of(active)
                : List.of(active, digest(previousKeyId, previousKey, cpf));
    }

    private CpfLookupDigest digest(String keyId, byte[] key, String cpf) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return new CpfLookupDigest(
                    keyId,
                    HexFormat.of().formatHex(mac.doFinal(cpf.getBytes(StandardCharsets.UTF_8)))
            );
        } catch (Exception exception) {
            throw new IllegalStateException("Falha ao calcular identificador protegido.", exception);
        }
    }

    private static String requireKeyId(String value) {
        if (value == null || !value.matches("[A-Za-z0-9._-]{1,32}")) {
            throw new IllegalStateException("Key ID de CPF HMAC inválido.");
        }
        return value;
    }

    public record PreviousKey(String keyId, Path file, String inline) {}
}
```

- [ ] **Step 4: Run GREEN HMAC tests**

Run:

```bash
cd apps/api
JAVA_HOME=$(/usr/libexec/java_home -v 21) mvn -Dtest=HmacCpfLookupDigestServiceTest,SecretMaterialLoaderTest test
```

Expected: PASS.

- [ ] **Step 5: Write the RED repository/import test for HMAC-first plus legacy lazy upgrade**

```java
@Test
void locatesLegacyIdentityWithoutAuthenticatingAndUpgradesToCurrentHmac() {
    when(digests.candidates("11144477735"))
            .thenReturn(List.of(new CpfLookupDigest("k2026-07", "a".repeat(64))));
    when(jdbc.query(anyString(), any(RowMapper.class), eq("k2026-07"), eq("a".repeat(64))))
            .thenReturn(List.of());
    when(jdbc.query(anyString(), any(RowMapper.class), eq(CpfHasher.hashDeDigitos("11144477735"))))
            .thenReturn(List.of(activeLegacyIdentity()));

    AuthIdentity identity = repository.findActiveByCpf("11144477735").orElseThrow();

    assertThat(identity.colaboradorId()).isEqualTo("alfa-1");
    verify(jdbc).update(
            contains("INSERT INTO auth_identity"),
            eq("alfa-1"), eq("a".repeat(64)), eq("k2026-07")
    );
}
```

- [ ] **Step 6: Implement HMAC-first lookup and make Academy import update only `auth_identity`**

```java
public Optional<AuthIdentity> findActiveByCpf(String cpfRaw) {
    for (CpfLookupDigest candidate : digestService.candidates(cpfRaw)) {
        Optional<AuthIdentity> current = findByDigest(candidate);
        if (current.isPresent()) {
            upgradeToCurrent(current.get().colaboradorId(), cpfRaw);
            return current;
        }
    }

    String digits = CpfNormalizer.requireValid(cpfRaw);
    Optional<AuthIdentity> legacy = findByLegacySha(CpfHasher.hashDeDigitos(digits));
    legacy.ifPresent(identity -> upgradeToCurrent(identity.colaboradorId(), digits));
    return legacy;
}

@Transactional
public void upsertAcademyIdentity(String colaboradorId, String cpfRaw, String academyEmail) {
    CpfLookupDigest digest = digestService.current(cpfRaw);
    jdbcTemplate.update("""
            INSERT INTO auth_identity (
                colaborador_id, cpf_lookup_hmac, cpf_lookup_key_id,
                email_autenticacao, email_verificado_em, email_fonte, status
            ) VALUES (?, ?, ?, NULLIF(TRIM(?), ''), NULL, 'ACADEMY', 'PENDENTE')
            ON DUPLICATE KEY UPDATE
                cpf_lookup_hmac = VALUES(cpf_lookup_hmac),
                cpf_lookup_key_id = VALUES(cpf_lookup_key_id),
                email_autenticacao = CASE
                    WHEN email_fonte = 'MANUAL_VERIFICADO' THEN email_autenticacao
                    ELSE COALESCE(NULLIF(TRIM(VALUES(email_autenticacao)), ''), email_autenticacao)
                END,
                versao_linha = versao_linha + 1
            """, colaboradorId, digest.value(), digest.keyId(), academyEmail);
}
```

In `ColaboradorImportService`, inject `AuthIdentityRepository`, call `upsertAcademyIdentity(...)` after each collaborator upsert, remove `fields.put("cpf_hash", ...)`, remove auth digests from `registrarMapeamentoLegado` snapshots, and exclude the keyed lookup digest from `gerarHash(UsuarioAcademy)`.

- [ ] **Step 7: Run focused import/repository tests**

Run:

```bash
cd apps/api
JAVA_HOME=$(/usr/libexec/java_home -v 21) mvn \
  -Dtest=AuthIdentityRepositoryTest,ColaboradorImportServiceTest,HmacCpfLookupDigestServiceTest test
```

Expected: PASS; assertions prove no `cpf_hash` or `cpf_lookup_hmac` is sent to operational memory.

- [ ] **Step 8: Commit the HMAC boundary**

```bash
git add apps/api/src/main/java/com/projeto/cortex/config \
  apps/api/src/main/java/com/projeto/cortex/auth/identity \
  apps/api/src/main/java/com/projeto/cortex/colaboradores \
  apps/api/src/main/resources/application.yml \
  apps/api/src/test/java/com/projeto/cortex/config \
  apps/api/src/test/java/com/projeto/cortex/auth/identity \
  apps/api/src/test/java/com/projeto/cortex/colaboradores
git commit -m "feat(auth): protect CPF lookup with versioned HMAC"
```

### Task 3: Secret-File Identity Provisioning and E-mail Providers

**Files:**
- Create: `apps/api/src/main/java/com/projeto/cortex/auth/identity/AuthIdentityProvisioningRunner.java`
- Create: `apps/api/src/main/java/com/projeto/cortex/auth/identity/ProvisioningManifest.java`
- Create: `apps/api/src/main/java/com/projeto/cortex/auth/identity/ProvisioningReceiptRepository.java`
- Create: `apps/api/src/main/java/com/projeto/cortex/email/EmailGateway.java`
- Create: `apps/api/src/main/java/com/projeto/cortex/email/EmailMessage.java`
- Create: `apps/api/src/main/java/com/projeto/cortex/email/FakeEmailGateway.java`
- Create: `apps/api/src/main/java/com/projeto/cortex/email/SmtpEmailGateway.java`
- Create: `apps/api/src/main/java/com/projeto/cortex/email/EmailConfiguration.java`
- Modify: `apps/api/pom.xml`
- Test: `apps/api/src/test/java/com/projeto/cortex/auth/identity/AuthIdentityProvisioningRunnerTest.java`
- Test: `apps/api/src/test/java/com/projeto/cortex/email/EmailConfigurationTest.java`

**Interfaces:**
- Consumes: `AuthIdentityRepository.upsertProvisionedIdentity(cpf,email,"MANUAL_VERIFICADO")` and mounted JSON referenced only by `CORTEX_AUTH_PROVISIONING_FILE`.
- Produces: `EmailGateway.send(EmailMessage): DeliveryReceipt`; idempotent `auth_provisioning_receipt` records; SMTP for deploy and fake capture for tests.

- [ ] **Step 1: Write RED provider/provisioning tests**

```java
@Test
void processesMountedManifestOnceWithoutLoggingItsContents() throws Exception {
    Path manifest = tempDir.resolve("bootstrap.json");
    Files.writeString(manifest, """
            {"version":1,"identities":[
              {"cpf":"11144477735","email":"alfa@example.invalid"}
            ]}
            """);
    AuthIdentityProvisioningRunner runner = runner(manifest);

    runner.run();
    runner.run();

    verify(identityRepository, times(1)).upsertProvisionedIdentity(
            "11144477735", "alfa@example.invalid", "MANUAL_VERIFICADO"
    );
    verify(receiptRepository, times(1)).record(anyString(), eq(1));
}

@Test
void productionRejectsFakeProvider() {
    assertThatThrownBy(() -> EmailConfiguration.validateProvider("fake", false))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("fake");
}
```

- [ ] **Step 2: Run RED tests**

```bash
cd apps/api
JAVA_HOME=$(/usr/libexec/java_home -v 21) mvn \
  -Dtest=AuthIdentityProvisioningRunnerTest,EmailConfigurationTest test
```

Expected: FAIL because provisioning and e-mail contracts do not exist.

- [ ] **Step 3: Add mail dependency and provider contract**

Add to `apps/api/pom.xml`:

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-mail</artifactId>
</dependency>
```

Create the complete provider-neutral records:

```java
package com.projeto.cortex.email;

public record EmailMessage(
        String recipient,
        String subject,
        String textBody,
        String idempotencyKey
) {}
```

```java
package com.projeto.cortex.email;

public interface EmailGateway {
    DeliveryReceipt send(EmailMessage message);

    record DeliveryReceipt(String provider, String messageId) {}
}
```

`FakeEmailGateway` must be annotated `@Profile({"local", "test"})` and retain messages only in an in-memory concurrent list. `SmtpEmailGateway` must construct `JavaMailSenderImpl` from host/port/user/TLS properties and obtain its password through `SecretMaterialLoader.required(passwordFile,passwordInline,"SMTP")`. Neither provider may log body or recipient.

- [ ] **Step 4: Implement the one-shot provisioning runner**

```java
public void run() {
    byte[] bytes = readManifestBytes(provisioningFile);
    String receipt = sha256(bytes);
    if (receiptRepository.exists(receipt)) {
        return;
    }
    ProvisioningManifest manifest = objectMapper.readValue(bytes, ProvisioningManifest.class);
    if (manifest.version() != 1 || manifest.identities() == null || manifest.identities().isEmpty()) {
        throw new IllegalStateException("Manifesto de provisionamento inválido.");
    }
    for (ProvisioningManifest.Identity identity : manifest.identities()) {
        identityRepository.upsertProvisionedIdentity(
                CpfNormalizer.requireValid(identity.cpf()),
                requireEmail(identity.email()),
                "MANUAL_VERIFICADO"
        );
    }
    receiptRepository.record(receipt, manifest.identities().size());
    Arrays.fill(bytes, (byte) 0);
}
```

Run this only when `CORTEX_AUTH_PROVISIONING_FILE` points to a mounted `0600` file and `CORTEX_AUTH_PROVISIONING_ENABLED=true`. The path is configuration; CPF/e-mail never appear in migration, command arguments or repository files. After success, the operator removes the secret mount; the receipt prevents replay.

- [ ] **Step 5: Run GREEN provider/provisioning tests**

```bash
cd apps/api
JAVA_HOME=$(/usr/libexec/java_home -v 21) mvn \
  -Dtest=AuthIdentityProvisioningRunnerTest,EmailConfigurationTest test
```

Expected: PASS; a context test with profile `prod` and provider `fake` must fail startup.

- [ ] **Step 6: Commit the deployable provider boundary**

```bash
git add apps/api/pom.xml \
  apps/api/src/main/java/com/projeto/cortex/auth/identity \
  apps/api/src/main/java/com/projeto/cortex/email \
  apps/api/src/test/java/com/projeto/cortex/auth/identity \
  apps/api/src/test/java/com/projeto/cortex/email
git commit -m "feat(auth): provision identities and send mail safely"
```

### Task 4: Generic E-mail OTP, Shared Rate Limits, and Revocable Cookie Sessions

**Files:**
- Create: `apps/api/src/main/java/com/projeto/cortex/auth/otp/EmailOtpChallengeService.java`
- Create: `apps/api/src/main/java/com/projeto/cortex/auth/otp/AuthRateLimiter.java`
- Create: `apps/api/src/main/java/com/projeto/cortex/auth/otp/OtpChallengeRequest.java`
- Create: `apps/api/src/main/java/com/projeto/cortex/auth/otp/OtpChallengeResponse.java`
- Create: `apps/api/src/main/java/com/projeto/cortex/auth/otp/OtpVerifyRequest.java`
- Create: `apps/api/src/main/java/com/projeto/cortex/auth/session/AuthSessionService.java`
- Create: `apps/api/src/main/java/com/projeto/cortex/auth/session/AuthCookieService.java`
- Create: `apps/api/src/main/java/com/projeto/cortex/auth/session/AuthSessionFilter.java`
- Create: `apps/api/src/main/java/com/projeto/cortex/auth/session/CsrfRequestFilter.java`
- Modify: `apps/api/src/main/java/com/projeto/cortex/auth/AuthController.java`
- Modify: `apps/api/src/main/java/com/projeto/cortex/auth/CurrentUserService.java`
- Modify: `apps/api/src/main/java/com/projeto/cortex/auth/PapelAcesso.java`
- Modify: `apps/api/src/main/java/com/projeto/cortex/common/LocalCorsConfiguration.java`
- Delete: `apps/api/src/main/java/com/projeto/cortex/auth/JwtAuthFilter.java`
- Delete: `apps/api/src/main/java/com/projeto/cortex/auth/JwtService.java`
- Test: `apps/api/src/test/java/com/projeto/cortex/auth/otp/EmailOtpChallengeServiceTest.java`
- Test: `apps/api/src/test/java/com/projeto/cortex/auth/session/AuthSessionFilterTest.java`
- Test: `apps/api/src/test/java/com/projeto/cortex/auth/AuthControllerMockMvcTest.java`

**Interfaces:**
- Consumes: `AuthIdentityRepository`, `EmailGateway`, V27 challenge/session/rate tables and explicit `PapelAcesso`.
- Produces: generic `POST /api/auth/email/challenges`, verify, `/api/auth/session`, `/api/auth/logout`; cookies `cortex_session` and `cortex_csrf`; request attribute `CurrentUserService.REQUEST_ATTRIBUTE_USER_ID` remains unchanged.

- [ ] **Step 1: Write RED OTP invariants**

```java
@Test
void validAndUnknownCpfReturnSamePublicResponse() {
    when(identities.findActiveByCpf("11144477735")).thenReturn(Optional.of(activeIdentity()));
    when(identities.findActiveByCpf("52998224725")).thenReturn(Optional.empty());

    OtpChallengeResponse valid = service.request("11144477735", "ip-bucket");
    OtpChallengeResponse unknown = service.request("52998224725", "ip-bucket-2");

    assertThat(valid.message()).isEqualTo(unknown.message());
    assertThat(valid.expiresInSeconds()).isEqualTo(unknown.expiresInSeconds());
    verify(emailGateway, times(1)).send(any());
}

@Test
void codeIsSingleUseAndAttemptLimited() {
    RequestedOtp requested = service.requestKnown(activeIdentity());
    assertThat(service.verify(requested.challengeId(), requested.code())).isPresent();
    assertThat(service.verify(requested.challengeId(), requested.code())).isEmpty();
}
```

- [ ] **Step 2: Run RED OTP/session tests**

```bash
cd apps/api
JAVA_HOME=$(/usr/libexec/java_home -v 21) mvn \
  -Dtest=EmailOtpChallengeServiceTest,AuthSessionFilterTest,AuthControllerMockMvcTest test
```

Expected: FAIL because OTP/session classes and routes do not exist.

- [ ] **Step 3: Implement OTP generation, digest and atomic verification**

Use `SecureRandom` for six digits, HMAC-SHA-256 with `CORTEX_AUTH_OTP_HMAC_KEY_FILE`, 600-second TTL, five attempts, five requests per 15-minute identifier/IP bucket. Verification must use one transaction and a guarded update:

```java
int consumed = jdbcTemplate.update("""
        UPDATE auth_email_challenge
        SET status = 'CONSUMIDO', consumido_em = CURRENT_TIMESTAMP(6)
        WHERE id = ?
          AND status = 'PENDENTE'
          AND expira_em > CURRENT_TIMESTAMP(6)
          AND tentativas < max_tentativas
          AND codigo_digest = ?
        """, challengeId, digest(code));
if (consumed != 1) {
    jdbcTemplate.update("""
            UPDATE auth_email_challenge
            SET tentativas = tentativas + 1,
                status = CASE WHEN tentativas + 1 >= max_tentativas
                              THEN 'BLOQUEADO' ELSE status END
            WHERE id = ? AND status = 'PENDENTE'
            """, challengeId);
    return Optional.empty();
}
return findConsumedIdentity(challengeId);
```

The public request always returns:

```java
new OtpChallengeResponse(
        publicChallengeId,
        600,
        "Se os dados estiverem aptos, enviaremos um código para o e-mail cadastrado."
)
```

- [ ] **Step 4: Implement opaque sessions, cookies and CSRF**

Generate 32 random bytes for both session and CSRF tokens. Store SHA-256 hashes in MySQL; return only cookies:

```java
ResponseCookie sessionCookie = ResponseCookie.from("cortex_session", rawSession)
        .httpOnly(true).secure(properties.secure())
        .sameSite(properties.sameSite()).path("/")
        .maxAge(properties.ttl()).build();
ResponseCookie csrfCookie = ResponseCookie.from("cortex_csrf", rawCsrf)
        .httpOnly(false).secure(properties.secure())
        .sameSite(properties.sameSite()).path("/")
        .maxAge(properties.ttl()).build();
```

`AuthSessionFilter` must join `auth_session` to `colaborador`, require `ativo=1`, `deletado_em IS NULL`, non-expired/non-revoked session, then set `cortex.authenticatedUserId`. `CsrfRequestFilter` must require `X-CSRF-Token` to match the `cortex_csrf` cookie in constant time for POST/PUT/PATCH/DELETE, excluding challenge/verify and health routes.

- [ ] **Step 5: Replace auth routes and explicit-role fallback**

```java
@PostMapping("/api/auth/email/challenges")
@ResponseStatus(HttpStatus.ACCEPTED)
public OtpChallengeResponse request(@RequestBody OtpChallengeRequest request,
                                    HttpServletRequest servletRequest) {
    return otpService.request(request == null ? null : request.cpf(),
            clientFingerprint.from(servletRequest));
}

@PostMapping("/api/auth/email/challenges/{id}/verify")
public LoginResponse verify(@PathVariable String id,
                            @RequestBody OtpVerifyRequest request,
                            HttpServletResponse response) {
    AuthenticatedIdentity identity = otpService.verify(id, request.code())
            .orElseThrow(() -> new ResponseStatusException(
                    HttpStatus.UNAUTHORIZED, "Código inválido ou expirado."));
    return sessionService.start(identity, response);
}
```

Change `CurrentUserService.papelAcesso` to select only `papel_acesso`; delete `PapelAcesso.fromPerfilGrupo`. Unknown values deny access. Do not alter any explicit ALFA row.

- [ ] **Step 6: Enable credentialed exact-origin CORS and run GREEN tests**

Set `.allowCredentials(true)` and require `CORTEX_CORS_ALLOWED_ORIGINS` in non-local profiles; do not use private-network wildcard patterns in production.

```bash
cd apps/api
JAVA_HOME=$(/usr/libexec/java_home -v 21) mvn \
  -Dtest=EmailOtpChallengeServiceTest,AuthSessionFilterTest,AuthControllerMockMvcTest,CurrentUserServiceAuthorizationTest,LocalCorsConfigurationTest test
```

Expected: PASS; unknown/known challenges are indistinguishable, replay fails, inactive collaborator receives 401, CSRF mismatch receives 403, explicit ALFA/BETA tests pass.

- [ ] **Step 7: Commit the online authentication boundary**

```bash
git add apps/api/src/main/java/com/projeto/cortex/auth \
  apps/api/src/main/java/com/projeto/cortex/common/LocalCorsConfiguration.java \
  apps/api/src/main/resources/application.yml \
  apps/api/src/test/java/com/projeto/cortex/auth \
  apps/api/src/test/java/com/projeto/cortex/common/LocalCorsConfigurationTest.java
git commit -m "feat(auth): require OTP and revocable cookie sessions"
```

### Task 5: Frontend OTP Session Cutover and Legacy Credential Purge

**Files:**
- Modify: `apps/web/src/features/auth/authApi.ts`
- Modify: `apps/web/src/features/auth/authService.ts`
- Modify: `apps/web/src/features/auth/authSession.ts`
- Modify: `apps/web/src/features/auth/LoginPage.tsx`
- Modify: `apps/web/src/lib/api/apiClient.ts`
- Modify: `apps/web/src/App.tsx`
- Modify: `apps/web/src/lib/sync/useAutomaticSync.ts`
- Modify: `apps/web/src/lib/sync/syncEngine.ts`
- Modify: `apps/web/src/lib/sync/registerDevice.ts`
- Delete: `apps/web/src/features/auth/cpfFilter.ts`
- Test: `apps/web/src/features/auth/authService.test.ts`
- Test: `apps/web/src/features/auth/authSession.test.ts`
- Test: `apps/web/src/lib/api/apiClient.test.ts`

**Interfaces:**
- Consumes: Task 4 challenge/verify/session/logout endpoints and `cortex_session`/`cortex_csrf` cookies.
- Produces: in-memory `AuthSessionState`, two-step CPF/code UI, `apiFetch` with credentials+CSRF, and `hasOnlineSession()` for sync.

- [ ] **Step 1: Write RED tests proving no CPF/JWT persistence or silent re-login**

```ts
it("purges legacy CPF, JWT and Bloom material", async () => {
  localStorage.setItem("cortex.auth.cpfFilter", "legacy");
  localStorage.setItem("cortex.auth.sessao", JSON.stringify({ cpf: "11144477735", token: "jwt" }));
  await initializeAuthSession();
  expect(localStorage.getItem("cortex.auth.cpfFilter")).toBeNull();
  expect(localStorage.getItem("cortex.auth.sessao")).toBeNull();
});

it("uses cookies and csrf instead of Authorization", async () => {
  document.cookie = "cortex_csrf=test-csrf; path=/";
  await apiFetch("/obras", { method: "POST", body: "{}" });
  expect(fetch).toHaveBeenCalledWith(
    "/api/obras",
    expect.objectContaining({
      credentials: "include",
      headers: expect.objectContaining({ "X-CSRF-Token": "test-csrf" }),
    }),
  );
  expect(JSON.stringify(vi.mocked(fetch).mock.calls)).not.toContain("Authorization");
});
```

- [ ] **Step 2: Run RED web tests**

```bash
cd apps/web
npm test -- src/features/auth/authService.test.ts src/features/auth/authSession.test.ts src/lib/api/apiClient.test.ts
```

Expected: FAIL because cookie-session APIs do not exist.

- [ ] **Step 3: Replace frontend auth contracts**

```ts
export async function requestEmailCode(cpf: string): Promise<OtpChallenge> {
  return readJson(await apiFetch("/auth/email/challenges", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ cpf: onlyDigits(cpf) }),
  }));
}

export async function verifyEmailCode(
  challengeId: string,
  code: string,
): Promise<AuthProfile> {
  return readJson(await apiFetch(
    `/auth/email/challenges/${encodeURIComponent(challengeId)}/verify`,
    {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ code: onlyDigits(code) }),
    },
  ));
}

export async function fetchSession(): Promise<AuthProfile | null> {
  const response = await apiFetch("/auth/session");
  if (response.status === 401) return null;
  return readJson(response);
}
```

`authSession.ts` keeps the profile in module memory, dispatches the existing session-change event, and exposes `hasOnlineSession(): boolean`. Its initializer must remove `cortex.auth.sessao` and `cortex.auth.cpfFilter` before calling `/auth/session`. `LoginPage.tsx` has explicit `identify` and `verify` states, generic challenge copy and a resend timer; it never displays a destination returned by the server.

- [ ] **Step 4: Replace bearer handling in `apiFetch`**

```ts
function csrfCookie(): string | null {
  const item = document.cookie.split("; ").find((entry) => entry.startsWith("cortex_csrf="));
  return item ? decodeURIComponent(item.slice("cortex_csrf=".length)) : null;
}

export async function apiFetch(path: string, options: ApiRequestOptions = {}): Promise<Response> {
  const method = (options.method ?? "GET").toUpperCase();
  const csrf = ["POST", "PUT", "PATCH", "DELETE"].includes(method) ? csrfCookie() : null;
  return rawFetch(path, {
    ...options,
    credentials: "include",
    headers: {
      ...(csrf ? { "X-CSRF-Token": csrf } : {}),
      ...options.headers,
    },
  });
}
```

Remove `reautenticarComCpf`. Change sync/register-device guards from `getSession()?.token` to `hasOnlineSession()` and keep the existing honest offline error copy.

- [ ] **Step 5: Run GREEN tests, lint and build**

```bash
cd apps/web
npm test -- src/features/auth/authService.test.ts src/features/auth/authSession.test.ts src/lib/api/apiClient.test.ts
npm run lint
npm run build
```

Expected: all exit 0; built JS contains neither `/auth/cpf-filter` nor `senha: cpfDigits`.

- [ ] **Step 6: Commit the frontend cutover**

```bash
git add apps/web/src/features/auth apps/web/src/lib/api/apiClient.ts \
  apps/web/src/lib/api/apiClient.test.ts apps/web/src/App.tsx \
  apps/web/src/lib/sync
git commit -m "feat(web): use OTP cookie sessions"
```

### Task 6: WebAuthn Passkeys and PRF-Only Offline Unlock

**Files:**
- Modify: `apps/api/pom.xml`
- Create: `apps/api/src/main/java/com/projeto/cortex/auth/webauthn/WebAuthnConfiguration.java`
- Create: `apps/api/src/main/java/com/projeto/cortex/auth/webauthn/WebAuthnCredentialRepository.java`
- Create: `apps/api/src/main/java/com/projeto/cortex/auth/webauthn/WebAuthnService.java`
- Create: `apps/api/src/main/java/com/projeto/cortex/auth/webauthn/WebAuthnController.java`
- Test: `apps/api/src/test/java/com/projeto/cortex/auth/webauthn/WebAuthnServiceTest.java`
- Create: `apps/web/src/features/auth/passkeyApi.ts`
- Create: `apps/web/src/features/auth/webauthnCodec.ts`
- Create: `apps/web/src/features/auth/offlineVault.ts`
- Create: `apps/web/src/features/auth/offlineVault.types.ts`
- Create: `apps/web/src/features/auth/OfflineUnlockPage.tsx`
- Modify: `apps/web/src/App.tsx`
- Test: `apps/web/src/features/auth/offlineVault.test.ts`

**Interfaces:**
- Consumes: authenticated cookie session for registration; V27 WebAuthn tables; exact RP configuration; browser WebAuthn API.
- Produces: server-validated passkey login and `OfflineVault.unlock(): Promise<'UNLOCKED'|'PRF_UNAVAILABLE'>`; no non-PRF offline fallback.

- [ ] **Step 1: Pin the security-fixed WebAuthn dependency and write RED ceremony tests**

Add the officially released version containing the YSA-2026-02 ownership fix:

```xml
<dependency>
    <groupId>com.yubico</groupId>
    <artifactId>webauthn-server-core</artifactId>
    <version>2.9.0</version>
</dependency>
```

```java
@Test
void rejectsAssertionWhoseCredentialBelongsToAnotherCollaborator() {
    AssertionResponse assertion = fixture.assertionOwnedBy("beta-2");
    assertThatThrownBy(() -> service.finishAuthentication("alfa-1", assertion))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("Credencial inválida");
}

@Test
void consumesChallengeOnlyOnceAndChecksConfiguredOrigin() {
    RegistrationResponse response = fixture.registration("https://cortex.example.invalid");
    service.finishRegistration("challenge-1", response);
    assertThatThrownBy(() -> service.finishRegistration("challenge-1", response))
            .isInstanceOf(ResponseStatusException.class);
}
```

- [ ] **Step 2: Run RED WebAuthn test**

```bash
cd apps/api
JAVA_HOME=$(/usr/libexec/java_home -v 21) mvn -Dtest=WebAuthnServiceTest test
```

Expected: FAIL because the WebAuthn adapter is absent.

- [ ] **Step 3: Implement the library adapter and four endpoints**

Build one `RelyingParty` from `CORTEX_AUTH_WEBAUTHN_RP_ID`, `CORTEX_AUTH_WEBAUTHN_RP_NAME` and the exact CSV `CORTEX_AUTH_WEBAUTHN_ALLOWED_ORIGINS`. Use `UserVerificationRequirement.REQUIRED`, resident keys, `AttestationConveyancePreference.NONE`, 300-second single-use DB challenges and repository-backed credential ownership/counters.

```java
@PostMapping("/api/auth/passkeys/registration/options")
public CeremonyOptions startRegistration() {
    return service.startRegistration(currentUser.requireUserId());
}

@PostMapping("/api/auth/passkeys/registration/verify")
public PasskeySummary finishRegistration(@RequestBody CeremonyResult result) {
    return service.finishRegistration(currentUser.requireUserId(), result);
}

@PostMapping("/api/auth/passkeys/authentication/options")
public CeremonyOptions startAuthentication() {
    return service.startDiscoverableAuthentication();
}

@PostMapping("/api/auth/passkeys/authentication/verify")
public LoginResponse finishAuthentication(@RequestBody CeremonyResult result,
                                          HttpServletResponse response) {
    return sessionService.start(service.finishAuthentication(result), response);
}
```

- [ ] **Step 4: Write RED PRF vault tests**

```ts
it("returns PRF_UNAVAILABLE and never calls a CPF or Bloom fallback", async () => {
  vi.spyOn(navigator.credentials, "get").mockResolvedValue(assertionWithoutPrf());
  await expect(unlockOfflineVault(vaultMetadata())).resolves.toBe("PRF_UNAVAILABLE");
  expect(fetch).not.toHaveBeenCalled();
  expect(localStorage.getItem("cortex.auth.cpfFilter")).toBeNull();
});

it("derives AES-GCM key from PRF and decrypts the signed local grant", async () => {
  vi.spyOn(navigator.credentials, "get").mockResolvedValue(assertionWithPrf(prfBytes));
  await expect(unlockOfflineVault(encryptedVaultFixture(prfBytes))).resolves.toBe("UNLOCKED");
});
```

- [ ] **Step 5: Implement PRF-only offline vault**

Store only public metadata (`credentialId`, random 32-byte PRF salt, AES-GCM IV, ciphertext) in IndexedDB. During online enrollment, derive a non-extractable AES-GCM key via HKDF from `getClientExtensionResults().prf.results.first`, then encrypt an authorization manifest containing collaborator ID, role, granted worksite IDs, issue time and server-signed expiry. Offline unlock generates a local random challenge and requests the enrolled credential with:

```ts
const assertion = await navigator.credentials.get({
  publicKey: {
    challenge: crypto.getRandomValues(new Uint8Array(32)),
    rpId: metadata.rpId,
    allowCredentials: [{ id: fromBase64Url(metadata.credentialId), type: "public-key" }],
    userVerification: "required",
    extensions: { prf: { eval: { first: fromBase64Url(metadata.prfSalt) } } },
  },
});
```

If PRF output is missing, render `OfflineUnlockPage` with “Este navegador exige conexão para entrar” and no alternate credential input. `App.tsx` must not render authenticated routes offline until the encrypted grant decrypts and its signed expiry is valid.

- [ ] **Step 6: Run GREEN backend/web passkey tests**

```bash
cd apps/api
JAVA_HOME=$(/usr/libexec/java_home -v 21) mvn -Dtest=WebAuthnServiceTest test
cd ../web
npm test -- src/features/auth/offlineVault.test.ts
npm run build
```

Expected: all exit 0; credential-owner mismatch, origin mismatch and replay fail; PRF absence remains online-only.

- [ ] **Step 7: Commit passkeys and offline unlock**

```bash
git add apps/api/pom.xml apps/api/src/main/java/com/projeto/cortex/auth/webauthn \
  apps/api/src/test/java/com/projeto/cortex/auth/webauthn \
  apps/web/src/features/auth apps/web/src/App.tsx
git commit -m "feat(auth): add passkeys and PRF offline unlock"
```

### Task 7: V28 Explicit Financial Grants and ALFA-Safe Authorization Service

**Files:**
- Create: `apps/api/src/main/resources/db/migration/V28__financial_permissions.sql`
- Create: `apps/api/src/main/java/com/projeto/cortex/financeiro/access/FinancialPermission.java`
- Create: `apps/api/src/main/java/com/projeto/cortex/financeiro/access/FinancialAccessService.java`
- Create: `apps/api/src/main/java/com/projeto/cortex/financeiro/access/FinancialGrantService.java`
- Create: `apps/api/src/main/java/com/projeto/cortex/financeiro/access/FinancialGrantRepository.java`
- Create: `apps/api/src/main/java/com/projeto/cortex/financeiro/access/FinancialGrantController.java`
- Create: `apps/api/src/main/java/com/projeto/cortex/financeiro/access/FinancialGrantRequest.java`
- Create: `apps/api/src/main/java/com/projeto/cortex/financeiro/access/FinancialGrantResponse.java`
- Test: `apps/api/src/test/java/com/projeto/cortex/financeiro/access/FinancialPermissionMigrationTest.java`
- Test: `apps/api/src/test/java/com/projeto/cortex/financeiro/access/FinancialAccessServiceTest.java`
- Test: `apps/api/src/test/java/com/projeto/cortex/financeiro/access/FinancialGrantControllerMockMvcTest.java`

**Interfaces:**
- Consumes: explicit ALFA/BETA role and active `vinculo_colaborador_obra` from `CurrentUserService`.
- Produces: `requirePermission(String obraId, FinancialPermission)`, `hasPermission(String userId,String obraId,FinancialPermission)` and `allowedObraIds(String userId,FinancialPermission)`; ALFA implicit, BETA explicit.

- [ ] **Step 1: Write RED authorization tests**

```java
@Test
void alfaHasEveryFinancialPermissionWithoutGrantRows() {
    when(currentUser.isAlfa("alfa-1")).thenReturn(true);
    assertThat(service.hasPermission(
            "alfa-1", "obra-1", FinancialPermission.FINANCEIRO_ADMINISTRAR
    )).isTrue();
    verifyNoInteractions(grantRepository);
}

@Test
void betaNeedsBothWorksiteLinkAndExactActiveGrant() {
    when(currentUser.isAlfa("beta-1")).thenReturn(false);
    when(currentUser.podeAcessarObra("beta-1", "obra-1")).thenReturn(true);
    when(grantRepository.existsActive(
            "beta-1", "obra-1", FinancialPermission.FINANCEIRO_VISUALIZAR
    )).thenReturn(true);
    assertThat(service.hasPermission(
            "beta-1", "obra-1", FinancialPermission.FINANCEIRO_VISUALIZAR
    )).isTrue();
    assertThat(service.hasPermission(
            "beta-1", "obra-2", FinancialPermission.FINANCEIRO_VISUALIZAR
    )).isFalse();
}
```

- [ ] **Step 2: Run RED grant tests**

```bash
cd apps/api
JAVA_HOME=$(/usr/libexec/java_home -v 21) mvn \
  -Dtest=FinancialPermissionMigrationTest,FinancialAccessServiceTest,FinancialGrantControllerMockMvcTest test
```

Expected: FAIL because V28 and financial-access classes do not exist.

- [ ] **Step 3: Add the complete V28 migration**

```sql
CREATE TABLE permissao_financeira_colaborador (
    id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    colaborador_id CHAR(36) NOT NULL,
    obra_id CHAR(36) NOT NULL,
    permissao VARCHAR(40) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'ATIVA',
    concedido_em DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    concedido_por CHAR(36) NOT NULL,
    revogado_em DATETIME(6) NULL,
    revogado_por CHAR(36) NULL,
    justificativa VARCHAR(500) NOT NULL,
    criado_em DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    atualizado_em DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
        ON UPDATE CURRENT_TIMESTAMP(6),
    versao_linha BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    CONSTRAINT uq_permissao_financeira_colaborador
        UNIQUE (colaborador_id, obra_id, permissao),
    CONSTRAINT fk_permissao_financeira_colaborador
        FOREIGN KEY (colaborador_id) REFERENCES colaborador(id),
    CONSTRAINT fk_permissao_financeira_obra
        FOREIGN KEY (obra_id) REFERENCES obra(id),
    CONSTRAINT fk_permissao_financeira_concedido_por
        FOREIGN KEY (concedido_por) REFERENCES colaborador(id),
    CONSTRAINT fk_permissao_financeira_revogado_por
        FOREIGN KEY (revogado_por) REFERENCES colaborador(id),
    CONSTRAINT chk_permissao_financeira_status
        CHECK (status IN ('ATIVA', 'REVOGADA')),
    CONSTRAINT chk_permissao_financeira_tipo
        CHECK (permissao IN (
            'FINANCEIRO_VISUALIZAR',
            'FINANCEIRO_OPERAR',
            'FINANCEIRO_APROVAR',
            'FINANCEIRO_ADMINISTRAR'
        ))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE INDEX idx_permissao_financeira_user_status
    ON permissao_financeira_colaborador (colaborador_id, status, permissao);
CREATE INDEX idx_permissao_financeira_obra_status
    ON permissao_financeira_colaborador (obra_id, status, permissao);
```

There is deliberately no BETA backfill: granting finance because somebody previously saw an RDO would violate the explicit-grant requirement. ALFA remains implicit through application logic.

- [ ] **Step 4: Implement the exact permission API**

```java
package com.projeto.cortex.financeiro.access;

public enum FinancialPermission {
    FINANCEIRO_VISUALIZAR,
    FINANCEIRO_OPERAR,
    FINANCEIRO_APROVAR,
    FINANCEIRO_ADMINISTRAR
}
```

```java
public boolean hasPermission(String userId, String obraId, FinancialPermission permission) {
    if (userId == null || userId.isBlank() || obraId == null || obraId.isBlank()) {
        return false;
    }
    if (currentUserService.isAlfa(userId)) {
        return true;
    }
    return currentUserService.podeAcessarObra(userId, obraId)
            && grantRepository.existsActive(userId, obraId, permission);
}

public void requirePermission(String obraId, FinancialPermission permission) {
    String userId = currentUserService.requireUserId();
    if (!hasPermission(userId, obraId, permission)) {
        throw new ResponseStatusException(
                HttpStatus.FORBIDDEN,
                "Você não possui a permissão financeira exigida nesta obra."
        );
    }
}
```

`FinancialGrantService` must require ALFA, validate active collaborator/obra, upsert idempotently, preserve grant/revocation history columns, require a nonblank justification, register `PERMISSAO_FINANCEIRA_CONCEDIDA`/`REVOGADA` events and maintain `AUTORIZADO_FINANCEIRO_EM` in `CortexOperationalMemoryService`. Controller paths are `GET/POST /api/obras/{obraId}/permissoes-financeiras` and `DELETE /api/obras/{obraId}/permissoes-financeiras/{colaboradorId}/{permissao}`.

- [ ] **Step 5: Run GREEN grant tests and MySQL migration**

```bash
cd apps/api
JAVA_HOME=$(/usr/libexec/java_home -v 21) mvn \
  -Dtest=FinancialPermissionMigrationTest,FinancialAccessServiceTest,FinancialGrantControllerMockMvcTest,PdorCw38386MysqlIntegrationTest test
```

Expected: PASS; V1-V28 apply; ALFA succeeds with zero grant rows; BETA without an exact active grant receives 403.

- [ ] **Step 6: Commit V28 and grant services**

```bash
git add apps/api/src/main/resources/db/migration/V28__financial_permissions.sql \
  apps/api/src/main/java/com/projeto/cortex/financeiro/access \
  apps/api/src/test/java/com/projeto/cortex/financeiro/access
git commit -m "feat(finance): add explicit worksite grants"
```

### Task 8: Retrofit Financial Authorization Across REST, Home, Sync, and StavIA

**Files:**
- Modify: `apps/api/src/main/java/com/projeto/cortex/financeiro/ItemContratualController.java`
- Modify: `apps/api/src/main/java/com/projeto/cortex/financeiro/PrevisaoFinanceiraController.java`
- Modify: `apps/api/src/main/java/com/projeto/cortex/pdor/PdorController.java`
- Modify: `apps/api/src/main/java/com/projeto/cortex/obras/ObrasRelacionadasService.java`
- Modify: `apps/api/src/main/java/com/projeto/cortex/sync/SyncService.java`
- Modify: `apps/api/src/main/java/com/projeto/cortex/intelligence/stavia/access/CortexStaviaAccessPolicy.java`
- Modify: `apps/api/src/main/java/com/projeto/cortex/intelligence/stavia/knowledge/finance/FinancialForecastKnowledgeSource.java`
- Test: `apps/api/src/test/java/com/projeto/cortex/financeiro/FinancialControllerAuthorizationTest.java`
- Test: `apps/api/src/test/java/com/projeto/cortex/sync/SyncPullFinancialScopeTest.java`
- Test: `apps/api/src/test/java/com/projeto/cortex/intelligence/stavia/FinancialKnowledgeAuthorizationTest.java`

**Interfaces:**
- Consumes: `FinancialAccessService` from Task 7.
- Produces: zero financial values/evidence/events for unauthorized BETA users across every output channel.

- [ ] **Step 1: Write RED cross-channel leakage tests**

```java
@Test
void linkedBetaWithoutFinanceGrantCannotReadForecast() throws Exception {
    doThrow(new ResponseStatusException(HttpStatus.FORBIDDEN))
            .when(financialAccess).requirePermission(
                    "obra-1", FinancialPermission.FINANCEIRO_VISUALIZAR
            );
    mockMvc.perform(get("/api/obras/obra-1/previsao-financeira/atual"))
            .andExpect(status().isForbidden());
    verifyNoInteractions(previsaoService);
}

@Test
void syncOmitsFinancialEventsForBetaWithoutGrant() {
    when(financialAccess.allowedObraIds("beta-1", FINANCEIRO_VISUALIZAR))
            .thenReturn(Set.of());
    assertThat(sync.pull("beta-1", 0, 100).eventos())
            .noneMatch(event -> Set.of("PREVISAO_FINANCEIRA", "PDOR", "ITEM_CONTRATUAL")
                    .contains(event.entidadeTipo()));
}
```

- [ ] **Step 2: Run RED retrofit tests**

```bash
cd apps/api
JAVA_HOME=$(/usr/libexec/java_home -v 21) mvn \
  -Dtest=FinancialControllerAuthorizationTest,SyncPullFinancialScopeTest,FinancialKnowledgeAuthorizationTest test
```

Expected: FAIL because worksite access alone still exposes financial data.

- [ ] **Step 3: Apply permission checks before service/repository calls**

Use `FINANCEIRO_VISUALIZAR` for GET/current/history/report, `FINANCEIRO_OPERAR` for create/update purchases/invoices/payments, `FINANCEIRO_APROVAR` for approval transitions, and `FINANCEIRO_ADMINISTRAR` for financial configuration/recalculation.

```java
@GetMapping("/api/obras/{obraId}/previsao-financeira/atual")
public PrevisaoFinanceiraResponse atual(@PathVariable String obraId) {
    financialAccess.requirePermission(obraId, FinancialPermission.FINANCEIRO_VISUALIZAR);
    return service.buscarAtual(obraId);
}
```

In `ObrasRelacionadasService`, return `valorContratual=null` unless `hasPermission(userId,obraId,FINANCEIRO_VISUALIZAR)`. Do not use zero, because zero would fabricate a financial value.

- [ ] **Step 4: Split sync scope by data classification**

Retain current worksite filtering for operational entities, then add a second predicate:

```java
private boolean canPullEvent(String userId, SyncEvent event) {
    if (!FINANCIAL_ENTITY_TYPES.contains(event.entidadeTipo())) {
        return event.obraId() == null || currentUserService.podeAcessarObra(userId, event.obraId());
    }
    return event.obraId() != null && financialAccess.hasPermission(
            userId, event.obraId(), FinancialPermission.FINANCEIRO_VISUALIZAR
    );
}
```

Apply the same predicate before response serialization and snapshot hydration; filtering only in the UI is insufficient.

- [ ] **Step 5: Make StavIA financial intent permission-aware**

Add `FINANCEIRO_VISUALIZAR` to the request permission set only for granted worksite scope. `FinancialForecastKnowledgeSource.supports` must require it, and `retrieve` must return no evidence before querying if absent:

```java
if (!request.permissions().contains(FinancialPermission.FINANCEIRO_VISUALIZAR.name())) {
    return List.of();
}
```

- [ ] **Step 6: Run GREEN retrofit and regression tests**

```bash
cd apps/api
JAVA_HOME=$(/usr/libexec/java_home -v 21) mvn \
  -Dtest=FinancialControllerAuthorizationTest,SyncPullFinancialScopeTest,FinancialKnowledgeAuthorizationTest,CurrentUserServiceAuthorizationTest,SyncServiceSecurityTest,StaviaAccessWiringTest test
```

Expected: PASS; BETA without grants gets 403/empty financial scope while normal RDO/worksite access remains unchanged.

- [ ] **Step 7: Commit the cross-channel authorization retrofit**

```bash
git add apps/api/src/main/java/com/projeto/cortex/financeiro \
  apps/api/src/main/java/com/projeto/cortex/pdor/PdorController.java \
  apps/api/src/main/java/com/projeto/cortex/obras/ObrasRelacionadasService.java \
  apps/api/src/main/java/com/projeto/cortex/sync/SyncService.java \
  apps/api/src/main/java/com/projeto/cortex/intelligence/stavia \
  apps/api/src/test/java/com/projeto/cortex/financeiro \
  apps/api/src/test/java/com/projeto/cortex/sync \
  apps/api/src/test/java/com/projeto/cortex/intelligence/stavia
git commit -m "fix(security): enforce financial grants everywhere"
```

### Task 9: Deploy Readiness, Secret Files, Smoke Flow, and Final Evidence

**Files:**
- Create: `apps/api/src/main/java/com/projeto/cortex/auth/AuthReadinessIndicator.java`
- Test: `apps/api/src/test/java/com/projeto/cortex/auth/AuthReadinessIndicatorTest.java`
- Modify: `.env.example`
- Modify: `apps/api/src/main/resources/application.yml`
- Modify: `apps/api/src/main/resources/application-local.yml`
- Modify: `scripts/dev/run-api.sh`
- Modify: `scripts/dev/run-api-docker.sh`
- Modify: `scripts/dev/run-compose.sh`
- Modify: `scripts/dev/smoke-stavia-sync.sh`
- Modify: `docs/dev-runbook.md`
- Modify: `docs/deploy-checklist.md`
- Modify: `docs/architecture/autorizacao-alfa-beta.md`
- Modify: `docs/adr/001-fase1.md`

**Interfaces:**
- Consumes: Tasks 1-8 and mounted secret-file paths.
- Produces: fail-closed production startup/readiness, reproducible fake-OTP local smoke, documented deployment variables and final verification evidence.

- [ ] **Step 1: Write the RED readiness test**

```java
@Test
void refusesOtpCutoverWithoutVerifiedActiveAlfa() {
    when(jdbc.queryForObject(contains("papel_acesso = 'ALFA'"), eq(Integer.class)))
            .thenReturn(0);
    assertThatThrownBy(readiness::verifyProductionReadiness)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("ALFA");
}

@Test
void acceptsRealProviderSecretsExactOriginsAndVerifiedAlfa() {
    when(jdbc.queryForObject(contains("papel_acesso = 'ALFA'"), eq(Integer.class)))
            .thenReturn(1);
    readinessWith("smtp", true, true, true).verifyProductionReadiness();
}
```

- [ ] **Step 2: Run RED readiness test**

```bash
cd apps/api
JAVA_HOME=$(/usr/libexec/java_home -v 21) mvn -Dtest=AuthReadinessIndicatorTest test
```

Expected: FAIL because the readiness validator does not exist.

- [ ] **Step 3: Implement fail-closed readiness**

```java
public void verifyProductionReadiness() {
    if (!productionProfile) return;
    require(provider.equals("smtp"), "Produção exige EmailGateway SMTP.");
    require(secretFilesPresent, "Produção exige secrets montados por arquivo.");
    require(exactHttpsOrigins, "Produção exige origens HTTPS exatas.");
    Integer verifiedAlfas = jdbcTemplate.queryForObject("""
            SELECT COUNT(*)
            FROM colaborador c
            JOIN auth_identity ai ON ai.colaborador_id = c.id
            WHERE c.ativo = 1
              AND c.deletado_em IS NULL
              AND c.papel_acesso = 'ALFA'
              AND ai.status = 'ATIVA'
              AND ai.email_verificado_em IS NOT NULL
            """, Integer.class);
    require(verifiedAlfas != null && verifiedAlfas > 0,
            "OTP não pode iniciar sem ao menos um ALFA ativo e verificado.");
}
```

- [ ] **Step 4: Document exact deployment inputs**

Add these names with empty values to `.env.example`; production uses only the `_FILE` forms:

```dotenv
CORTEX_AUTH_CPF_HMAC_KEY_ID=k2026-07
CORTEX_AUTH_CPF_HMAC_KEY_FILE=
CORTEX_AUTH_OTP_HMAC_KEY_FILE=
CORTEX_AUTH_SESSION_HASH_KEY_FILE=
CORTEX_AUTH_PROVISIONING_ENABLED=false
CORTEX_AUTH_PROVISIONING_FILE=
CORTEX_EMAIL_PROVIDER=fake
CORTEX_SMTP_HOST=
CORTEX_SMTP_PORT=587
CORTEX_SMTP_USERNAME=
CORTEX_SMTP_PASSWORD_FILE=
CORTEX_SMTP_STARTTLS=true
CORTEX_AUTH_WEBAUTHN_RP_ID=localhost
CORTEX_AUTH_WEBAUTHN_RP_NAME=Stavias Cortex
CORTEX_AUTH_WEBAUTHN_ALLOWED_ORIGINS=http://localhost:5173
CORTEX_AUTH_COOKIE_SECURE=false
CORTEX_AUTH_COOKIE_SAME_SITE=Lax
CORTEX_CORS_ALLOWED_ORIGINS=http://localhost:5173
```

`docs/deploy-checklist.md` must state that production requires HTTPS, `Secure` cookies, SMTP, secret files, exact origins, backup/restore, V27/V28 Flyway success, one verified ALFA, fake provider disabled and a completed OTP+passkey smoke. `docs/dev-runbook.md` must show a local fake-provider flow without printing codes in general logs; the test harness reads captured fake mail through an injected test bean, not a production HTTP endpoint.

- [ ] **Step 5: Update smoke script to authenticate rather than mint JWT**

The smoke starts with the `test` profile, provisions the reserved `example.invalid` identity from a temporary `0600` manifest, asks for an OTP, obtains the code from the test-only fake gateway fixture, verifies it into a cookie jar, extracts the CSRF cookie, and uses:

```bash
curl -fsS -b "$COOKIE_JAR" -c "$COOKIE_JAR" \
  -H "X-CSRF-Token: $CSRF_TOKEN" \
  -H 'Content-Type: application/json' \
  -X POST "$API_URL/api/sync/push" \
  --data-binary @"$PUSH_PAYLOAD"
```

Remove Node-generated JWT and the production JWT secret requirement from the smoke. Assert BETA without V28 grant receives 403 for finance, then grant as ALFA and assert 200.

- [ ] **Step 6: Run the complete verification matrix**

```bash
cd apps/api
JAVA_HOME=$(/usr/libexec/java_home -v 21) mvn clean test
cd ../web
npm test
npm run lint
npm run build
cd ../..
bash scripts/dev/smoke-stavia-sync.sh
docker build -t cortex-api:auth-v28 apps/api
git diff --check
git grep -n -E "cpf-filter|senhaRaw|senha: cpfDigits|CORTEX_AUTH_JWT_SECRET" -- \
  apps/api/src/main apps/web/src scripts/dev docs .env.example
```

Expected: Maven, Vitest, lint, build, smoke, Docker build and `git diff --check` exit 0. The final `git grep` exits 1 with no matches outside historical immutable migrations/validation documents explicitly excluded from deploy artifacts.

- [ ] **Step 7: Record aggregate database evidence without PII**

```sql
SELECT
    SUM(c.ativo = 1 AND c.deletado_em IS NULL AND c.papel_acesso = 'ALFA') AS active_alfa,
    SUM(c.ativo = 1 AND c.deletado_em IS NULL AND c.papel_acesso = 'ALFA'
        AND ai.email_verificado_em IS NOT NULL AND ai.status = 'ATIVA') AS verified_alfa,
    SUM(c.ativo = 1 AND c.deletado_em IS NULL
        AND ai.cpf_lookup_hmac IS NOT NULL) AS hmac_covered
FROM colaborador c
LEFT JOIN auth_identity ai ON ai.colaborador_id = c.id;

SELECT COUNT(*) AS legacy_cpf_evidence
FROM cortex_evidencia_operacional
WHERE nome_campo IN ('cpf_hash', 'cpf_lookup_hmac');
```

Expected before production cutover: `active_alfa >= 1`, `verified_alfa >= 1`, `hmac_covered` equals the active collaborator count, and `legacy_cpf_evidence = 0`. Do not print IDs, hashes, e-mails or masked CPF values.

- [ ] **Step 8: Commit deployment evidence and documentation**

```bash
git add .env.example apps/api/src/main/resources \
  apps/api/src/main/java/com/projeto/cortex/auth/AuthReadinessIndicator.java \
  apps/api/src/test/java/com/projeto/cortex/auth/AuthReadinessIndicatorTest.java \
  scripts/dev docs
git commit -m "docs(deploy): require secure auth readiness"
```

## Completion Gate

- [ ] V27 and V28 are the only new migrations and apply on a fresh MySQL 8.4 schema.
- [ ] Every previously explicit ALFA remains ALFA; runtime profile/group inference is absent.
- [ ] At least one active ALFA has a verified authentication e-mail before OTP cutover.
- [ ] No CPF SHA/HMAC remains in operational evidence or legacy snapshot JSON.
- [ ] Known and unknown CPF challenge responses are indistinguishable.
- [ ] OTP replay, expired OTP, excessive attempts, revoked sessions, inactive users, CSRF mismatch, wrong RP origin and wrong credential owner all fail closed.
- [ ] Browser storage contains no raw CPF, OTP, JWT or Bloom filter.
- [ ] PRF-unavailable browsers are online-only and receive no fallback credential path.
- [ ] BETA without an exact active V28 grant receives no financial REST response, Home value, sync event or StavIA evidence.
- [ ] JDK 21 Maven suite, web suite, lint, build, smoke, Docker build and `git diff --check` all pass with fresh output.
