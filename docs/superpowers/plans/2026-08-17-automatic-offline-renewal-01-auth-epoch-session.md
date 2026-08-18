# Automatic Offline Renewal — Backend Auth Epoch and Session Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make PostgreSQL `auth_identity.auth_epoch` the global factor-revocation authority, bind every new online session to that epoch and an opaque generation marker, close password/passkey authentication races transactionally, and allow `/api/auth/offline-grant` to issue only from the exact still-current session generation.

**Architecture:** This is plan **1 of 3** for the approved automatic offline renewal design. It changes only the backend authentication authority and HTTP contracts; plan 2 owns the encrypted browser capsule and automatic renewal scheduler, and plan 3 owns legacy operational-namespace reconciliation and release acceptance. PostgreSQL V89 performs a rollout-safe cutover from the V88 password-owned epoch while leaving old sessions nullable and therefore fail-closed; application services verify factors outside database locks, then a short transaction re-reads identity and factor versions, records any WebAuthn counter change, and inserts the epoch-bound session atomically.

**Tech Stack:** Java 21, Spring Boot, Spring MVC filters, Spring JDBC, Spring transactions, Flyway, PostgreSQL 18, MySQL compatibility migrations, Yubico WebAuthn, JUnit 5, AssertJ, Mockito, Testcontainers, Maven Surefire/Failsafe.

**Spec:** `docs/superpowers/specs/2026-08-17-automatic-offline-grant-renewal-design.md`

## Global Constraints

- This file is backend plan **1 of 3**. Do not implement IndexedDB capsule stores, browser cryptography, automatic renewal scheduling, old namespace replay, UI removal, browser acceptance, or deployment monitoring here.
- Preserve `POST /api/auth/offline-grant` with an empty body; never accept CPF, password, passkey material, or an offline grant as online authorization.
- Preserve the existing cookie HttpOnly boundary, client-instance proof, CSRF triple, `Cache-Control: no-store`, current role/worksite authorization, and signed grant TTL of at most seven days.
- `authEpoch` is global to `auth_identity`, not a password-only counter. Password reset, passkey revocation, identity blocking, collaborator deactivation, and collaborator deletion advance the same monotonic value.
- Passkey registration preserves the current epoch and current session so the client can request a grant and persist/re-read its PRF vault before declaring offline readiness.
- Sessions created before V89 retain `auth_epoch IS NULL` and `session_marker IS NULL`; never backfill them. Repository resolution must reject them.
- The public `sessionMarker` is opaque and non-secret, remains stable for reloads using the same cookie, and changes on every new authentication, including a new login by the same collaborator.
- Factor cryptography stays outside row-lock transactions. The commit transaction only locks and compares the factor version, revocation state, identity epoch, and active eligibility before inserting the session.
- A direct epoch bump must invalidate the prior cookie even if an explicit `revokeAllByCollaboratorId` call is absent; session resolution is the authoritative defense.
- V89 must coexist safely with a V88 application during the migration-before-Render rollout window. Keep `auth_password_credential.auth_epoch` and bridge old V88 password increments into the new global epoch in this release; remove compatibility state only in a later migration after the old revision cannot serve traffic.
- The cutover default is epoch 2, not 1, so an identity inserted by V88 after V89 is applied cannot recreate the pre-cutover generation even when its first password row is an epoch-1 INSERT that emits no update trigger.
- Never log or serialize raw session tokens, CSRF tokens, password hashes, CPF, WebAuthn credential bytes, signed grant payloads, or offline key material.
- Do not weaken the PostgreSQL release gates: update the required schema version from 88 to 89 everywhere it is asserted.
- Keep MySQL compilation and its existing integration suite honest. Add the analogous nullable session/global-epoch columns rather than inventing a constant epoch in Java.
- Plan 1 does **not** produce the PWA signer keyring, key status metadata, `retiredCutoff`, `notAfter`, or release manifest consumed by grant verification. Plan 2 owns its own reviewed build-time keyring source and must not infer it from backend runtime configuration or from an offline-grant envelope.
- Every behavior change follows RED → GREEN → focused regression → commit. Do not combine unrelated tasks in one commit.

---

## File Structure and Locked Boundaries

### Files created

- `apps/api/src/main/resources/db/migration-postgresql/V89__global_auth_epoch_and_session_generation.sql` — PostgreSQL cutover, V88 bridge, global revocation triggers, nullable legacy-session columns, and WebAuthn row version.
- `apps/api/src/main/resources/db/migration/V46__global_auth_epoch_and_session_generation.sql` — equivalent legacy MySQL schema plus identity/collaborator lifecycle revocation triggers required by the shared session repository.
- `apps/api/src/main/java/com/projeto/cortex/auth/session/VerifiedAuthentication.java` — sealed, redacted factor snapshot shared by password/passkey session commit.
- `apps/api/src/main/java/com/projeto/cortex/auth/session/CurrentSessionIdentity.java` — identity name/role plus global epoch rebuilt from the row lock used by direct OTP/activation issuance.
- `apps/api/src/main/java/com/projeto/cortex/auth/session/AuthenticatedSessionIssue.java` — couples the authenticated identity and one-time issued session for controller/cookie/profile handling.
- `apps/api/src/main/java/com/projeto/cortex/auth/session/AuthenticationCommitService.java` — database-neutral CAS boundary used by password and passkey controllers.
- `apps/api/src/main/java/com/projeto/cortex/auth/session/PostgresqlAuthenticationCommitService.java` — short PostgreSQL transaction that locks factor/identity state and creates the session by CAS.
- `apps/api/src/main/java/com/projeto/cortex/auth/session/MysqlAuthenticationCommitService.java` — MySQL implementation of the same CAS boundary; supports passkey commits against the shared schema and fails closed if handed an unavailable password factor.
- `apps/api/src/main/java/com/projeto/cortex/auth/identity/AuthIdentityEpochRepository.java` — database-neutral application contract for explicitly advancing the global epoch.
- `apps/api/src/main/java/com/projeto/cortex/auth/identity/PostgresqlAuthIdentityEpochRepository.java` — PostgreSQL `UPDATE ... RETURNING` epoch adapter, active only under `postgresql-common`.
- `apps/api/src/main/java/com/projeto/cortex/auth/identity/MysqlAuthIdentityEpochRepository.java` — MySQL 8.4 `SELECT ... FOR UPDATE` plus checked-update epoch adapter, active outside `postgresql-common`.
- `apps/api/src/main/java/com/projeto/cortex/auth/password/PasswordCredentialSnapshot.java` — password hash plus credential version and global epoch observed before Argon2 verification.
- `apps/api/src/main/java/com/projeto/cortex/auth/webauthn/WebAuthnCredentialAuthenticationSnapshot.java` — credential bytes plus record ID/owner/version/epoch captured by the lookup that feeds the Yubico proof, before cryptographic verification.
- `apps/api/src/main/java/com/projeto/cortex/auth/webauthn/WebAuthnSnapshotCredentialRepository.java` — one-snapshot Yubico `CredentialRepository` used only for a single authentication finish call.
- `apps/api/src/main/java/com/projeto/cortex/auth/webauthn/WebAuthnRegistrationSessionBinding.java` — exact session ID, marker, owner, and epoch committed into a registration challenge.
- `apps/api/src/main/java/com/projeto/cortex/auth/webauthn/WebAuthnSqlDialect.java` — the three SQL fragments that differ between PostgreSQL JSONB/interval/qualified-lock syntax and MySQL JSON/TIMESTAMPADD/unqualified-lock syntax.
- `apps/api/src/main/java/com/projeto/cortex/auth/webauthn/PostgresqlWebAuthnSqlDialect.java` — PostgreSQL challenge/credential insert SQL, active only under `postgresql-common`.
- `apps/api/src/main/java/com/projeto/cortex/auth/webauthn/MysqlWebAuthnSqlDialect.java` — MySQL 8.4 challenge/credential insert SQL, active outside `postgresql-common`.
- `apps/api/src/main/java/com/projeto/cortex/auth/webauthn/PasskeyRevocationService.java` — self-owned transactional passkey revocation, epoch bump, and session revocation.
- `apps/api/src/test/java/com/projeto/cortex/auth/postgresql/PostgresqlV89GlobalAuthEpochUpgradeIT.java` — V88 → V89 migration and rolling-cutover proof.
- `apps/api/src/test/java/com/projeto/cortex/auth/session/MysqlV46GlobalAuthEpochUpgradeIT.java` — real MySQL 8.4 V45 → V46 migration proof with the exact epoch-2 cutover seed.
- `apps/api/src/test/java/com/projeto/cortex/auth/postgresql/PostgresqlAuthenticationSessionCasIT.java` — real PostgreSQL password/passkey CAS and rollback proof.
- `apps/api/src/test/java/com/projeto/cortex/auth/session/MysqlAuthenticationSessionCasIT.java` — real MySQL 8.4 passkey/session CAS and rollback proof.
- `apps/api/src/test/java/com/projeto/cortex/auth/session/AuthenticationCommitServiceProfileTest.java` — exact one-bean wiring proof for PostgreSQL and MySQL profiles.
- `apps/api/src/test/java/com/projeto/cortex/auth/identity/AuthIdentityEpochRepositoryProfileTest.java` — dialect wiring, mandatory-transaction, and MySQL checked-update contract proof.
- `apps/api/src/test/java/com/projeto/cortex/auth/webauthn/WebAuthnSqlDialectProfileTest.java` — exact one-dialect-bean and parameter-order proof for PostgreSQL/MySQL WebAuthn persistence.
- `apps/api/src/test/java/com/projeto/cortex/config/MavenDatabaseItProfileSelectionTest.java` — XML-level proof that PostgreSQL and MySQL Failsafe selections are disjoint.
- `apps/api/src/test/java/com/projeto/cortex/auth/offline/PostgresqlOfflineGrantLifecycleIT.java` — Spring/MockMvc/PostgreSQL cookie, client proof, CSRF, reset/revocation, and signed-grant lifecycle.
- `apps/api/src/test/java/com/projeto/cortex/auth/offline/MysqlOfflineGrantLifecycleIT.java` — real MySQL 8.4 proof that blocking, deactivation, and deletion advance the global epoch and invalidate the old cookie/grant boundary.

### Existing files modified

- `apps/api/src/main/java/com/projeto/cortex/auth/password/PasswordCredentialRepository.java`
- `apps/api/src/main/java/com/projeto/cortex/auth/password/PostgresqlPasswordCredentialRepository.java`
- `apps/api/src/main/java/com/projeto/cortex/auth/password/PasswordAuthenticationService.java`
- `apps/api/src/main/java/com/projeto/cortex/auth/password/PasswordSetupService.java`
- `apps/api/src/main/java/com/projeto/cortex/auth/session/AuthSessionRepository.java`
- `apps/api/src/main/java/com/projeto/cortex/auth/session/AuthSessionService.java`
- `apps/api/src/main/java/com/projeto/cortex/auth/session/IssuedAuthSession.java`
- `apps/api/src/main/java/com/projeto/cortex/auth/session/ResolvedAuthSession.java`
- `apps/api/src/main/java/com/projeto/cortex/auth/session/PostgresqlAuthSessionRepository.java`
- `apps/api/src/main/java/com/projeto/cortex/auth/session/MysqlAuthSessionRepository.java`
- `apps/api/src/main/java/com/projeto/cortex/auth/session/AuthSessionProfileResolver.java`
- `apps/api/src/main/java/com/projeto/cortex/auth/activation/PostgresqlActivationSessionProfileResolver.java`
- `apps/api/src/main/java/com/projeto/cortex/auth/AuthSessionResponse.java`
- `apps/api/src/main/java/com/projeto/cortex/auth/AuthController.java`
- `apps/api/src/main/java/com/projeto/cortex/auth/CurrentUserService.java`
- `apps/api/src/main/java/com/projeto/cortex/auth/webauthn/WebAuthnCredentialRepository.java`
- `apps/api/src/main/java/com/projeto/cortex/auth/webauthn/StoredWebAuthnChallenge.java`
- `apps/api/src/main/java/com/projeto/cortex/auth/webauthn/WebAuthnCeremonyEngine.java`
- `apps/api/src/main/java/com/projeto/cortex/auth/webauthn/YubicoWebAuthnCeremonyEngine.java`
- `apps/api/src/main/java/com/projeto/cortex/auth/webauthn/WebAuthnConfiguration.java`
- `apps/api/src/main/java/com/projeto/cortex/auth/webauthn/WebAuthnService.java`
- `apps/api/src/main/java/com/projeto/cortex/auth/webauthn/WebAuthnController.java`
- `apps/api/src/main/java/com/projeto/cortex/auth/webauthn/PasskeySummary.java`
- `apps/api/src/main/java/com/projeto/cortex/auth/offline/OfflineGrantController.java`
- `apps/api/src/main/java/com/projeto/cortex/auth/offline/OfflineGrantService.java`
- `apps/api/src/main/java/com/projeto/cortex/auth/offline/OfflineGrantSubject.java`
- `apps/api/src/main/java/com/projeto/cortex/auth/offline/OfflineGrantSubjectRepository.java`
- `apps/api/src/main/java/com/projeto/cortex/config/PostgresqlSchemaVersion.java`
- `apps/api/src/main/resources/application-postgresql-common.yml`
- `apps/api/pom.xml`
- `.github/workflows/api-ci.yml`
- `.github/workflows/production.yml`
- `scripts/security/test-production-publication.sh`
- `scripts/security/test-production-workflow-contract.sh`
- `scripts/security/test-production-workflow-contract-regressions.sh`
- Existing unit/IT fixtures that construct `IssuedAuthSession`, `ResolvedAuthSession`, or `AuthSessionResponse`.
- Existing activation, clean-start, V61-upgrade, OTP, Academy-login, and PDOR
  fixtures that invoke session issuance or the issued-profile resolver.

### Stable interfaces produced by this plan

```java
public sealed interface VerifiedAuthentication {
    AuthenticatedIdentity identity();
    String credentialId();
    long credentialVersion();
    long authEpoch();

    record Password(
            AuthenticatedIdentity identity,
            String credentialId,
            long credentialVersion,
            long authEpoch
    ) implements VerifiedAuthentication {}

    record Passkey(
            AuthenticatedIdentity identity,
            String credentialId,
            long credentialVersion,
            long authEpoch,
            long signatureCount,
            boolean backedUp
    ) implements VerifiedAuthentication {}
}
```

```java
public record AuthenticatedSessionIssue(
        AuthenticatedIdentity identity,
        IssuedAuthSession session
) {}
```

```java
public record CurrentSessionIdentity(
        AuthenticatedIdentity identity,
        long authEpoch
) {}
```

```java
public interface AuthenticationCommitService {
    Optional<AuthenticatedSessionIssue> issue(
            VerifiedAuthentication verified,
            ClientInstanceProof clientInstance
    );
}
```

```java
public interface AuthIdentityEpochRepository {
    long advance(String collaboratorId);
}
```

`PostgresqlAuthIdentityEpochRepository` is active under
`@Profile("postgresql-common")` and owns the PostgreSQL-only
`UPDATE ... RETURNING` statement. `MysqlAuthIdentityEpochRepository` is active
under `@Profile("!postgresql-common")` and owns a same-transaction
`SELECT ... FOR UPDATE` followed by an update-count-checked `UPDATE`. Both
implementations require an existing transaction with
`Propagation.MANDATORY`; callers never select a dialect or calculate the next
epoch themselves, and exactly one implementation bean exists in either
supported runtime.

`PostgresqlAuthenticationCommitService` is active under
`@Profile("postgresql-common")`; `MysqlAuthenticationCommitService` is active
under `@Profile("!postgresql-common")`. Both implement the exact interface and
perform factor/identity/session CAS in the database dialect they own. The
shared MySQL schema through V46 has no `auth_password_credential`, so the MySQL
implementation supports the passkey variant and rejects a password variant
without issuing a session; it must never emulate a password row or accept an
epoch supplied only by Java.

```java
public record IssuedAuthSession(
        String sessionId,
        String sessionToken,
        String csrfToken,
        String sessionMarker,
        long authEpoch,
        Instant expiresAt
) {}
```

```java
public record ResolvedAuthSession(
        String sessionId,
        String collaboratorId,
        String collaboratorName,
        PapelAcesso role,
        Instant expiresAt,
        String csrfHash,
        String clientInstanceHash,
        String sessionMarker,
        long authEpoch
) {}
```

```java
public interface AuthSessionRepository {
    Optional<CurrentSessionIdentity> lockCurrentIdentityForSessionIssue(
            String collaboratorId
    );

    Instant create(
            String sessionId,
            String collaboratorId,
            String tokenHash,
            String csrfHash,
            String clientInstanceHash,
            String sessionMarker,
            long authEpoch,
            int ttlSeconds
    );
}
```

```java
public class AuthSessionService {
    public Optional<AuthenticatedSessionIssue> issueCurrent(
            AuthenticatedIdentity identity,
            ClientInstanceProof clientInstance
    );

    public Optional<AuthenticatedSessionIssue> issueCurrentForActivation(
            AuthenticatedIdentity identity,
            ClientInstanceProof clientInstance
    );

    IssuedAuthSession issueVerified(
            AuthenticatedIdentity identity,
            ClientInstanceProof clientInstance,
            long authEpoch
    );
}
```

`issueCurrent(...)` treats its input identity only as the pre-lock expected
owner. It rebuilds name and role from `lockCurrentIdentityForSessionIssue(...)`,
accepts the locked ALFA or BETA role used by ordinary authentication, returns
that locked identity together with the session, and holds the
active-identity/epoch lock through the insert. `issueCurrentForActivation(...)`
uses the same locked row and transaction but requires the **locked** role to be
ALFA before it inserts anything; a stale ALFA snapshot whose row became BETA
returns empty and creates no session. The package-private
`issueVerified(...)` is called only by the same-package CAS service after it has
locked and revalidated the factor and identity.

---

### Task 1: Add the rollout-safe V89/V46 global epoch schema

**Files:**
- Create: `apps/api/src/main/resources/db/migration-postgresql/V89__global_auth_epoch_and_session_generation.sql`
- Create: `apps/api/src/main/resources/db/migration/V46__global_auth_epoch_and_session_generation.sql`
- Create: `apps/api/src/test/java/com/projeto/cortex/auth/postgresql/PostgresqlV89GlobalAuthEpochUpgradeIT.java`
- Create: `apps/api/src/test/java/com/projeto/cortex/auth/session/MysqlV46GlobalAuthEpochUpgradeIT.java`
- Create: `apps/api/src/test/java/com/projeto/cortex/config/MavenDatabaseItProfileSelectionTest.java`
- Modify: `apps/api/pom.xml:171-210`
- Modify: `apps/api/src/test/java/com/projeto/cortex/auth/password/PasswordSecurityMigrationTest.java:32-53`
- Modify: `apps/api/src/test/java/com/projeto/cortex/common/PostgresqlActivationReadinessTest.java`
- Modify: `apps/api/src/test/java/com/projeto/cortex/postgresql/PostgresqlCleanStartFlowIT.java`
- Modify: `apps/api/src/main/java/com/projeto/cortex/config/PostgresqlSchemaVersion.java:3-8`
- Modify: `apps/api/src/main/resources/application-postgresql-common.yml:15-22`
- Modify: `apps/api/src/test/java/com/projeto/cortex/config/PostgresqlSchemaReadinessGuardTest.java`
- Modify: `apps/api/src/test/java/com/projeto/cortex/config/PostgresqlRuntimeReadinessGuardTest.java`
- Modify: `apps/api/src/test/java/com/projeto/cortex/config/PostgresqlModeConfigurationGuardTest.java`
- Modify: `apps/api/src/test/java/com/projeto/cortex/config/PostgresqlFoundationContractTest.java`
- Modify: `apps/api/src/test/java/com/projeto/cortex/config/PostgresqlProfileModesContractTest.java`
- Modify: `apps/api/src/test/java/com/projeto/cortex/config/PostgresqlEffectiveConfigurationTest.java`

**Interfaces:**
- Consumes: V44 PostgreSQL baseline, V61 client-instance binding, V87 password tables, and V88 `auth_password_credential.auth_epoch` plus its two revocation triggers.
- Produces: `auth_identity.auth_epoch NOT NULL`, nullable `auth_session.auth_epoch/session_marker`, `auth_webauthn_credential.versao_linha`, nullable all-or-none registration-session binding columns on `auth_webauthn_challenge`, V88-to-V89 compatibility bridge, PostgreSQL and MySQL lifecycle triggers that advance the epoch on identity blocking/collaborator deactivation/deletion, required PostgreSQL schema version `89`, and disjoint Failsafe profiles: `postgresql-it` excludes every `*MysqlIT`, while `mysql-it` selects only real non-skipped MySQL 8.4 tests.

- [ ] **Step 1: Write the V89 migration unit contract in RED**

Extend `PasswordSecurityMigrationTest` with an exact resource assertion:

```java
@Test
void v89MovesEpochAuthorityToIdentityWithoutBackfillingLegacySessions()
        throws Exception {
    String sql;
    try (var stream = getClass().getResourceAsStream(
            "/db/migration-postgresql/"
                    + "V89__global_auth_epoch_and_session_generation.sql"
    )) {
        assertThat(stream).isNotNull();
        sql = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
    }

    assertThat(sql)
            .contains("ALTER TABLE auth_identity")
            .contains("ADD COLUMN auth_epoch bigint")
            .contains("GREATEST")
            .contains("auth_password_credential")
            .contains("ADD COLUMN session_marker char(43)")
            .contains("ADD COLUMN auth_epoch bigint")
            .contains("ADD COLUMN versao_linha bigint NOT NULL DEFAULT 0")
            .contains("ADD COLUMN registration_session_id varchar(36)")
            .contains("ADD COLUMN registration_session_marker char(43)")
            .contains("ADD COLUMN registration_auth_epoch bigint")
            .doesNotContain("UPDATE auth_session SET auth_epoch")
            .doesNotContain("UPDATE auth_session SET session_marker");
}
```

- [ ] **Step 2: Run the unit test and verify the missing resource failure**

Run:

```bash
REPO_ROOT="$(git rev-parse --show-toplevel)"
cd "$REPO_ROOT/apps/api"
./mvnw --batch-mode -Dtest=PasswordSecurityMigrationTest test
```

Expected: FAIL because `V89__global_auth_epoch_and_session_generation.sql` is absent.

- [ ] **Step 3: Write the real V88 → V89 PostgreSQL migration IT in RED**

In `PostgresqlV89GlobalAuthEpochUpgradeIT`, start PostgreSQL 18, migrate with `target("88")`, insert four identities, then migrate without a target. Use fixtures with: password epoch 7; password epoch 1; no password and active passkey; no password and already-revoked passkey. Insert one active legacy session and one unconsumed registration challenge before V89.

Core assertions:

```java
assertThat(epoch(jdbc, passwordEpochSevenId)).isEqualTo(8L);
assertThat(epoch(jdbc, passwordEpochOneId)).isEqualTo(2L);
assertThat(epoch(jdbc, passkeyOnlyId)).isEqualTo(2L);
assertThat(epoch(jdbc, revokedPasskeyId)).isEqualTo(2L);

Map<String, Object> legacy = jdbc.queryForMap(
        "SELECT auth_epoch, session_marker FROM auth_session WHERE id = ?",
        legacySessionId
);
assertThat(legacy.get("auth_epoch")).isNull();
assertThat(legacy.get("session_marker")).isNull();

Map<String, Object> legacyChallenge = jdbc.queryForMap(
        """
        SELECT registration_session_id,
               registration_session_marker,
               registration_auth_epoch
        FROM auth_webauthn_challenge WHERE id = ?
        """,
        legacyChallengeId
);
assertThat(legacyChallenge.values()).containsOnlyNulls();

assertThat(jdbc.queryForObject(
        "SELECT versao_linha FROM auth_webauthn_credential WHERE id = ?",
        Long.class,
        revokedPasskeyRecordId
)).isZero();
```

Also update the old V88 password epoch in a transaction after V89 and assert that the bridge advances `auth_identity.auth_epoch` by exactly one. This proves compatibility with the old application during the migration-before-deploy interval.

Then simulate the more dangerous INSERT path during that same rolling window:
after V89 is already applied, execute the exact V88 identity INSERT without an
`auth_epoch` column and the exact V88 first-password INSERT with
`auth_password_credential.auth_epoch = 1`. Assert the new identity receives
global epoch 2 from the V89 default even though the UPDATE bridge never fires,
and that a passkey-only identity inserted by the V88 shape also starts at 2.
Issue a legacy epoch-1 grant fixture and prove the post-cutover subject resolves
epoch 2, so the old vault cannot consume a capsule issued by the new backend.
This test must use the V88 SQL shape, not a V89-aware repository helper.

Use three additional active identities to exercise the global lifecycle triggers directly after V89: change one identity from `ATIVA` to `BLOQUEADA`, set one collaborator from active to inactive, and set one collaborator's `deletado_em`. Each isolated transition must advance that identity's epoch by exactly one even when no password credential exists. Keep the bridge case separate so the assertions do not hide double bumps behind a composite Academy-import transaction.

- [ ] **Step 4: Run the focused IT and verify Flyway stops at the missing V89 resource**

Run:

```bash
REPO_ROOT="$(git rev-parse --show-toplevel)"
cd "$REPO_ROOT/apps/api"
./mvnw --batch-mode -Ppostgresql-it \
  -Dit.test=PostgresqlV89GlobalAuthEpochUpgradeIT verify
```

Expected: FAIL because V89 and the expected columns do not exist.

- [ ] **Step 5: Write the real MySQL V45 → V46 migration IT in RED**

Create `MysqlV46GlobalAuthEpochUpgradeIT` with a non-skipped MySQL 8.4
Testcontainers database. Configure Flyway with `target("45")`, insert an active
identity, active passkey, active legacy session, and unconsumed registration
challenge, then migrate through V46.
The test must assert the exact shared-schema cutover contract:

```java
assertThat(tableExists(jdbc, "auth_password_credential")).isFalse();
assertThat(epoch(jdbc, collaboratorId)).isEqualTo(2L);
assertThat(sessionColumn(jdbc, legacySessionId, "auth_epoch")).isNull();
assertThat(sessionColumn(jdbc, legacySessionId, "session_marker")).isNull();
assertThat(credentialVersion(jdbc, credentialRecordId)).isZero();
assertThat(challengeRegistrationBinding(jdbc, legacyChallengeId))
        .containsOnlyNulls();
```

After those cutover assertions, create three isolated active passkey-only identities and prove the V46 triggers are real, not documentation: `ATIVA -> BLOQUEADA`, collaborator `ativo = 1 -> 0`, and `deletado_em NULL -> timestamp` each advance the matching `auth_identity.auth_epoch` from N to N + 1. A direct status update that already increments `auth_epoch` must not receive a second bump from the identity trigger. These assertions run on MySQL 8.4 with no skip condition.
Create each of those post-V46 identities without naming `auth_epoch` and first
assert it receives default 2; this is the shared-schema rolling-cutover fence,
not merely a backfill assertion.

First create `MavenDatabaseItProfileSelectionTest`, parse `pom.xml` as XML, and
assert these exact disjoint selector sets:

```java
assertThat(includes("postgresql-it"))
        .containsExactly("**/*IT.java");
assertThat(excludes("postgresql-it"))
        .containsExactlyInAnyOrder("**/*MysqlIT.java", "**/Mysql*IT.java");
assertThat(includes("mysql-it"))
        .containsExactlyInAnyOrder("**/*MysqlIT.java", "**/Mysql*IT.java");
assertThat(excludes("mysql-it")).isEmpty();
```

Run it before changing the POM:

```bash
REPO_ROOT="$(git rev-parse --show-toplevel)"
cd "$REPO_ROOT/apps/api"
./mvnw --batch-mode \
  -Dtest=MavenDatabaseItProfileSelectionTest test
```

Expected: FAIL because `mysql-it` and the PostgreSQL MySQL exclusions do not
exist.

Then add a `mysql-it` Failsafe profile with only the two MySQL include patterns,
and add both patterns as exclusions in `postgresql-it`. Do not use a generic
`**/*IT.java` include in `mysql-it`, an environment condition, a skip flag, or
`continue-on-error`. Rerun the selection test and expect PASS.

The current POM has `flyway-core` and `flyway-database-postgresql` only. Add the
database module that lets the same managed Flyway version recognize MySQL 8.4:

```xml
<profile>
    <id>mysql-it</id>
    <dependencies>
        <dependency>
            <groupId>org.flywaydb</groupId>
            <artifactId>flyway-mysql</artifactId>
        </dependency>
    </dependencies>
    <!-- the disjoint Failsafe configuration follows -->
</profile>
```

Do not pin a second Flyway version: use Spring Boot dependency management so
all three Flyway artifacts remain aligned. Keep `flyway-mysql` confined to the
`mysql-it` profile so the canonical PostgreSQL runtime does not load the retired
MySQL migration engine. Update `PostgresqlFoundationContractTest` to parse the
POM and assert exactly that boundary: no top-level/runtime `flyway-mysql`, one
managed module under `mysql-it`, and the PostgreSQL Flyway location still only
`classpath:db/migration-postgresql`. The non-skipped V45 -> V46 Testcontainers
migration below is the executable proof that the module is present in its
owning profile; a driver-recognition failure is not an acceptable skipped
result.

Tasks 2 and 4 rename the two existing authentication MySQL fixtures exactly
once and remove their `@EnabledIfEnvironmentVariable`; Task 6 only verifies
the resulting profile. Now run the migration IT:

```bash
REPO_ROOT="$(git rev-parse --show-toplevel)"
cd "$REPO_ROOT/apps/api"
./mvnw --batch-mode -Pmysql-it \
  -Dit.test=MysqlV46GlobalAuthEpochUpgradeIT verify
```

Expected: FAIL because V46 and the expected columns do not exist. A skipped
test is a failure of this step, not a pass.

- [ ] **Step 6: Implement PostgreSQL V89 with no session backfill**

Use this schema shape:

```sql
ALTER TABLE auth_identity
    ADD COLUMN auth_epoch bigint;

UPDATE auth_identity identity
SET auth_epoch = GREATEST(
        COALESCE((
            SELECT credential.auth_epoch
            FROM auth_password_credential credential
            WHERE credential.colaborador_id = identity.colaborador_id
        ), 0),
        1
    ) + 1;

ALTER TABLE auth_identity
    ALTER COLUMN auth_epoch SET NOT NULL,
    ALTER COLUMN auth_epoch SET DEFAULT 2,
    ADD CONSTRAINT chk_auth_identity_auth_epoch CHECK (auth_epoch >= 1);

ALTER TABLE auth_session
    ADD COLUMN auth_epoch bigint,
    ADD COLUMN session_marker char(43),
    ADD CONSTRAINT chk_auth_session_epoch_marker CHECK (
        (auth_epoch IS NULL AND session_marker IS NULL)
        OR (
            auth_epoch >= 1
            AND session_marker ~ '^[A-Za-z0-9_-]{43}$'
        )
    );

CREATE UNIQUE INDEX uq_auth_session_marker
    ON auth_session (session_marker)
    WHERE session_marker IS NOT NULL;

ALTER TABLE auth_webauthn_credential
    ADD COLUMN versao_linha bigint NOT NULL DEFAULT 0,
    ADD CONSTRAINT chk_auth_webauthn_version CHECK (versao_linha >= 0);

ALTER TABLE auth_webauthn_challenge
    ADD COLUMN registration_session_id varchar(36),
    ADD COLUMN registration_session_marker char(43),
    ADD COLUMN registration_auth_epoch bigint,
    ADD CONSTRAINT chk_webauthn_registration_session_binding CHECK (
        (
            registration_session_id IS NULL
            AND registration_session_marker IS NULL
            AND registration_auth_epoch IS NULL
        ) OR (
            registration_session_id IS NOT NULL
            AND registration_session_marker ~ '^[A-Za-z0-9_-]{43}$'
            AND registration_auth_epoch >= 1
        )
    );

CREATE INDEX idx_webauthn_challenge_registration_session
    ON auth_webauthn_challenge (registration_session_id)
    WHERE registration_session_id IS NOT NULL;
```

The default remains 2 for this rolling release. A V88 process that creates a
new identity after Neon has applied V89 does not know the new column, so this
default applies the one-time cutover bump even when its first password
credential INSERT explicitly uses the old password epoch 1 and therefore emits
no UPDATE trigger. Do not lower the default to 1 until every V88 process is
provably unable to serve and a later reviewed migration owns that change.

The nullable all-or-none challenge shape deliberately leaves pre-V89
registration challenges unbound. Task 4 rejects them instead of attaching
them to whichever cookie happens to arrive after deployment; authentication
challenges keep all three columns null.

Drop the two V88 triggers/functions by exact name, create global identity/collaborator revocation triggers, and add the rolling bridge:

```sql
CREATE OR REPLACE FUNCTION cortex_bridge_password_epoch_to_identity_v89()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.auth_epoch > OLD.auth_epoch THEN
        UPDATE auth_identity
        SET auth_epoch = auth_epoch + 1,
            versao_linha = versao_linha + 1
        WHERE colaborador_id = NEW.colaborador_id;
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_auth_password_epoch_bridge_v89
AFTER UPDATE OF auth_epoch ON auth_password_credential
FOR EACH ROW
EXECUTE FUNCTION cortex_bridge_password_epoch_to_identity_v89();
```

The direct identity trigger must only add a bump when the statement did not already advance it:

```sql
IF OLD.status = 'ATIVA'
   AND NEW.status <> 'ATIVA'
   AND NEW.auth_epoch = OLD.auth_epoch THEN
    NEW.auth_epoch := OLD.auth_epoch + 1;
END IF;
```

The collaborator trigger advances the identity even when no password row exists. Its isolated inactive/deleted transitions and the direct identity blocking transition are mandatory assertions in `PostgresqlV89GlobalAuthEpochUpgradeIT`; do not reduce these triggers to a source-text check. Do not drop `auth_password_credential.auth_epoch` in V89.

- [ ] **Step 7: Implement the exact shared-MySQL V46 schema**

The shared MySQL history through V45 has no `auth_password_credential`; V46
must not query it or contain a conditional branch for a table that is absent.
Use MySQL-compatible DDL with this exact cutover:

```sql
ALTER TABLE auth_identity
    ADD COLUMN auth_epoch bigint NOT NULL DEFAULT 2;

UPDATE auth_identity
SET auth_epoch = 2;

ALTER TABLE auth_session
    ADD COLUMN auth_epoch bigint NULL,
    ADD COLUMN session_marker char(43) NULL,
    ADD CONSTRAINT chk_auth_session_epoch_marker CHECK (
        (auth_epoch IS NULL AND session_marker IS NULL)
        OR (auth_epoch >= 1 AND session_marker REGEXP '^[A-Za-z0-9_-]{43}$')
    );

CREATE UNIQUE INDEX uq_auth_session_marker
    ON auth_session (session_marker);

ALTER TABLE auth_webauthn_credential
    ADD COLUMN versao_linha bigint NOT NULL DEFAULT 0;

ALTER TABLE auth_webauthn_challenge
    ADD COLUMN registration_session_id char(36) NULL,
    ADD COLUMN registration_session_marker char(43) NULL,
    ADD COLUMN registration_auth_epoch bigint NULL,
    ADD CONSTRAINT chk_webauthn_registration_session_binding CHECK (
        (
            registration_session_id IS NULL
            AND registration_session_marker IS NULL
            AND registration_auth_epoch IS NULL
        ) OR (
            registration_session_id IS NOT NULL
            AND registration_session_marker REGEXP '^[A-Za-z0-9_-]{43}$'
            AND registration_auth_epoch >= 1
        )
    );

CREATE INDEX idx_webauthn_challenge_registration_session
    ON auth_webauthn_challenge (registration_session_id);
```

Every identity that exists at cutover is bumped to **exactly 2**. Fresh rows
created after V46 also start at 2 so no legacy writer can recreate the
pre-cutover generation during a rolling application update. MySQL permits multiple `NULL` values in the unique
index, so legacy sessions remain untouched with both new columns null. Do not
generate markers or backfill epochs for existing sessions. Existing WebAuthn
challenges also retain three null registration-binding columns and become
fail-closed for registration after Task 4; never bind them to a later session.

Add the MySQL lifecycle triggers in the same migration. Keep them as
single-statement triggers so Flyway does not depend on client-side delimiter
directives:

```sql
CREATE TRIGGER trg_auth_identity_epoch_on_status_v46
BEFORE UPDATE ON auth_identity
FOR EACH ROW
SET NEW.auth_epoch = CASE
    WHEN OLD.status = 'ATIVA'
      AND NEW.status <> 'ATIVA'
      AND NEW.auth_epoch = OLD.auth_epoch
        THEN OLD.auth_epoch + 1
    ELSE NEW.auth_epoch
END;

CREATE TRIGGER trg_colaborador_epoch_on_ineligible_v46
AFTER UPDATE ON colaborador
FOR EACH ROW
UPDATE auth_identity
SET auth_epoch = auth_epoch + 1,
    versao_linha = versao_linha + 1
WHERE colaborador_id = NEW.id
  AND (
      (OLD.ativo = 1 AND NEW.ativo = 0)
      OR (
          OLD.deletado_em IS NULL
          AND NEW.deletado_em IS NOT NULL
      )
  );
```

The direct identity trigger deliberately does not add a second bump when the
writer already supplied `auth_epoch = OLD.auth_epoch + 1`. The collaborator
trigger protects operational lifecycle writers even when no password factor
exists. A composite import may legitimately advance more than once if it makes
both transitions; security depends on monotonic invalidation, not contiguous
epoch numbers.

- [ ] **Step 8: Advance all PostgreSQL schema-version guards to 89**

Set:

```java
public static final String REQUIRED = "89";
```

and:

```yaml
cortex:
  postgresql:
    required-schema-version: 89
```

Rename test methods/messages from V88 to V89 and update explicit `version = '88'` assertions to `version = '89'`. Leave V88 migration-content tests intact.

Update `PostgresqlActivationReadinessTest` so its method names, SQL assertion,
and error message require V89. Update
`PostgresqlCleanStartFlowIT.assertCurrentMigrationChainWasApplied(...)` so the
ordered version list ends in `"89"`; V89 adds columns only, so keep the current
table inventory/count unchanged. Do not migrate that test's session issuer yet;
Task 2 changes its call after `issueCurrent(...)` exists.

- [ ] **Step 9: Run migration/readiness tests GREEN**

Run:

```bash
REPO_ROOT="$(git rev-parse --show-toplevel)"
cd "$REPO_ROOT/apps/api"
./mvnw --batch-mode \
  -Dtest=PasswordSecurityMigrationTest,MavenDatabaseItProfileSelectionTest,PostgresqlActivationReadinessTest,PostgresqlSchemaReadinessGuardTest,PostgresqlRuntimeReadinessGuardTest,PostgresqlModeConfigurationGuardTest,PostgresqlFoundationContractTest,PostgresqlProfileModesContractTest,PostgresqlEffectiveConfigurationTest \
  test
./mvnw --batch-mode -Ppostgresql-it \
  -Dit.test=PostgresqlV89GlobalAuthEpochUpgradeIT,PostgresqlCleanStartFlowIT verify
./mvnw --batch-mode -Pmysql-it \
  -Dit.test=MysqlV46GlobalAuthEpochUpgradeIT verify
```

Expected: PASS; PostgreSQL proves old sessions remain null, its bridge is
monotonic, and all three lifecycle transitions advance epoch; activation/clean-start
both require V89; profile selection is disjoint; and the non-skipped MySQL 8.4
IT proves every V45 identity becomes epoch 2 without an unavailable password
table and that blocking/deactivation/deletion each invalidate the cutover epoch.

- [ ] **Step 10: Commit the schema cutover**

```bash
REPO_ROOT="$(git rev-parse --show-toplevel)"
cd "$REPO_ROOT"
git add apps/api/src/main/resources/db/migration-postgresql/V89__global_auth_epoch_and_session_generation.sql \
  apps/api/src/main/resources/db/migration/V46__global_auth_epoch_and_session_generation.sql \
  apps/api/pom.xml \
  apps/api/src/main/java/com/projeto/cortex/config/PostgresqlSchemaVersion.java \
  apps/api/src/main/resources/application-postgresql-common.yml \
  apps/api/src/test/java/com/projeto/cortex/auth/postgresql/PostgresqlV89GlobalAuthEpochUpgradeIT.java \
  apps/api/src/test/java/com/projeto/cortex/auth/session/MysqlV46GlobalAuthEpochUpgradeIT.java \
  apps/api/src/test/java/com/projeto/cortex/config/MavenDatabaseItProfileSelectionTest.java \
  apps/api/src/test/java/com/projeto/cortex/auth/password/PasswordSecurityMigrationTest.java \
  apps/api/src/test/java/com/projeto/cortex/common/PostgresqlActivationReadinessTest.java \
  apps/api/src/test/java/com/projeto/cortex/postgresql/PostgresqlCleanStartFlowIT.java \
  apps/api/src/test/java/com/projeto/cortex/config
git commit -m "feat(auth): move authorization epoch to identity"
```

---

### Task 2: Bind every direct session issuer to global epoch and a marker

**Files:**
- Create: `apps/api/src/main/java/com/projeto/cortex/auth/session/CurrentSessionIdentity.java`
- Create: `apps/api/src/main/java/com/projeto/cortex/auth/session/AuthenticatedSessionIssue.java`
- Modify: `apps/api/src/main/java/com/projeto/cortex/auth/session/AuthSessionRepository.java:7-26`
- Modify: `apps/api/src/main/java/com/projeto/cortex/auth/session/AuthSessionService.java:19-155`
- Modify: `apps/api/src/main/java/com/projeto/cortex/auth/session/IssuedAuthSession.java:8-59`
- Modify: `apps/api/src/main/java/com/projeto/cortex/auth/session/ResolvedAuthSession.java:9-70`
- Modify: `apps/api/src/main/java/com/projeto/cortex/auth/session/PostgresqlAuthSessionRepository.java:23-101`
- Modify: `apps/api/src/main/java/com/projeto/cortex/auth/session/MysqlAuthSessionRepository.java:25-155`
- Modify: `apps/api/src/main/java/com/projeto/cortex/auth/session/AuthSessionProfileResolver.java:8-18`
- Modify: `apps/api/src/main/java/com/projeto/cortex/auth/activation/PostgresqlActivationSessionProfileResolver.java:19-56`
- Modify: `apps/api/src/main/java/com/projeto/cortex/auth/AuthSessionResponse.java:13-134`
- Modify: `apps/api/src/main/java/com/projeto/cortex/auth/CurrentUserService.java:340-377`
- Modify: `apps/api/src/main/java/com/projeto/cortex/auth/AuthController.java:100-208`
- Modify: `apps/api/src/main/java/com/projeto/cortex/auth/webauthn/WebAuthnController.java:107-136`
- Test: `apps/api/src/test/java/com/projeto/cortex/auth/session/AuthSessionServiceTest.java`
- Test: `apps/api/src/test/java/com/projeto/cortex/auth/session/JdbcAuthSessionRepositoryTest.java`
- Test: `apps/api/src/test/java/com/projeto/cortex/auth/AuthSessionResponseTest.java`
- Test: `apps/api/src/test/java/com/projeto/cortex/auth/postgresql/PostgresqlAuthSessionRepositoryIT.java`
- Rename: `apps/api/src/test/java/com/projeto/cortex/auth/session/AuthSessionMysqlIntegrationTest.java` → `apps/api/src/test/java/com/projeto/cortex/auth/session/AuthSessionMysqlIT.java`
- Test: `apps/api/src/test/java/com/projeto/cortex/auth/session/SessionTokenFixtures.java`
- Test: `apps/api/src/test/java/com/projeto/cortex/auth/AuthControllerTest.java`
- Test: `apps/api/src/test/java/com/projeto/cortex/auth/activation/PostgresqlActivationSessionProfileResolverTest.java`
- Test: `apps/api/src/test/java/com/projeto/cortex/auth/offline/OfflineGrantControllerTest.java`
- Test: `apps/api/src/test/java/com/projeto/cortex/auth/offline/OfflineGrantEndpointBoundaryTest.java`
- Test: `apps/api/src/test/java/com/projeto/cortex/auth/webauthn/WebAuthnControllerTest.java`
- Test: `apps/api/src/test/java/com/projeto/cortex/auth/webauthn/WebAuthnEndpointBoundaryTest.java`
- Test: `apps/api/src/test/java/com/projeto/cortex/postgresql/PostgresqlCleanStartFlowIT.java`
- Test: `apps/api/src/test/java/com/projeto/cortex/auth/otp/PostgresqlEmailOtpChallengeRepositoryIT.java`
- Test: `apps/api/src/test/java/com/projeto/cortex/auth/postgresql/PostgresqlAcademyDirectCpfLoginIT.java`
- Test: `apps/api/src/test/java/com/projeto/cortex/auth/webauthn/PostgresqlV61ClientInstanceBindingUpgradeIT.java`
- Test: `apps/api/src/test/java/com/projeto/cortex/pdor/PdorCw38386MysqlIntegrationTest.java`

**Interfaces:**
- Consumes: V89/V46 columns from Task 1 and the existing 32-byte URL-safe token generator.
- Produces: marker/epoch-bearing issued and resolved sessions, public
  `AuthSessionResponse.sessionMarker()`, `issueCurrent(...)` for every direct
  issuer until password/passkey move to CAS in Tasks 3–4, with the returned
  identity rebuilt from the same lock that authorizes the insert, and
  package-private `issueVerified(...)` for Tasks 3–4.

- [ ] **Step 1: Write failing record-validation tests**

Add cases proving that marker format is exactly 43 Base64URL characters, epoch is at least one, and `toString()` does not expose marker/token/CSRF hashes:

```java
assertThatThrownBy(() -> new IssuedAuthSession(
        SESSION_ID, RAW_TOKEN, RAW_CSRF, "short", 7L, EXPIRES_AT
)).isInstanceOf(IllegalArgumentException.class);

assertThat(new IssuedAuthSession(
        SESSION_ID, RAW_TOKEN, RAW_CSRF, "m".repeat(43), 7L, EXPIRES_AT
).toString()).doesNotContain(RAW_TOKEN, RAW_CSRF, "m".repeat(43));
```

- [ ] **Step 2: Write repository ITs for null and mismatched epochs**

In `PostgresqlAuthSessionRepositoryIT`, add three rows:

```java
// Legacy row: epoch and marker omitted.
insertLegacySession(jdbc, legacySessionId, collaboratorId, legacyTokenHash);

// Current row.
sessions.create(
        currentSessionId,
        collaboratorId,
        currentTokenHash,
        csrfHash,
        clientInstanceHash,
        "s".repeat(43),
        identityEpoch,
        300
);
```

Assert legacy resolution is empty, current resolution returns marker/epoch, and after `UPDATE auth_identity SET auth_epoch = auth_epoch + 1`, current resolution is empty without calling session revocation.

- [ ] **Step 3: Run tests RED**

Run:

```bash
REPO_ROOT="$(git rev-parse --show-toplevel)"
cd "$REPO_ROOT/apps/api"
./mvnw --batch-mode \
  -Dtest=AuthSessionServiceTest,AuthSessionResponseTest test
./mvnw --batch-mode -Ppostgresql-it \
  -Dit.test=PostgresqlAuthSessionRepositoryIT verify
```

Expected: compilation/test failure because the records and repository do not expose epoch/marker.

- [ ] **Step 4: Extend the session records and repository signature**

Implement `lockCurrentIdentityForSessionIssue(...)` with this PostgreSQL
query; the MySQL adapter uses the same predicates with `FOR UPDATE`:

```sql
SELECT identity.auth_epoch,
       colaborador.id,
       colaborador.nome,
       colaborador.papel_acesso
FROM auth_identity identity
JOIN colaborador
  ON colaborador.id = identity.colaborador_id
WHERE identity.colaborador_id = ?
  AND identity.status = 'ATIVA'
  AND colaborador.ativo = TRUE
  AND colaborador.deletado_em IS NULL
  AND colaborador.papel_acesso IN ('ALFA', 'BETA')
FOR SHARE OF identity, colaborador
```

Reject zero/multiple rows, epochs below one, or a collaborator ID different
from the pre-lock expected owner. Map the row to `CurrentSessionIdentity` and
rebuild `AuthenticatedIdentity` from the locked name/role; never merge those
fields with the pre-lock object. Change
`AuthSessionRepository.create` to:

```java
Instant create(
        String sessionId,
        String collaboratorId,
        String tokenHash,
        String csrfHash,
        String clientInstanceHash,
        String sessionMarker,
        long authEpoch,
        int ttlSeconds
);
```

Generate `sessionMarker` with the existing 32-byte CSPRNG independently from the raw session and CSRF tokens. Validate all three are pairwise distinct.

Update PostgreSQL insertion:

```sql
INSERT INTO auth_session (
    id, colaborador_id, token_hash, csrf_hash,
    client_instance_hash, client_instance_bound,
    session_marker, auth_epoch, expira_em
) VALUES (?, ?, ?, ?, ?, TRUE, ?, ?,
    clock_timestamp() + (? * INTERVAL '1 second'))
RETURNING expira_em
```

Update session resolution to select marker/epoch and require:

```sql
AND session.auth_epoch IS NOT NULL
AND session.session_marker IS NOT NULL
AND session.auth_epoch = identity.auth_epoch
```

Apply the equivalent query to `MysqlAuthSessionRepository` using the V46 schema.
Keep active collaborator/identity checks unchanged. The MySQL insert must use
MySQL interval syntax explicitly; do not copy the PostgreSQL expression into
that adapter:

```sql
INSERT INTO auth_session (
    id, colaborador_id, token_hash, csrf_hash,
    client_instance_hash, client_instance_bound,
    session_marker, auth_epoch, expira_em
) VALUES (?, ?, ?, ?, ?, TRUE, ?, ?,
    TIMESTAMPADD(SECOND, ?, CURRENT_TIMESTAMP(6)))
```

Keep the placeholder order identical to the repository method. Extend
`AuthSessionMysqlIT` to execute the real insert, assert the stored marker and
epoch, and assert the database expiry lies within the configured TTL window;
a source-text assertion is insufficient.

Introduce the stable `issueCurrent(...)`, `issueCurrentForActivation(...)`, and
`issueVerified(...)` methods.
`issueCurrent(...)` is `@Transactional`, obtains
`lockCurrentIdentityForSessionIssue(...)`, calls `create(...)` with the locked
owner and epoch, and returns `AuthenticatedSessionIssue(lockedIdentity,
issuedSession)` while holding the lock through the insert. The activation
variant performs its ALFA eligibility check against that same locked identity
before `create(...)`; it must not issue first and rely on the ALFA-only profile
resolver to fail afterward.
`issueVerified(...)` validates the epoch and is package-private;
the password/passkey CAS transaction is its only caller. Do not remove the old
two-argument `issue(...)` while any caller still references it. In Step 6,
migrate **all** direct callers to `issueCurrent(...)` in the same task, then
remove `issue(...)`; Tasks 3 and 4 subsequently replace only the temporary
password/passkey `issueCurrent(...)` calls with factor CAS. This sequencing
keeps Task 2 compiling and GREEN.

Keep `AuthSessionService` non-final and keep its transactional methods
non-final so Spring Boot's default class-based proxy can intercept
`issueCurrent(...)` and `issueCurrentForActivation(...)`. In
`AuthSessionServiceTest`, load the proxied bean, assert the transaction is
active inside the repository callback, and prove the locked-role eligibility
check and insert occur in the same transaction.

- [ ] **Step 5: Put the marker in the public session profile**

Add `String sessionMarker` to `AuthSessionResponse` and validate it with `[A-Za-z0-9_-]{43}`. Change the resolver contract to accept the complete issued session:

```java
AuthSessionResponse profileForIssuedSession(
        AuthenticatedIdentity identity,
        IssuedAuthSession session
);
```

Build issued and resumed responses from `session.sessionMarker()`. Never expose `sessionId` as the marker.

Update `PostgresqlActivationSessionProfileResolver` to implement this exact new
signature. Its issued path remains ALFA-only and returns:

```java
return AuthSessionResponse.from(
        identity,
        session.expiresAt(),
        GLOBAL_SCOPE,
        session.sessionMarker()
);
```

Update `PostgresqlActivationSessionProfileResolverTest` with an explicit
`IssuedAuthSession` carrying marker/epoch and assert the returned activation
profile exposes that exact marker. Do not substitute `sessionId` or construct a
new marker in the resolver.

- [ ] **Step 6: Update all constructors and fixtures explicitly**

Use fixed 43-character marker fixtures in:

- `AuthControllerTest`
- `AuthSessionResponseTest`
- `OfflineGrantEndpointBoundaryTest`
- `SessionTokenFixtures`
- `WebAuthnControllerTest`
- `WebAuthnEndpointBoundaryTest`
- `AuthCookieServiceTest`

Rename `AuthSessionMysqlIntegrationTest` to `AuthSessionMysqlIT`, remove its
environment-variable skip, and run it against a MySQL 8.4 Testcontainer under
the `mysql-it` profile introduced in Task 1. This fixture must exercise the
V46 columns and reject a legacy null marker/epoch; it must not silently run
under the PostgreSQL profile.

Update `JdbcAuthSessionRepositoryTest` in the same step: pass the new marker and
epoch arguments to `create(...)`, replace its inherited PostgreSQL interval
expectation with the exact MySQL `TIMESTAMPADD(SECOND, ?,
CURRENT_TIMESTAMP(6))` statement, and assert the placeholder sequence ends in
marker, epoch, TTL. This facade test must not keep approving the PostgreSQL
expression after the MySQL adapter becomes non-skipped.

Do not loosen constructors or add a default marker merely to satisfy tests.

Migrate every direct issuer before deleting `issue(...)`. For
`AuthController.verifyChallenge`, use the ALFA-only atomic variant:

```java
AuthenticatedSessionIssue issue = sessions.issueCurrentForActivation(
        identity,
        clientInstance
)
        .orElseThrow(() -> new ResponseStatusException(
                HttpStatus.UNAUTHORIZED,
                "Identidade ou época de autenticação alterada."
        ));
```

Add an `AuthControllerTest` proving OTP cannot create a session when no current
identity epoch can be locked and that success returns the marker stored on that
session. Add a barrier case in which the activation factor supplies an ALFA
snapshot but the locked row is now BETA: `issueCurrentForActivation(...)`
returns empty before `create(...)`, and the controller emits no cookie or
profile. Separately, `AuthSessionServiceTest` exercises ordinary
`issueCurrent(...)` with an ALFA snapshot whose locked row is BETA and proves
the issued identity is rebuilt as BETA. If the locked row became inactive,
blocked, or deleted, both variants return empty. Thus no post-V89 path inserts
a null/guessed epoch, serializes a pre-lock role/name, or creates an unusable
activation session before the ALFA-only resolver rejects it.

Use the same `issueCurrent(...).orElseThrow(...)` shape, preserving each
caller's existing generic failure response, in:

- `AuthController.login` (temporary until Task 3 routes password through CAS);
- `WebAuthnController.finishAuthentication` (temporary until Task 4 routes
  passkey through CAS);
- `PostgresqlCleanStartFlowIT`;
- `PostgresqlEmailOtpChallengeRepositoryIT`;
- `PostgresqlAcademyDirectCpfLoginIT`;
- `PdorCw38386MysqlIntegrationTest`.

Pass `issue.identity()` and the complete `issue.session()` to
`profileForIssuedSession(...)` everywhere. Update mocks/verifiers
in `AuthControllerTest`, `WebAuthnControllerTest`, and activation tests; no
caller may retain the old `(identity, expiresAt)` signature.

Adapt `PostgresqlV61ClientInstanceBindingUpgradeIT` without weakening its V61
historical assertion: migrate V60 → V61, assert the three legacy rows have
null/unbound client-instance fields directly, then migrate the same database
through V89 **before** invoking the current `PostgresqlAuthSessionRepository`.
Assert V89 leaves that legacy session's `auth_epoch` and `session_marker` null
and the current repository rejects it. This keeps the historical fixture valid
without making the V89-aware repository query columns that did not exist at
V61.

- [ ] **Step 7: Run session tests GREEN**

Run:

```bash
REPO_ROOT="$(git rev-parse --show-toplevel)"
cd "$REPO_ROOT/apps/api"
./mvnw --batch-mode \
  -Dtest=AuthSessionServiceTest,AuthSessionResponseTest,AuthCookieServiceTest,AuthSessionFilterTest,JdbcAuthSessionRepositoryTest,AuthControllerTest,PostgresqlActivationSessionProfileResolverTest,OfflineGrantControllerTest,OfflineGrantEndpointBoundaryTest,WebAuthnControllerTest,WebAuthnEndpointBoundaryTest,PdorCw38386MysqlIntegrationTest \
  test
./mvnw --batch-mode -Ppostgresql-it \
  -Dit.test=PostgresqlAuthSessionRepositoryIT,PostgresqlCleanStartFlowIT,PostgresqlEmailOtpChallengeRepositoryIT,PostgresqlAcademyDirectCpfLoginIT,PostgresqlV61ClientInstanceBindingUpgradeIT verify
./mvnw --batch-mode -Pmysql-it \
  -Dit.test=AuthSessionMysqlIT verify
```

Expected: PASS with zero skipped selected authentication MySQL tests; all old
`issue(...)`/`profileForIssuedSession(identity, expiresAt)` callers compile
against the new contracts, V61 historical rows remain unbound and become
fail-closed under V89, direct identity epoch bump invalidates the cookie, and
the marker is stable only for one session row. Direct OTP/activation issuance
also proves that a concurrent role/name/eligibility change is either reflected
from the locked row or rejected; it never returns the pre-lock identity. The
unrelated PDOR MySQL fixture may retain its existing environment policy,
but it must compile with the new
issuer signature.

- [ ] **Step 8: Commit epoch-bound sessions**

```bash
REPO_ROOT="$(git rev-parse --show-toplevel)"
cd "$REPO_ROOT"
git add apps/api/src/main/java/com/projeto/cortex/auth/session \
  apps/api/src/main/java/com/projeto/cortex/auth/activation/PostgresqlActivationSessionProfileResolver.java \
  apps/api/src/main/java/com/projeto/cortex/auth/AuthSessionResponse.java \
  apps/api/src/main/java/com/projeto/cortex/auth/CurrentUserService.java \
  apps/api/src/main/java/com/projeto/cortex/auth/AuthController.java \
  apps/api/src/main/java/com/projeto/cortex/auth/webauthn/WebAuthnController.java \
  apps/api/src/test/java/com/projeto/cortex/auth/session \
  apps/api/src/test/java/com/projeto/cortex/auth/AuthSessionResponseTest.java \
  apps/api/src/test/java/com/projeto/cortex/auth/AuthControllerTest.java \
  apps/api/src/test/java/com/projeto/cortex/auth/activation/PostgresqlActivationSessionProfileResolverTest.java \
  apps/api/src/test/java/com/projeto/cortex/auth/offline/OfflineGrantControllerTest.java \
  apps/api/src/test/java/com/projeto/cortex/auth/offline/OfflineGrantEndpointBoundaryTest.java \
  apps/api/src/test/java/com/projeto/cortex/auth/webauthn/WebAuthnControllerTest.java \
  apps/api/src/test/java/com/projeto/cortex/auth/webauthn/WebAuthnEndpointBoundaryTest.java \
  apps/api/src/test/java/com/projeto/cortex/auth/postgresql/PostgresqlAuthSessionRepositoryIT.java \
  apps/api/src/test/java/com/projeto/cortex/postgresql/PostgresqlCleanStartFlowIT.java \
  apps/api/src/test/java/com/projeto/cortex/auth/otp/PostgresqlEmailOtpChallengeRepositoryIT.java \
  apps/api/src/test/java/com/projeto/cortex/auth/postgresql/PostgresqlAcademyDirectCpfLoginIT.java \
  apps/api/src/test/java/com/projeto/cortex/auth/webauthn/PostgresqlV61ClientInstanceBindingUpgradeIT.java \
  apps/api/src/test/java/com/projeto/cortex/pdor/PdorCw38386MysqlIntegrationTest.java
git commit -m "feat(auth): bind sessions to global epoch"
```

---

### Task 3: Commit password authentication and reset atomically

**Files:**
- Create: `apps/api/src/main/java/com/projeto/cortex/auth/session/VerifiedAuthentication.java`
- Create: `apps/api/src/main/java/com/projeto/cortex/auth/session/AuthenticationCommitService.java`
- Create: `apps/api/src/main/java/com/projeto/cortex/auth/session/PostgresqlAuthenticationCommitService.java`
- Create: `apps/api/src/main/java/com/projeto/cortex/auth/session/MysqlAuthenticationCommitService.java`
- Create: `apps/api/src/main/java/com/projeto/cortex/auth/identity/AuthIdentityEpochRepository.java`
- Create: `apps/api/src/main/java/com/projeto/cortex/auth/identity/PostgresqlAuthIdentityEpochRepository.java`
- Create: `apps/api/src/main/java/com/projeto/cortex/auth/identity/MysqlAuthIdentityEpochRepository.java`
- Create: `apps/api/src/main/java/com/projeto/cortex/auth/password/PasswordCredentialSnapshot.java`
- Modify: `apps/api/src/main/java/com/projeto/cortex/auth/password/PasswordCredentialRepository.java:5-14`
- Modify: `apps/api/src/main/java/com/projeto/cortex/auth/password/PostgresqlPasswordCredentialRepository.java:20-125`
- Modify: `apps/api/src/main/java/com/projeto/cortex/auth/password/PasswordAuthenticationService.java:50-111`
- Modify: `apps/api/src/main/java/com/projeto/cortex/auth/password/PasswordSetupService.java:30-200`
- Modify: `apps/api/src/main/java/com/projeto/cortex/auth/AuthController.java:161-208`
- Create: `apps/api/src/test/java/com/projeto/cortex/auth/session/AuthenticationCommitServiceProfileTest.java`
- Create: `apps/api/src/test/java/com/projeto/cortex/auth/identity/AuthIdentityEpochRepositoryProfileTest.java`
- Create: `apps/api/src/test/java/com/projeto/cortex/auth/postgresql/PostgresqlAuthenticationSessionCasIT.java`
- Test: `apps/api/src/test/java/com/projeto/cortex/auth/password/PasswordAuthenticationServiceTest.java`
- Test: `apps/api/src/test/java/com/projeto/cortex/auth/password/PasswordSetupServiceTest.java`
- Test: `apps/api/src/test/java/com/projeto/cortex/auth/password/PostgresqlPasswordPersistenceIT.java`
- Test: `apps/api/src/test/java/com/projeto/cortex/auth/AuthControllerTest.java`

**Interfaces:**
- Consumes: Task 2 `AuthenticatedSessionIssue` and session creation with
  `sessionMarker` and `authEpoch`.
- Produces: `VerifiedAuthentication.Password`, the database-neutral
  `AuthenticationCommitService.issue(...)` contract with mutually exclusive
  PostgreSQL/MySQL implementations, and `AuthIdentityEpochRepository.advance(...)`
  for Task 4 passkey revocation.

Replace the password-owned epoch API with these exact credential operations:

```java
public record PasswordCredentialSnapshot(
        String passwordHash,
        long credentialVersion,
        long authEpoch
) {
    @Override
    public String toString() {
        return "PasswordCredentialSnapshot[passwordHash=<redacted>, "
                + "credentialVersion=" + credentialVersion
                + ", authEpoch=" + authEpoch + "]";
    }
}

public interface PasswordCredentialRepository {
    Optional<PasswordCredentialSnapshot> findForAuthentication(
            String collaboratorId
    );

    boolean existsByCollaboratorId(String collaboratorId);

    void rotateHash(String collaboratorId, String passwordHash);
}
```

Delete `findAuthEpochByCollaboratorId`, `rotateHashAndEpoch`, and
`invalidateEpoch`; only `AuthIdentityEpochRepository` may mutate the global
epoch after V89. `PasswordSetupService.issue(...)` uses
`existsByCollaboratorId(...)` to choose `FIRST_ACCESS` versus `RESET`.

- [ ] **Step 1: Write password snapshot tests RED**

Change `PasswordAuthenticationServiceTest` so a successful lookup returns:

```java
new PasswordCredentialSnapshot(
        ARGON_HASH,
        12L,
        7L
)
```

and assert:

```java
assertThat(service.authenticate(CPF, PASSWORD)).contains(
        new VerifiedAuthentication.Password(
                IDENTITY,
                COLLABORATOR_ID,
                12L,
                7L
        )
);
assertThat(new PasswordCredentialSnapshot(ARGON_HASH, 12L, 7L).toString())
        .contains("passwordHash=<redacted>")
        .doesNotContain(ARGON_HASH);
```

Keep the dummy-hash timing path for unknown CPF and missing credential.

- [ ] **Step 2: Write PostgreSQL CAS tests RED**

In `PostgresqlAuthenticationSessionCasIT`, prove four sequences:

1. Valid snapshot creates exactly one session with epoch and marker.
2. `UPDATE auth_password_credential SET versao_linha = versao_linha + 1` after verification makes commit return empty.
3. `UPDATE auth_identity SET auth_epoch = auth_epoch + 1` after verification makes commit return empty.
4. Force the session insert to fail and assert no partial factor/identity write remains.

Core assertion:

```java
Optional<AuthenticatedSessionIssue> issued = commits.issue(
        verifiedPassword,
        clientInstance
);
assertThat(issued).isEmpty();
assertThat(jdbc.queryForObject(
        "SELECT COUNT(*) FROM auth_session WHERE colaborador_id = ?",
        Integer.class,
        collaboratorId
)).isZero();
```

- [ ] **Step 3: Run password tests RED**

Run:

```bash
REPO_ROOT="$(git rev-parse --show-toplevel)"
cd "$REPO_ROOT/apps/api"
./mvnw --batch-mode \
  -Dtest=PasswordAuthenticationServiceTest,PasswordSetupServiceTest,AuthControllerTest test
./mvnw --batch-mode -Ppostgresql-it \
  -Dit.test=PostgresqlAuthenticationSessionCasIT,PostgresqlPasswordPersistenceIT verify
```

Expected: compilation failures because the snapshot, sealed result, and commit service do not exist.

- [ ] **Step 4: Implement the sealed verified-factor and commit contracts**

Create `VerifiedAuthentication` exactly as declared in “Stable interfaces”. Its canonical constructor must require non-null identity, canonical UUID credential ID, non-negative version, epoch at least one, and for passkey a non-negative signature count. Its `toString()` returns only:

```java
return "VerifiedAuthentication[factor="
        + getClass().getSimpleName()
        + ", credential=[REDACTED]]";
```

Create `AuthenticationCommitService` with the exact stable signature declared
above. Controllers depend only on this interface. Register exactly one bean per
runtime: `PostgresqlAuthenticationCommitService` under
`@Profile("postgresql-common")` and `MysqlAuthenticationCommitService` under
`@Profile("!postgresql-common")`. Add a profile/wiring unit test proving there
is never zero or two commit-service beans for either supported profile.

- [ ] **Step 5: Load password hash, factor version, and global epoch together**

Implement `findForAuthentication` with one active-state query:

```sql
SELECT credential.password_hash,
       credential.versao_linha,
       identity.auth_epoch
FROM auth_password_credential credential
JOIN auth_identity identity
  ON identity.colaborador_id = credential.colaborador_id
JOIN colaborador
  ON colaborador.id = identity.colaborador_id
WHERE credential.colaborador_id = ?
  AND identity.status = 'ATIVA'
  AND colaborador.ativo = TRUE
  AND colaborador.deletado_em IS NULL
  AND colaborador.papel_acesso IN ('ALFA', 'BETA')
```

`PasswordAuthenticationService.authenticate` verifies Argon2 outside a transaction and returns the observed version/epoch. Do not read epoch from `auth_password_credential`.

- [ ] **Step 6: Implement the short CAS transaction**

`PostgresqlAuthenticationCommitService.issue(...)` implements
`AuthenticationCommitService`, is `@Transactional`, and pattern-matches the
sealed factor. For password, run:

```sql
SELECT credential.versao_linha,
       identity.auth_epoch,
       colaborador.id,
       colaborador.nome,
       colaborador.papel_acesso
FROM auth_password_credential credential
JOIN auth_identity identity
  ON identity.colaborador_id = credential.colaborador_id
JOIN colaborador
  ON colaborador.id = identity.colaborador_id
WHERE credential.colaborador_id = ?
  AND credential.versao_linha = ?
  AND identity.auth_epoch = ?
  AND identity.status = 'ATIVA'
  AND colaborador.ativo = TRUE
  AND colaborador.deletado_em IS NULL
  AND colaborador.papel_acesso IN ('ALFA', 'BETA')
FOR UPDATE OF credential, identity, colaborador
```

Only one row permits session creation. Rebuild `AuthenticatedIdentity` from
the locked collaborator columns—do not return the pre-lock name/role carried by
the verification snapshot:

```java
IssuedAuthSession issued = sessions.issueVerified(
        currentIdentity,
        clientInstance,
        verified.authEpoch()
);
return Optional.of(new AuthenticatedSessionIssue(
        currentIdentity,
        issued
));
```

The identity lock remains held through the insert; a concurrent epoch advance waits and invalidates the session on its next resolution.

Create `MysqlAuthenticationCommitService` with the same interface and
transaction boundary, using MySQL `FOR UPDATE` syntax (without PostgreSQL's
`OF` clause) and the V46 identity/session columns. Because shared MySQL has no
password credential table, a `VerifiedAuthentication.Password` returns empty
and emits no session; do not synthesize factor state. Task 4 completes and
tests the real passkey branch in both implementations.

- [ ] **Step 7: Route password login through the commit service**

In `AuthController.login`, replace the direct `sessions.issue(...)` call with:

```java
VerifiedAuthentication.Password verified = requiredPasswordAuthentication()
        .authenticate(cpf, password)
        .orElseThrow(this::loginRejected);

AuthenticatedSessionIssue issue = authenticationCommits
        .issue(verified, clientInstance)
        .orElseThrow(this::loginRejected);

sessionProfiles.requireEligibleForSessionIssue(issue.identity());
cookies.write(response, issue.session());
return sessionProfiles.profileForIssuedSession(
        issue.identity(),
        issue.session()
);
```

Retain the existing rate limiter, generic 401 copy, no CPF logging, cookie writer, and `no-store` header.

- [ ] **Step 8: Write portable epoch-adapter tests RED**

Create `AuthIdentityEpochRepositoryProfileTest` before either concrete adapter.
Use `ApplicationContextRunner` with a mocked `JdbcTemplate` to load the two
adapter configurations under `postgresql-common` and `mysql-it` separately.
Assert each context has exactly one `AuthIdentityEpochRepository`, of the
expected dialect type, and never both. Also assert both concrete `advance`
methods declare `@Transactional(propagation = Propagation.MANDATORY)`.

For the MySQL SQL-unit cases, instantiate the target adapter directly with a
mocked `JdbcTemplate` (the annotation/profile checks stay separate). Return a
locked current epoch of `7`, require the first SQL call to contain
`SELECT auth_epoch ... FOR UPDATE`, require the second SQL call to contain no
`RETURNING`, and return an update count of one. Assert `advance` returns `8`.
Repeat with update count zero and assert fail-closed `IllegalStateException`;
no epoch may be returned from an unchecked Java-only increment. Task 4's real
MySQL service IT supplies the transactional proof that this unit test
deliberately bypasses.

Run:

```bash
REPO_ROOT="$(git rev-parse --show-toplevel)"
cd "$REPO_ROOT/apps/api"
./mvnw --batch-mode -Dtest=AuthIdentityEpochRepositoryProfileTest test
```

Expected: compilation failure because the two concrete adapters do not exist.

- [ ] **Step 9: Move reset authority to portable dialect adapters**

Remove `@Transactional` from the public `PasswordSetupService.complete(...)`.
Validate the password and calculate its Argon2id hash first, then use an
injected `TransactionTemplate` to run a private
`completeLocked(cpf, code, passwordHash)` block containing identity lookup,
challenge lock/verification, hash rotation, epoch advance, activation,
challenge consumption, session revocation, and audit. This keeps expensive
password hashing outside row locks while preserving one atomic database commit.

Keep the application contract database-neutral:

```java
public interface AuthIdentityEpochRepository {
    long advance(String collaboratorId);
}
```

Implement the PostgreSQL adapter only under its owning profile:

```java
@Repository
@Profile("postgresql-common")
class PostgresqlAuthIdentityEpochRepository
        implements AuthIdentityEpochRepository {

@Override
@Transactional(propagation = Propagation.MANDATORY)
public long advance(String collaboratorId) {
    Long epoch = jdbc.queryForObject("""
            UPDATE auth_identity
            SET auth_epoch = auth_epoch + 1,
                versao_linha = versao_linha + 1
            WHERE colaborador_id = ?
            RETURNING auth_epoch
            """, Long.class, collaboratorId);
    if (epoch == null || epoch < 2) {
        throw new IllegalStateException(
                "Época global de autenticação não avançada."
        );
    }
    return epoch;
}
}
```

Implement the MySQL 8.4 adapter without PostgreSQL syntax:

```java
@Repository
@Profile("!postgresql-common")
class MysqlAuthIdentityEpochRepository
        implements AuthIdentityEpochRepository {

@Override
@Transactional(propagation = Propagation.MANDATORY)
public long advance(String collaboratorId) {
    Long current = jdbc.queryForObject("""
            SELECT auth_epoch
            FROM auth_identity
            WHERE colaborador_id = ?
            FOR UPDATE
            """, Long.class, collaboratorId);
    if (current == null || current < 1) {
        throw new IllegalStateException(
                "Identidade de autenticação não encontrada."
        );
    }
    int updated = jdbc.update("""
            UPDATE auth_identity
            SET auth_epoch = auth_epoch + 1,
                versao_linha = versao_linha + 1
            WHERE colaborador_id = ?
              AND auth_epoch = ?
            """, collaboratorId, current);
    if (updated != 1) {
        throw new IllegalStateException(
                "Época global de autenticação não avançada."
        );
    }
    return Math.addExact(current, 1L);
}
}
```

The MySQL select lock and checked update execute inside the caller's existing
transaction; never split them across autocommit boundaries. Convert a missing
row, overflow, or update count other than one into a failure so the surrounding
password-reset/passkey-revocation transaction rolls back. Do not place
`RETURNING` in the shared interface or MySQL adapter.

Keep both concrete epoch adapters non-final. Their transaction annotations
must be interceptable with Spring Boot's default class-based proxies; do not
make the classes or annotated methods final. Add a context-level assertion in
`AuthIdentityEpochRepositoryProfileTest` that the proxied bean can be created
and that `Propagation.MANDATORY` rejects a call outside the caller's
transaction.

In `PasswordSetupService.issue` for `RESET`, call `epochs.advance(targetId)` and `sessions.revokeAllByCollaboratorId(...)` inside the existing transaction. In `complete`, rotate only the hash/credential `versao_linha`, advance the global epoch, activate/consume, and revoke sessions in the same transaction. Keep the audit row in that commit.

- [ ] **Step 10: Prove direct epoch invalidation independently from `revokeAll`**

In `PostgresqlPasswordPersistenceIT`, create a current session, call only `epochs.advance(targetId)` inside a transaction, and assert `findActiveByTokenHashAndClientInstanceHash(...)` returns empty while `revogado_em` is still null. Then retain service tests proving the explicit revocation remains for cleanup/audit.

- [ ] **Step 11: Run password/CAS tests GREEN**

Run:

```bash
REPO_ROOT="$(git rev-parse --show-toplevel)"
cd "$REPO_ROOT/apps/api"
./mvnw --batch-mode \
  -Dtest=PasswordAuthenticationServiceTest,PasswordSetupServiceTest,AuthControllerTest,AuthSessionServiceTest,AuthenticationCommitServiceProfileTest,AuthIdentityEpochRepositoryProfileTest \
  test
./mvnw --batch-mode -Ppostgresql-it \
  -Dit.test=PostgresqlAuthenticationSessionCasIT,PostgresqlPasswordPersistenceIT,PostgresqlAuthSessionRepositoryIT \
  verify
```

Expected: PASS; stale factor version and stale epoch both prevent session
insertion, each runtime wires exactly one epoch adapter, and the MySQL adapter
uses a locked read plus checked update without PostgreSQL `RETURNING` syntax.

- [ ] **Step 12: Commit password CAS and global reset authority**

```bash
REPO_ROOT="$(git rev-parse --show-toplevel)"
cd "$REPO_ROOT"
git add apps/api/src/main/java/com/projeto/cortex/auth/session/VerifiedAuthentication.java \
  apps/api/src/main/java/com/projeto/cortex/auth/session/AuthenticatedSessionIssue.java \
  apps/api/src/main/java/com/projeto/cortex/auth/session/AuthenticationCommitService.java \
  apps/api/src/main/java/com/projeto/cortex/auth/session/PostgresqlAuthenticationCommitService.java \
  apps/api/src/main/java/com/projeto/cortex/auth/session/MysqlAuthenticationCommitService.java \
  apps/api/src/main/java/com/projeto/cortex/auth/identity/AuthIdentityEpochRepository.java \
  apps/api/src/main/java/com/projeto/cortex/auth/identity/PostgresqlAuthIdentityEpochRepository.java \
  apps/api/src/main/java/com/projeto/cortex/auth/identity/MysqlAuthIdentityEpochRepository.java \
  apps/api/src/main/java/com/projeto/cortex/auth/password \
  apps/api/src/main/java/com/projeto/cortex/auth/AuthController.java \
  apps/api/src/test/java/com/projeto/cortex/auth/password \
  apps/api/src/test/java/com/projeto/cortex/auth/session/AuthenticationCommitServiceProfileTest.java \
  apps/api/src/test/java/com/projeto/cortex/auth/identity/AuthIdentityEpochRepositoryProfileTest.java \
  apps/api/src/test/java/com/projeto/cortex/auth/postgresql/PostgresqlAuthenticationSessionCasIT.java \
  apps/api/src/test/java/com/projeto/cortex/auth/AuthControllerTest.java
git commit -m "fix(auth): commit password login against current factor"
```

---

### Task 4: Commit passkey authentication, registration, and revocation atomically

**Files:**
- Create: `apps/api/src/main/java/com/projeto/cortex/auth/webauthn/WebAuthnCredentialAuthenticationSnapshot.java`
- Create: `apps/api/src/main/java/com/projeto/cortex/auth/webauthn/WebAuthnSnapshotCredentialRepository.java`
- Create: `apps/api/src/main/java/com/projeto/cortex/auth/webauthn/WebAuthnRegistrationSessionBinding.java`
- Create: `apps/api/src/main/java/com/projeto/cortex/auth/webauthn/WebAuthnSqlDialect.java`
- Create: `apps/api/src/main/java/com/projeto/cortex/auth/webauthn/PostgresqlWebAuthnSqlDialect.java`
- Create: `apps/api/src/main/java/com/projeto/cortex/auth/webauthn/MysqlWebAuthnSqlDialect.java`
- Create: `apps/api/src/main/java/com/projeto/cortex/auth/webauthn/PasskeyRevocationService.java`
- Modify: `apps/api/src/main/java/com/projeto/cortex/auth/session/AuthenticationCommitService.java`
- Modify: `apps/api/src/main/java/com/projeto/cortex/auth/session/PostgresqlAuthenticationCommitService.java`
- Modify: `apps/api/src/main/java/com/projeto/cortex/auth/session/MysqlAuthenticationCommitService.java`
- Modify: `apps/api/src/main/java/com/projeto/cortex/auth/webauthn/WebAuthnCredentialRepository.java:154-303`
- Modify: `apps/api/src/main/java/com/projeto/cortex/auth/webauthn/StoredWebAuthnChallenge.java`
- Modify: `apps/api/src/main/java/com/projeto/cortex/auth/webauthn/WebAuthnCeremonyEngine.java`
- Modify: `apps/api/src/main/java/com/projeto/cortex/auth/webauthn/YubicoWebAuthnCeremonyEngine.java`
- Modify: `apps/api/src/main/java/com/projeto/cortex/auth/webauthn/WebAuthnConfiguration.java`
- Modify: `apps/api/src/main/java/com/projeto/cortex/auth/webauthn/WebAuthnService.java:69-222`
- Modify: `apps/api/src/main/java/com/projeto/cortex/auth/webauthn/WebAuthnController.java:55-134`
- Modify: `apps/api/src/main/java/com/projeto/cortex/auth/webauthn/PasskeySummary.java`
- Test: `apps/api/src/test/java/com/projeto/cortex/auth/webauthn/WebAuthnServiceTest.java`
- Test: `apps/api/src/test/java/com/projeto/cortex/auth/webauthn/WebAuthnControllerTest.java`
- Test: `apps/api/src/test/java/com/projeto/cortex/auth/webauthn/WebAuthnCredentialRepositoryTest.java`
- Create: `apps/api/src/test/java/com/projeto/cortex/auth/webauthn/WebAuthnSqlDialectProfileTest.java`
- Test: `apps/api/src/test/java/com/projeto/cortex/auth/webauthn/PostgresqlWebAuthnCredentialPersistenceIT.java`
- Rename: `apps/api/src/test/java/com/projeto/cortex/auth/webauthn/WebAuthnMysqlIntegrationTest.java` → `apps/api/src/test/java/com/projeto/cortex/auth/webauthn/WebAuthnMysqlIT.java`
- Test: `apps/api/src/test/java/com/projeto/cortex/auth/postgresql/PostgresqlAuthenticationSessionCasIT.java`
- Create: `apps/api/src/test/java/com/projeto/cortex/auth/session/MysqlAuthenticationSessionCasIT.java`

**Interfaces:**
- Consumes: `VerifiedAuthentication.Passkey`, the database-neutral
  `AuthIdentityEpochRepository.advance(...)` with its PostgreSQL/MySQL profile
  adapters, and Task 3’s `AuthenticationCommitService` implementations.
- Produces: passkey login whose database snapshot is captured before and feeds
  the Yubico proof, then atomically re-read by PostgreSQL/MySQL CAS before
  recording the counter and session; registration challenges bound at start to
  the exact session ID/marker/epoch and committed only by that same session;
  profile-selected SQL for the three genuinely dialect-specific WebAuthn
  statements;
  registration without an epoch bump; authenticated
  `DELETE /api/auth/passkeys/{credentialRecordId}` revocation.

- [ ] **Step 1: Write passkey snapshot/CAS tests RED**

Make `WebAuthnServiceTest` prove call order, not merely the final value:

1. Parse the bounded credential ID and call
   `findAuthenticationSnapshot(credentialId, challengeOwner)`.
2. Pass that exact snapshot to `engine.finishAuthentication(...)`.
3. Only after the Yubico proof succeeds, construct the verified CAS input.

Capture the snapshot returned by the repository and assert the engine received
the same object instance. A test engine must reject a call made before the
snapshot lookup. Then expect:

```java
new VerifiedAuthentication.Passkey(
        IDENTITY,
        CREDENTIAL_RECORD_ID,
        4L,
        9L,
        12L,
        true
)
```

Verify `recordAuthentication(...)` is no longer called by `WebAuthnService`; it belongs to the commit transaction.

Add CAS IT cases where the snapshot is followed by `revogado_em`,
`versao_linha`, or identity-epoch change and assert no session is inserted.

Add a deterministic PostgreSQL barrier IT with two connections and latches:

1. Thread A loads the snapshot that will feed Yubico and signals
   `snapshotLoaded`.
2. Thread B revokes the exact credential or advances its version/identity epoch
   and commits.
3. The test releases Thread A; its proof succeeds against the **captured**
   `RegisteredCredential`, but `AuthenticationCommitService.issue(...)`
   re-reads under lock and returns empty.
4. Assert no session row and no counter/backed-up update were committed.

Repeat the CAS rejection in `MysqlAuthenticationSessionCasIT` on MySQL 8.4;
the PostgreSQL barrier is the canonical proof-order test and the MySQL test
proves dialect parity.

- [ ] **Step 2: Write registration-epoch preservation test RED**

In `PostgresqlWebAuthnCredentialPersistenceIT`, create an epoch-bound session,
start registration through the service, and assert the persisted challenge
contains that exact `sessionId`, `sessionMarker`, owner, and `authEpoch` in
addition to its client-instance binding. Record `beforeEpoch`, finish through
the session-aware repository/service path with the same resolved session, and
assert:

```java
assertThat(epoch(jdbc, collaboratorId)).isEqualTo(beforeEpoch);
assertThat(sessions.findActiveByTokenHashAndClientInstanceHash(
        tokenHash,
        clientInstanceHash
)).isPresent();
```

Task 5's `PostgresqlOfflineGrantLifecycleIT` must then call
`POST /api/auth/offline-grant` with that same cookie/client-proof/CSRF triple
and assert `200` plus a signed claim whose `authEpoch` is still `beforeEpoch`.
This is the backend handoff plan 2 needs before it can persist and re-read the
new PRF vault.

Add fail-closed cases for a pre-V89 challenge whose three session-binding
columns are null and for a challenge whose saved marker/epoch/session ID does
not equal the request's resolved session. Most importantly, use this barrier:

1. Session A starts registration and the challenge commits A's ID, marker, and
   epoch.
2. A is logged out and session B is issued for the **same collaborator**.
3. The browser submits the old challenge while authenticated as B.
4. Finish consumes/rejects the challenge, inserts no credential, and leaves B
   untouched.

This is not satisfied by reading only the session present at finish.

Create `WebAuthnSqlDialectProfileTest` in RED. Load `postgresql-common` and the
MySQL profile separately, assert exactly one `WebAuthnSqlDialect` bean in each,
and prove the PostgreSQL fragment contains `?::jsonb` plus its interval while
the MySQL fragment contains `CAST(? AS JSON)` and
`TIMESTAMPADD(SECOND, ?, CURRENT_TIMESTAMP(6))`. Both variants must expose the
same placeholder order for `createChallenge(...)` and `saveCredential(...)`.
The third fragment is the exact registration-session lock query: PostgreSQL
ends with `FOR UPDATE OF session, identity, collaborator`; MySQL ends with
plain `FOR UPDATE` and contains no `OF` clause.
No repository code may choose a dialect from a product-name string at runtime.

- [ ] **Step 3: Write revocation test RED**

Create a passkey and current session, call the revocation service with the record UUID, and assert in one committed state:

```java
assertThat(jdbc.queryForObject(
        "SELECT revogado_em IS NOT NULL FROM auth_webauthn_credential WHERE id = ?",
        Boolean.class,
        credentialRecordId
)).isTrue();
assertThat(epoch(jdbc, collaboratorId)).isEqualTo(beforeEpoch + 1);
assertThat(resolveOldSession()).isEmpty();
```

Add a forced-failure case after credential update and before commit; assert revocation, epoch, and session revocation all roll back.

In `MysqlAuthenticationSessionCasIT`, make the MySQL 8.4 proof explicit and
service-level:

1. Seed an active passkey, identity epoch `N`, and an epoch-bound cookie session.
2. Invoke the Spring-proxied `PasskeyRevocationService`; assert the credential
   has `revogado_em`, the identity is exactly `N + 1`, and resolving the old
   token/client-instance pair returns empty.
3. Add a test-only `@Primary` `AuthSessionRepository` decorator that delegates
   to `MysqlAuthSessionRepository` but can throw from
   `revokeAllByCollaboratorId(...)`. Enable the fault only after fixture setup,
   so it occurs after credential revocation and
   `MysqlAuthIdentityEpochRepository.advance(...)` inside the service
   transaction.
4. Assert the exception rolls the whole transaction back: the credential is
   still active, identity epoch remains `N`, the old cookie resolves, and no
   partial revocation/audit state remains.

This test must consume the MySQL adapter through the interface/Spring profile;
it must not instantiate the PostgreSQL adapter or emulate an epoch bump with
test SQL.

- [ ] **Step 4: Run passkey tests RED**

Run:

```bash
REPO_ROOT="$(git rev-parse --show-toplevel)"
cd "$REPO_ROOT/apps/api"
./mvnw --batch-mode \
  -Dtest=WebAuthnServiceTest,WebAuthnControllerTest,WebAuthnCredentialRepositoryTest,WebAuthnSqlDialectProfileTest test
./mvnw --batch-mode -Ppostgresql-it \
  -Dit.test=PostgresqlWebAuthnCredentialPersistenceIT,PostgresqlAuthenticationSessionCasIT verify
./mvnw --batch-mode -Pmysql-it \
  -Dit.test=MysqlAuthenticationSessionCasIT verify
```

Expected: FAIL because the passkey snapshot, version CAS, start-to-finish
session binding, SQL dialect beans, and revocation service are absent.

- [ ] **Step 5: Capture the exact passkey snapshot that feeds Yubico**

Add the complete pre-proof record:

```java
record WebAuthnCredentialAuthenticationSnapshot(
        String credentialRecordId,
        AuthenticatedIdentity identity,
        ByteArray credentialId,
        long credentialVersion,
        long authEpoch,
        RegisteredCredential registeredCredential
) {}
```

Its canonical constructor requires a canonical record/owner UUID, epoch at
least one, non-negative version, and exact equality between `credentialId` and
`registeredCredential.getCredentialId()`. Its redacted `toString()` must not
expose credential ID, user handle, public-key COSE, or counter.

Implement one repository method:

```java
Optional<WebAuthnCredentialAuthenticationSnapshot>
        findAuthenticationSnapshot(
                ByteArray credentialId,
                String expectedCollaboratorId
        );
```

with one query that returns credential record ID, owner, collaborator name and
role, `versao_linha`, `identity.auth_epoch`, `credential_id`, `user_handle`,
`public_key_cose`, signature count, transports, and backup state. Require the
expected owner, active identity/collaborator, allowed role, and
`revogado_em IS NULL` in this lookup.

Change `WebAuthnCeremonyEngine.finishAuthentication(...)` to accept that
snapshot. `YubicoWebAuthnCeremonyEngine` must create a ceremony-scoped relying
party through `WebAuthnConfiguration.buildRelyingParty(...)` and a
`WebAuthnSnapshotCredentialRepository` that exposes **only** the captured
`RegisteredCredential` and its captured owner/user handle. Do not let the
Yubico finish call perform another database lookup. The untrusted response
credential ID is only a lookup key; the signature is still accepted solely by
Yubico against the captured public key.

The exact engine contract becomes:

```java
VerifiedWebAuthnAuthentication finishAuthentication(
        String requestJson,
        JsonNode credential,
        WebAuthnCredentialAuthenticationSnapshot snapshot
);
```

`WebAuthnSnapshotCredentialRepository` implements Yubico's
`CredentialRepository`; `lookup`/`lookupAll` return the captured
`RegisteredCredential` only for exact credential-ID and user-handle matches,
and username/user-handle methods expose only `snapshot.identity().colaboradorId()`.

`finishCpfBoundAuthentication` therefore performs this exact order:

```java
WebAuthnCredentialAuthenticationSnapshot snapshot = repository
        .findAuthenticationSnapshot(responseCredentialId, challengeOwner)
        .orElseThrow(this::invalidCredential);
VerifiedWebAuthnAuthentication proof = engine.finishAuthentication(
        challenge.requestJson(),
        credentialResponse,
        snapshot
);
```

After Yubico succeeds, confirm its credential ID, user handle, and collaborator
match the snapshot and challenge, then return `VerifiedAuthentication.Passkey`
with the captured record ID/version/epoch plus the proved counter/backup state.
It no longer persists the counter itself. The later commit transaction must
re-read current database state; proof success alone never authorizes insertion.

- [ ] **Step 6: Extend both commit-service adapters for passkeys**

In both `PostgresqlAuthenticationCommitService` and
`MysqlAuthenticationCommitService`, re-read and lock the credential, identity,
and collaborator by expected record UUID, expected owner, version, epoch, and
`revogado_em IS NULL`. Rebuild current identity from the locked row and reject
any owner, role, active-state, version, or epoch mismatch. PostgreSQL uses
`FOR UPDATE OF credential, identity, collaborator`; MySQL uses `FOR UPDATE`.
Then update exactly one row:

```sql
UPDATE auth_webauthn_credential
SET signature_count = GREATEST(signature_count, ?),
    backed_up = ?,
    usado_em = CURRENT_TIMESTAMP(6),
    versao_linha = versao_linha + 1
WHERE id = ?
  AND colaborador_id = ?
  AND versao_linha = ?
  AND revogado_em IS NULL
```

Only after the update count is one, insert the epoch-bound session in the same transaction. Any mismatch returns empty and rolls back.

The locked row is the post-proof CAS read. The `credentialRecordId`, owner,
`versao_linha`, and `auth_epoch` must still equal the values captured by the
lookup that fed Yubico. The repository invariant is that every mutable
credential-material update increments `versao_linha`; add a repository test
for that invariant. The barrier IT from Step 1 must prove that a proof against
captured material cannot cross a committed revocation/version/epoch change.
`CURRENT_TIMESTAMP(6)` is intentionally used in this shared update because it
is accepted by both supported database dialects.

- [ ] **Step 7: Make registration session-aware without advancing epoch**

First isolate the PostgreSQL-specific SQL in the repository.
`WebAuthnSqlDialect` supplies the challenge insert, credential insert, and
exact-session registration lock
statements; `PostgresqlWebAuthnSqlDialect` retains `?::jsonb` and
`CURRENT_TIMESTAMP(6) + (? * INTERVAL '1 second')`, while
`MysqlWebAuthnSqlDialect` uses `CAST(? AS JSON)` and
`TIMESTAMPADD(SECOND, ?, CURRENT_TIMESTAMP(6))`. The statements must keep the
same argument order; the lock query uses the dialect-specific suffix stated
above. Wire exactly one bean with the same mutually exclusive
profiles used by the session/epoch adapters, inject it into
`WebAuthnCredentialRepository`, and keep challenge consumption, lookups,
snapshot mapping, counters, and revocation in the shared repository because
their SQL is already accepted by both databases. Do not duplicate the whole
repository or leave `?::jsonb`, interval arithmetic, or `FOR UPDATE OF` in a
shared string.

Add `CurrentUserService.requireResolvedSession()` that reads
`AuthSessionFilter.REQUEST_ATTRIBUTE_SESSION` and returns a
`ResolvedAuthSession` or 401. Pass that **same resolved session** to registration
start and finish.

At start, persist this immutable binding with the challenge:

```java
public record WebAuthnRegistrationSessionBinding(
        String sessionId,
        String collaboratorId,
        String sessionMarker,
        long authEpoch
) {}
```

Extend `createChallenge(...)` and both dialect insert statements with the
three V89/V46 challenge columns. Registration supplies the binding;
authentication supplies three nulls. Extend `StoredWebAuthnChallenge` to carry
the optional binding, but parse it all-or-none and reject any partial shape.
At finish, before asking Yubico to verify or trying an insert, require exact
equality between the stored start binding and the request's resolved session
ID, owner, marker, and epoch. A null legacy registration binding is invalid;
never substitute the finish-time session.

`saveCredentialForSession(...)` must run transactionally and re-read/lock the
**exact session row**, identity, and collaborator before inserting. Match all
values committed in the challenge and independently match the request-derived
resolved session, not just the collaborator:

```sql
session.id = :sessionId
AND session.colaborador_id = :sessionCollaboratorId
AND session.session_marker = :sessionMarker
AND session.auth_epoch = :sessionAuthEpoch
AND session.revogado_em IS NULL
AND session.expira_em > CURRENT_TIMESTAMP(6)
AND session.auth_epoch = identity.auth_epoch
AND identity.auth_epoch = :sessionAuthEpoch
AND identity.status = 'ATIVA'
AND collaborator.ativo = TRUE
AND collaborator.deletado_em IS NULL
AND collaborator.papel_acesso IN ('ALFA', 'BETA')
```

Also require that the locked session owner equals both the registration
challenge owner and its stored registration binding. PostgreSQL locks
`session`, `identity`, and `collaborator` with
`FOR UPDATE OF session, identity, collaborator`; MySQL uses `FOR UPDATE`. Only
then insert the credential with `versao_linha = 0` in the same transaction. It
does not call `epochs.advance` or revoke any session.

Add RED then GREEN integration cases that mutate, between registration start
and finish, each of: session marker, identity epoch, `revogado_em`,
`expira_em`, and session owner/challenge owner. Include logout followed by a
new login for the same owner: the new cookie/marker cannot finish the challenge
bound to the old session. Every case must return 401/409 according to the
existing endpoint policy, insert no credential, and preserve the preexisting
epoch. A successful registration must prove the exact session row remains
active and unchanged.

- [ ] **Step 8: Add self-owned passkey revocation**

Expose the stable database UUID in `PasskeySummary` as `credentialRecordId`; keep raw credential bytes redacted.

Implement:

```java
@Transactional
public void revoke(
        ResolvedAuthSession session,
        String credentialRecordId
) {
    int updated = credentials.revokeForOwner(
            credentialRecordId,
            session.collaboratorId()
    );
    if (updated != 1) {
        throw new ResponseStatusException(
                HttpStatus.NOT_FOUND,
                "Passkey ativa não encontrada."
        );
    }
    epochs.advance(session.collaboratorId());
    sessions.revokeAllByCollaboratorId(
            session.collaboratorId(),
            "PASSKEY_REVOGADA"
    );
}
```

Add:

```java
@DeleteMapping("/api/auth/passkeys/{credentialRecordId}")
@ResponseStatus(HttpStatus.NO_CONTENT)
public void revoke(
        @PathVariable String credentialRecordId,
        HttpServletRequest request,
        HttpServletResponse response
) {
    noStore(response);
    revocations.revoke(
            currentUser.requireResolvedSession(),
            credentialRecordId
    );
}
```

The existing auth and CSRF filters protect this unsafe non-public route.

- [ ] **Step 9: Route passkey login through the commit service**

`WebAuthnController.finishAuthentication` accepts the verified passkey result, calls `authenticationCommits.issue(...)`, writes cookies, and builds the profile with the complete issued session exactly like password login.

Rename `WebAuthnMysqlIntegrationTest` to `WebAuthnMysqlIT`, remove its
environment-variable skip, and convert its fixture to MySQL 8.4 Testcontainers
under `mysql-it`. It must exercise snapshot lookup, exact-session registration,
counter/version CAS, and revocation against V46.

- [ ] **Step 10: Run passkey tests GREEN**

Run:

```bash
REPO_ROOT="$(git rev-parse --show-toplevel)"
cd "$REPO_ROOT/apps/api"
./mvnw --batch-mode \
  -Dtest=WebAuthnServiceTest,YubicoWebAuthnCeremonyEngineTest,WebAuthnControllerTest,WebAuthnCredentialRepositoryTest,WebAuthnSqlDialectProfileTest,WebAuthnEndpointBoundaryTest \
  test
./mvnw --batch-mode -Ppostgresql-it \
  -Dit.test=PostgresqlWebAuthnCredentialPersistenceIT,PostgresqlAuthenticationSessionCasIT,PostgresqlCpfPasskeyIdentityLookupIT \
  verify
./mvnw --batch-mode -Pmysql-it \
  -Dit.test=WebAuthnMysqlIT,MysqlAuthenticationSessionCasIT verify
```

Expected: PASS with zero skipped MySQL tests; the barrier proves the proof/CAS
ordering, registration preserves the exact epoch/session, and revocation
advances epoch and invalidates the old cookie. The MySQL IT additionally proves
both JSON/TTL insert fragments against MySQL 8.4, rejection of a challenge
started by an older same-owner session, an exact `N + 1` advance, and full
rollback when session cleanup fails after the epoch adapter ran.

- [ ] **Step 11: Commit passkey CAS and revocation**

```bash
REPO_ROOT="$(git rev-parse --show-toplevel)"
cd "$REPO_ROOT"
git add apps/api/src/main/java/com/projeto/cortex/auth/webauthn \
  apps/api/src/main/java/com/projeto/cortex/auth/session/AuthenticationCommitService.java \
  apps/api/src/main/java/com/projeto/cortex/auth/session/PostgresqlAuthenticationCommitService.java \
  apps/api/src/main/java/com/projeto/cortex/auth/session/MysqlAuthenticationCommitService.java \
  apps/api/src/main/java/com/projeto/cortex/auth/CurrentUserService.java \
  apps/api/src/test/java/com/projeto/cortex/auth/webauthn \
  apps/api/src/test/java/com/projeto/cortex/auth/postgresql/PostgresqlAuthenticationSessionCasIT.java \
  apps/api/src/test/java/com/projeto/cortex/auth/session/MysqlAuthenticationSessionCasIT.java
git commit -m "feat(auth): bind passkeys to global epoch"
```

---

### Task 5: Make offline-grant issuance authoritative to the resolved session

**Files:**
- Modify: `apps/api/src/main/java/com/projeto/cortex/auth/offline/OfflineGrantController.java:1-41`
- Modify: `apps/api/src/main/java/com/projeto/cortex/auth/offline/OfflineGrantService.java:16-157`
- Modify: `apps/api/src/main/java/com/projeto/cortex/auth/offline/OfflineGrantSubject.java:5-20`
- Modify: `apps/api/src/main/java/com/projeto/cortex/auth/offline/OfflineGrantSubjectRepository.java:9-56`
- Modify: `apps/api/src/test/java/com/projeto/cortex/auth/offline/OfflineGrantControllerTest.java`
- Modify: `apps/api/src/test/java/com/projeto/cortex/auth/offline/OfflineGrantServiceTest.java`
- Modify: `apps/api/src/test/java/com/projeto/cortex/auth/offline/OfflineGrantSubjectRepositoryTest.java`
- Modify: `apps/api/src/test/java/com/projeto/cortex/auth/offline/OfflineGrantEndpointBoundaryTest.java`
- Create: `apps/api/src/test/java/com/projeto/cortex/auth/offline/PostgresqlOfflineGrantLifecycleIT.java`
- Create: `apps/api/src/test/java/com/projeto/cortex/auth/offline/MysqlOfflineGrantLifecycleIT.java`

**Interfaces:**
- Consumes: epoch-bound `ResolvedAuthSession` and global identity epoch from Tasks 1–4.
- Produces: `OfflineGrantService.issue(ResolvedAuthSession)` and a subject repository that accepts passkey-only identities while rejecting stale/null-epoch sessions.

Use this complete, request-cache-independent snapshot:

```java
record OfflineGrantSubject(
        String nome,
        PapelAcesso papelAcesso,
        Optional<Set<String>> obraIds,
        Instant databaseNow,
        long authEpoch
) {}

Optional<OfflineGrantSubject> findActive(ResolvedAuthSession session);
```

`OfflineGrantService` must no longer ask `CurrentUserService` for role or worksites
after locating the subject; that request-scoped cache is not an authoritative
snapshot for grant signing.

- [ ] **Step 1: Write controller/service tests RED**

Make the controller test attach a `ResolvedAuthSession` request attribute and verify:

```java
verify(grants).issue(resolvedSession);
```

Make service tests reject a subject/session epoch mismatch and sign `subject.authEpoch()` only when equal.

- [ ] **Step 2: Write repository SQL tests RED**

Update `OfflineGrantSubjectRepositoryTest` to require SQL containing:

```text
FROM auth_session session
JOIN auth_identity identity
session.auth_epoch = identity.auth_epoch
session.auth_epoch IS NOT NULL
session.session_marker IS NOT NULL
```

and explicitly assert it no longer contains `JOIN auth_password_credential`.

- [ ] **Step 3: Write the real endpoint lifecycle IT RED**

Use `@SpringBootTest`, `@AutoConfigureMockMvc`, PostgreSQL 18 Testcontainers, real Flyway migrations, real Argon2 service, and a test offline signing key. Exercise:

```java
MvcResult login = mvc.perform(post("/api/auth/login")
        .header(ClientInstanceProof.HEADER, rawClientInstance)
        .contentType(MediaType.APPLICATION_JSON)
        .content(loginJson))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.sessionMarker").isString())
        .andReturn();

mvc.perform(post("/api/auth/offline-grant")
        .header(ClientInstanceProof.HEADER, rawClientInstance)
        .header(CsrfRequestFilter.CSRF_HEADER, rawCsrf)
        .cookie(sessionCookie, csrfCookie))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.payload").isString())
        .andExpect(header().string("Cache-Control", "no-store"));
```

Decode and verify the signed payload with the real public key and assert `authEpoch` equals the current identity row.

Create the MySQL counterpart with a real MySQL 8.4 Testcontainer and the shared
schema migrated through V46. Because that schema has no password table, seed a
passkey-only active identity and issue a current epoch/marker session through
the MySQL session repository. Exercise the same empty-body endpoint with a real
cookie, client-instance proof, CSRF triple, and test signing key. In three
isolated fixtures, first obtain and verify a signed grant, then transition the
identity to `BLOQUEADA`, the collaborator to inactive, or the collaborator to
deleted. Each transition must advance epoch and make both old-cookie resolution
and a second offline-grant request return unauthorized. This is a non-skipped
endpoint/repository proof, not merely a migration SQL assertion.

- [ ] **Step 4: Run offline tests RED**

Run:

```bash
REPO_ROOT="$(git rev-parse --show-toplevel)"
cd "$REPO_ROOT/apps/api"
./mvnw --batch-mode \
  -Dtest=OfflineGrantControllerTest,OfflineGrantServiceTest,OfflineGrantSubjectRepositoryTest,OfflineGrantEndpointBoundaryTest \
  test
./mvnw --batch-mode -Ppostgresql-it \
  -Dit.test=PostgresqlOfflineGrantLifecycleIT verify
./mvnw --batch-mode -Pmysql-it \
  -Dit.test=MysqlOfflineGrantLifecycleIT verify
```

Expected: FAIL because issuance still takes only a collaborator UUID and the repository still requires a password credential.

- [ ] **Step 5: Pass the exact resolved session into issuance**

In the controller, obtain:

```java
ResolvedAuthSession session = currentUsers.requireResolvedSession();
return grants.issue(session);
```

Change the service signature to:

```java
@Transactional(
        isolation = Isolation.REPEATABLE_READ
)
public OfflineGrant issue(ResolvedAuthSession session)
```

Reject null with 401 before any authorization work. This transaction is
deliberately **not** `readOnly`: the subject query takes `FOR SHARE` locks and
must run on the primary with lock-capable PostgreSQL/MySQL semantics. It remains
application-write-free, but must not be routed to a replica or rejected by a
read-only transaction. Add a reflection/transaction probe in
`OfflineGrantServiceTest` that asserts `REPEATABLE_READ` and
`readOnly == false`.

- [ ] **Step 6: Read subject and session epoch in one locked query**

Replace password ownership with:

```sql
SELECT collaborator.nome,
       collaborator.papel_acesso,
       identity.auth_epoch,
       CURRENT_TIMESTAMP AS database_now
FROM auth_session session
JOIN auth_identity identity
  ON identity.colaborador_id = session.colaborador_id
JOIN colaborador collaborator
  ON collaborator.id = identity.colaborador_id
WHERE session.id = ?
  AND session.colaborador_id = ?
  AND session.auth_epoch IS NOT NULL
  AND session.session_marker IS NOT NULL
  AND session.auth_epoch = identity.auth_epoch
  AND session.auth_epoch = ?
  AND session.revogado_em IS NULL
  AND session.expira_em > CURRENT_TIMESTAMP
  AND identity.status = 'ATIVA'
  AND collaborator.ativo = TRUE
  AND collaborator.deletado_em IS NULL
  AND collaborator.papel_acesso IN ('ALFA', 'BETA')
FOR SHARE
```

Map the locked role directly. For BETA, query
`AutorizacaoDeObra.OBRAS_ALCANCADAS` directly in this repository during the
same `REPEATABLE_READ` transaction and return `Optional.of(Set.copyOf(rows))`;
for ALFA return `Optional.empty()`. Do not call the request-memoized
`CurrentUserService.allowedObraIds(...)`. The first locked query is the
linearization point: its repeatable snapshot supplies the complete worksite
scope, and locks prevent epoch/status/role mutation until signing completes.
Passkey-only identities work because no password join remains.

- [ ] **Step 7: Expand lifecycle IT across revocation boundaries**

Using the same cookie/client proof/CSRF triple, prove separately in PostgreSQL:

1. Password reset advances identity epoch; old cookie receives 401 and cannot obtain a new grant.
2. Passkey revocation advances identity epoch; old cookie receives 401.
3. Direct `UPDATE auth_identity SET auth_epoch = auth_epoch + 1` without `revokeAll` makes the old cookie receive 401.
4. A manually inserted V88-style session with null epoch/marker receives 401.
5. An active passkey-only identity can authenticate and receive a grant.
6. Blocked identity, inactive collaborator, missing CSRF, and wrong client-instance proof are refused.

In `MysqlOfflineGrantLifecycleIT`, prove the narrower but security-critical
parity set against the real shared schema: before each lifecycle transition the
passkey-only current cookie obtains a signed grant; after `BLOQUEADA`, inactive,
or deleted, the trigger-advanced epoch makes the old cookie fail and no new
grant is issued. Assert the persisted epoch moved, not just the HTTP status.

- [ ] **Step 8: Run offline tests GREEN**

Run:

```bash
REPO_ROOT="$(git rev-parse --show-toplevel)"
cd "$REPO_ROOT/apps/api"
./mvnw --batch-mode \
  -Dtest=OfflineGrantControllerTest,OfflineGrantServiceTest,OfflineGrantSubjectRepositoryTest,OfflineGrantEndpointBoundaryTest,AuthSessionFilterTest,CsrfRequestFilterTest \
  test
./mvnw --batch-mode -Ppostgresql-it \
  -Dit.test=PostgresqlOfflineGrantLifecycleIT,PostgresqlAuthSessionRepositoryIT,PostgresqlPasswordPersistenceIT,PostgresqlWebAuthnCredentialPersistenceIT \
  verify
./mvnw --batch-mode -Pmysql-it \
  -Dit.test=MysqlOfflineGrantLifecycleIT,MysqlAuthenticationSessionCasIT \
  verify
```

Expected: PASS; stale/null sessions are rejected before grant issuance,
passkey-only identity no longer depends on a password row, and both PostgreSQL
and MySQL invalidate old cookie/grant authority after every lifecycle transition.

- [ ] **Step 9: Commit the authoritative offline-grant boundary**

```bash
REPO_ROOT="$(git rev-parse --show-toplevel)"
cd "$REPO_ROOT"
git add apps/api/src/main/java/com/projeto/cortex/auth/offline \
  apps/api/src/main/java/com/projeto/cortex/auth/CurrentUserService.java \
  apps/api/src/test/java/com/projeto/cortex/auth/offline
git commit -m "fix(auth): issue offline grants from current sessions"
```

---

### Task 6: Close backend regressions and release-gate contracts

**Files:**
- Modify: all API tests reported by constructor/signature compilation failures.
- Modify: `.github/workflows/api-ci.yml:17-103`
- Modify: `.github/workflows/production.yml`
- Modify: `apps/api/pom.xml:171-210`
- Modify: `scripts/security/test-production-publication.sh`
- Modify: `scripts/security/test-production-workflow-contract.sh`
- Modify: `scripts/security/test-production-workflow-contract-regressions.sh`
- Verify: `apps/api/src/test/java/com/projeto/cortex/config/MavenDatabaseItProfileSelectionTest.java`
- Verify: `apps/api/src/test/java/com/projeto/cortex/auth/session/AuthSessionMysqlIT.java`
- Verify: `apps/api/src/test/java/com/projeto/cortex/auth/webauthn/WebAuthnMysqlIT.java`
- Verify: `apps/api/src/test/java/com/projeto/cortex/auth/session/MysqlV46GlobalAuthEpochUpgradeIT.java`
- Verify: `apps/api/src/test/java/com/projeto/cortex/auth/session/MysqlAuthenticationSessionCasIT.java`
- Verify: `apps/api/src/test/java/com/projeto/cortex/auth/offline/MysqlOfflineGrantLifecycleIT.java`
- Verify only: `docs/superpowers/specs/2026-08-17-automatic-offline-grant-renewal-design.md:491-523`

**Interfaces:**
- Consumes: complete backend behavior from Tasks 1–5.
- Produces: one internally consistent backend revision ready for plan 2, with
  non-skipped PostgreSQL 18 and MySQL 8.4 release gates and no browser capsule
  or sync-reconciliation implementation mixed into it.

- [ ] **Step 1: Write the MySQL CI/release contract in RED**

Extend the production publication/workflow contract scripts and their negative
fixtures to require:

- `api-ci.yml` has a `mysql-integration` job that runs
  `./mvnw --batch-mode -Pmysql-it verify` and `container-build.needs` includes
  `mysql-integration`.
- `production.yml` has a `mysql-gate` job with the same real profile and
  `publish.needs` includes `mysql-gate`.
- Neither job sets a skip flag, conditional environment enablement, nor
  `continue-on-error`.
- The Maven `mysql-it` profile uses Failsafe and non-skipped MySQL 8.4
  Testcontainers fixtures; it cannot select PostgreSQL IT classes.

Run before editing workflows:

```bash
REPO_ROOT="$(git rev-parse --show-toplevel)"
cd "$REPO_ROOT"
bash scripts/security/test-production-workflow-contract.sh
bash scripts/security/test-production-workflow-contract-regressions.sh
bash scripts/security/test-production-publication.sh
```

Expected: FAIL because the MySQL jobs and required `needs` edges do not exist.

- [ ] **Step 2: Add a non-skipped MySQL 8.4 job to CI and production**

In `api-ci.yml`, add:

```yaml
mysql-integration:
  name: API · MySQL 8.4 integration suite
  runs-on: ubuntu-latest
  needs: [api-test]
  defaults:
    run:
      working-directory: apps/api
  steps:
    - uses: actions/checkout@fbc6f3992d24b796d5a048ff273f7fcc4a7b6c09 # v5
    - uses: actions/setup-java@03ad4de0992f5dab5e18fcb136590ce7c4a0ac95 # v5
      with:
        distribution: temurin
        java-version: 21
        cache: maven
    - name: Run disposable MySQL 8.4 migrations and integration tests
      run: ./mvnw --batch-mode -Pmysql-it verify
```

Add `mysql-integration` to `container-build.needs`. Add an equivalent
`mysql-gate` to `production.yml`, and add it to `publish.needs`. The tests own
their `mysql:8.4` Testcontainers lifecycle; no external password, service, or
environment gate is needed. Keep all actions pinned to the reviewed SHAs.

Do not rename fixtures in this task: Tasks 2 and 4 already renamed them once,
removed `@EnabledIfEnvironmentVariable`, and moved them to Testcontainers.
Run `MavenDatabaseItProfileSelectionTest` here to enforce that
`postgresql-it` excludes both MySQL patterns and `mysql-it` contains only those
patterns.

- [ ] **Step 3: Run the complete Java unit suite**

Run:

```bash
REPO_ROOT="$(git rev-parse --show-toplevel)"
cd "$REPO_ROOT/apps/api"
./mvnw --batch-mode test
```

Expected: PASS. If a constructor failure names `IssuedAuthSession`, `ResolvedAuthSession`, or `AuthSessionResponse`, update that fixture with an explicit 43-character marker and epoch; do not add insecure compatibility constructors.

- [ ] **Step 4: Run the complete PostgreSQL and MySQL integration suites**

Run:

```bash
REPO_ROOT="$(git rev-parse --show-toplevel)"
cd "$REPO_ROOT/apps/api"
./mvnw --batch-mode -Ppostgresql-it verify
./mvnw --batch-mode -Pmysql-it verify
```

Expected: PASS with zero skipped MySQL tests, including V89/V46 migrations,
PostgreSQL password/passkey CAS, MySQL passkey CAS, exact-session registration,
and the full PostgreSQL plus MySQL lifecycle invalidation boundary for offline
grants.

- [ ] **Step 5: Verify migration, workflow, and secret hygiene statically**

Run:

```bash
REPO_ROOT="$(git rev-parse --show-toplevel)"
cd "$REPO_ROOT"
bash scripts/security/scan-cortex-secrets.sh
bash scripts/security/test-neon-migration-contract.sh
bash scripts/security/test-hosted-deployment-contract.sh
bash scripts/security/test-production-workflow-contract.sh
bash scripts/security/test-production-workflow-contract-regressions.sh
bash scripts/security/test-production-publication.sh
git diff --check
git diff --check origin/develop...HEAD
```

Expected: all commands exit 0; both the unstaged/worktree diff and the complete
`origin/develop...HEAD` revision range are whitespace-clean; no secret, raw
token, private key, password, or CPF fixture appears outside test-only
synthetic values.

- [ ] **Step 6: Verify the exact changed surface**

Run:

```bash
REPO_ROOT="$(git rev-parse --show-toplevel)"
cd "$REPO_ROOT"
git status --short
git diff --stat origin/develop...HEAD
git diff --name-only origin/develop...HEAD | sort
```

Expected: backend source, backend tests, V89/V46 migrations, configuration guards, and this plan only. No `apps/web/src`, operational outbox, RDO, sync, PDOR, Financeiro, or deployment credential file changes.

- [ ] **Step 7: Perform the spec acceptance checklist**

Confirm from tests and code:

- V89 bumps every identity once and uses the maximum known V88 password epoch.
- The pre-V89 revoked-passkey fixture is epoch 2 after cutover; plan 2 consumes
  this boundary to prove its historical epoch-1 PRF vault cannot use a new
  capsule.
- V88-era session rows remain null and fail closed.
- Activation readiness and clean-start require/apply V89, while the V61
  upgrade fixture proves its historical unbound rows before exercising the
  current V89-aware repository.
- Every new session has a unique marker and global epoch.
- Same cookie returns the same marker; new authentication returns another.
- Every direct issuer and both session-profile resolvers consume the complete
  `IssuedAuthSession`; no removed `issue(...)` or `(identity, expiresAt)` call
  remains after Task 2.
- Password/passkey factor version and epoch are re-read under lock before insert.
- The passkey credential/version/epoch snapshot is captured by the exact lookup
  that feeds Yubico, and a deterministic barrier proves post-proof CAS rejects
  concurrent revocation/version/epoch changes on PostgreSQL; MySQL proves the
  same rejection semantics.
- Password reset and passkey revocation advance global epoch transactionally.
- PostgreSQL and MySQL wire exactly one dialect-owned epoch adapter; MySQL uses
  `SELECT ... FOR UPDATE` plus a checked `UPDATE`, never PostgreSQL
  `UPDATE ... RETURNING`.
- MySQL passkey revocation proves epoch `N + 1`, old-cookie rejection, and full
  credential/epoch/session rollback after an injected post-advance failure.
- Passkey registration preserves epoch/session.
- Passkey registration re-locks and revalidates the exact session ID, owner,
  marker, epoch, revocation, and expiration before insertion.
- Direct epoch bump invalidates a cookie without relying on explicit session revocation.
- On both PostgreSQL and MySQL, isolated identity blocking, collaborator
  deactivation, and collaborator deletion advance the global epoch; a real
  old-cookie endpoint journey proves no subsequent grant can be issued.
- Offline grant compares session and identity epoch in the authoritative query.
- Offline grant still requires cookie, instance proof, CSRF, active authorization, and uses no request credential body.
- Passkey-only identity can receive the signed grant.
- MySQL V46 seeds every preexisting identity to exactly epoch 2 without
  referencing a nonexistent password table, installs the lifecycle triggers,
  and all MySQL tests are non-skipped.
- CI container build and production publication both require the MySQL gate.
- Plan 1 does not create or source the PWA signing-key keyring; plan 2 owns it.
- No browser capsule, outbox, namespace replay, or UI behavior was implemented in plan 1.

- [ ] **Step 8: Commit final backend test/contract corrections**

If Steps 1–7 required fixture or guard corrections, commit only those corrections:

```bash
REPO_ROOT="$(git rev-parse --show-toplevel)"
cd "$REPO_ROOT"
git add apps/api docs/superpowers/plans/2026-08-17-automatic-offline-renewal-01-auth-epoch-session.md
git add .github/workflows/api-ci.yml .github/workflows/production.yml \
  scripts/security/test-production-publication.sh \
  scripts/security/test-production-workflow-contract.sh \
  scripts/security/test-production-workflow-contract-regressions.sh
git commit -m "test(auth): prove global epoch session boundary"
```

If the worktree is already clean after the prior task commits, do not create an empty commit.

---

## Plan 1 Completion Boundary

Plan 1 is complete only when the same local revision has:

```bash
REPO_ROOT="$(git rev-parse --show-toplevel)"
cd "$REPO_ROOT/apps/api"
./mvnw --batch-mode test
./mvnw --batch-mode -Ppostgresql-it verify
./mvnw --batch-mode -Pmysql-it verify

cd "$REPO_ROOT"
bash scripts/security/scan-cortex-secrets.sh
bash scripts/security/test-neon-migration-contract.sh
bash scripts/security/test-hosted-deployment-contract.sh
bash scripts/security/test-production-workflow-contract.sh
bash scripts/security/test-production-workflow-contract-regressions.sh
bash scripts/security/test-production-publication.sh
git diff --check
git diff --check origin/develop...HEAD
```

Expected: every command exits 0 on the same revision, both database profiles
report zero skipped selected ITs, and the workflow contracts prove publication
depends on both database gates.

Completion of this plan does **not** prove silent renewal in a real browser. It only gives plan 2 the secure backend primitives it consumes: global `authEpoch`, epoch-bound cookie session, stable `sessionMarker`, passkey/password CAS, revocation semantics, and a session-authoritative offline-grant endpoint.
