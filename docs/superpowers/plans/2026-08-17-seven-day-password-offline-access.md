# Córtex Seven-Day Password Offline Access Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace collaborative CPF-only offline unlock with a seven-day, password-encrypted local vault while preserving passkey unlock, legacy v2 rollback data, server-side authorization, and idempotent reconnect behavior.

**Architecture:** PostgreSQL owns a monotonic `auth_epoch` on the existing Argon2id password credential and embeds it in a version-2 signed grant with a strict seven-day ceiling. The browser stores a version-3 AES-256-GCM envelope whose key is derived from the just-validated password with PBKDF2-HMAC-SHA-256; identity, scope, grant, epoch, and trusted clock remain only inside ciphertext. Existing collaborative v2 records remain untouched and bounded to their signed 24-hour grant, but the new application does not silently promote or renew them; passkey/PRF vaults remain independent.

**Tech Stack:** Java 21, Spring Boot 3.5, PostgreSQL 18/Flyway, JUnit 5, Testcontainers, React 19, TypeScript, Web Crypto, IndexedDB/idb, Vitest, Vite PWA.

**Spec:** `docs/superpowers/specs/2026-08-17-seven-day-password-offline-access-design.md`

## Global Constraints

- Maximum new offline-grant lifetime is exactly `604800` seconds; legacy grant protocol version 1 remains capped at `86400` seconds.
- First offline access on a new browser is impossible; version 3 is created only after a successful online CPF + password login.
- Server password storage remains Argon2id; browser key derivation is PBKDF2-HMAC-SHA-256 with exactly `600000` iterations and a random 16-byte salt.
- Collaborative ciphertext uses AES-256-GCM with a fresh random 12-byte IV on every seal and a 128-bit authentication tag.
- Password, Argon2id hash, reusable password verifier, signed collaborative grant, name, role, worksite IDs, `authEpoch`, scope fingerprint, and `lastTrustedTime` never appear in clear browser persistence.
- The persisted CPF verifier may locate at most 20 candidate records and must use an independent random 16-byte salt and PBKDF2-HMAC-SHA-256 with `600000` iterations.
- CPF mismatch, password mismatch, missing vault, tampering, signature failure, epoch failure, expiry, and clock rollback expose one generic offline-unlock message.
- Five failed local attempts within a 15-minute window block more attempts until the window ends; each earlier failure records an exponentially increasing not-before time.
- Clock rollback tolerance remains exactly five minutes.
- Version-2 collaborative records are neither deleted before a confirmed v3 write nor renewed/extended by the new runtime.
- Existing passkey/PRF vault behavior and storage format remain unchanged.
- MySQL Academy and Zeladoria remain read-only external sources and receive no password, epoch, or per-user schema change.
- A synchronized transport result is not financial acceptance; reconnect continues through current server authorization, idempotency, evidence, and PDOR rules.
- Tests, local build, PostgreSQL integration, CI, deployment, and authenticated browser acceptance must be reported as separate evidence layers.

## File Map

- `apps/api/src/main/resources/db/migration-postgresql/V88__offline_password_vault_authorization_epoch.sql`: adds and constrains `auth_epoch`, plus database-owned epoch bumps for protected identity or collaborator inactivation.
- `apps/api/src/main/java/com/projeto/cortex/auth/password/PasswordCredentialRepository.java`: exposes hash rotation and explicit epoch invalidation.
- `apps/api/src/main/java/com/projeto/cortex/auth/password/PostgresqlPasswordCredentialRepository.java`: performs atomic hash/epoch writes and reads.
- `apps/api/src/main/java/com/projeto/cortex/auth/password/PasswordSetupService.java`: invalidates offline epochs at reset issuance and rotates them at successful setup/reset.
- `apps/api/src/main/java/com/projeto/cortex/auth/offline/OfflineGrantClaims.java`: adds protocol version 2 and `authEpoch`.
- `apps/api/src/main/java/com/projeto/cortex/auth/offline/OfflineGrantSubject.java`: carries the database-owned epoch with the active identity snapshot.
- `apps/api/src/main/java/com/projeto/cortex/auth/offline/OfflineGrantSubjectRepository.java`: joins active identity and active password credential before grant issuance.
- `apps/api/src/main/java/com/projeto/cortex/auth/offline/OfflineGrantService.java`: issues a strict seven-day version-2 grant.
- `apps/api/src/main/java/com/projeto/cortex/auth/offline/OfflineGrantSigner.java`: serializes exact version-2 claims including `authEpoch`.
- `apps/web/src/features/auth/offlineVault.types.ts`: defines legacy grant v1, current grant v2, collaborative metadata v2, and password-vault metadata v3.
- `apps/web/src/features/auth/offlineVault.ts`: verifies both strict protocol versions with different lifetime ceilings while leaving passkey behavior intact.
- `apps/web/src/features/auth/passwordOfflineVault.ts`: owns PBKDF2 password derivation, AES-GCM sealing/opening, trusted-clock checks, and in-memory non-extractable keys.
- `apps/web/src/features/auth/offlineVaultRepository.ts`: stores the v2/v3 union without indexing encrypted owner data and supports confirmed v2-to-v3 replacement.
- `apps/web/src/features/auth/collaborativeOfflineGrant.ts`: locates by CPF, orchestrates v3 create/unlock, emits generic errors, and preserves v2 until v3 persistence succeeds.
- `apps/web/src/features/auth/authService.ts`: passes the validated password directly into vault preparation and drops the reference after the awaited call.
- `apps/web/src/features/auth/OfflineUnlockPage.tsx`: collects CPF + password, retains passkey fallback, and presents generic failure/first-online guidance.
- `apps/web/src/features/auth/renovacaoDoGrantOffline.ts`: renews only with a live in-memory vault key and reports when reauthentication is required.
- `apps/web/src/features/auth/OfflineGrantRenewalPrompt.tsx`: performs explicit CPF + password reauthentication before expiry when the key was lost on reload.
- `apps/web/src/App.tsx`: displays the renewal prompt and keeps offline initialization/reconnect boundaries intact.
- Environment, compose, deploy, runbook, and security-contract files: publish the exact `604800` policy consistently.

---

### Task 1: Persist and Rotate the Server Authorization Epoch

**Files:**
- Create: `apps/api/src/main/resources/db/migration-postgresql/V88__offline_password_vault_authorization_epoch.sql`
- Modify: `apps/api/src/main/java/com/projeto/cortex/auth/password/PasswordCredentialRepository.java`
- Modify: `apps/api/src/main/java/com/projeto/cortex/auth/password/PostgresqlPasswordCredentialRepository.java`
- Modify: `apps/api/src/main/java/com/projeto/cortex/auth/password/PasswordSetupService.java`
- Modify: `apps/api/src/main/java/com/projeto/cortex/config/PostgresqlSchemaVersion.java`
- Modify: `apps/api/src/main/resources/application-postgresql-common.yml`
- Test: `apps/api/src/test/java/com/projeto/cortex/auth/password/PasswordSecurityMigrationTest.java`
- Test: `apps/api/src/test/java/com/projeto/cortex/auth/password/PostgresqlPasswordPersistenceIT.java`
- Test: `apps/api/src/test/java/com/projeto/cortex/auth/password/PasswordSetupServiceTest.java`
- Test: PostgreSQL readiness/schema contract tests that assert version 87.

**Interfaces:**
- Consumes: existing Argon2id `password_hash`, `auth_identity.status`, and transactional password setup flow.
- Produces: `long rotateHashAndEpoch(String collaboratorId, String passwordHash)`, `long invalidateEpoch(String collaboratorId)`, and `Optional<Long> findAuthEpochByCollaboratorId(String collaboratorId)`.

- [ ] **Step 1: Write failing migration and repository tests**

Add assertions that V88 contains `auth_epoch bigint NOT NULL DEFAULT 1`, `CHECK (auth_epoch >= 1)`, a trigger that increments epoch when an Academy identity leaves `ATIVA`, and a collaborator trigger that increments when an otherwise-active identity loses `colaborador.ativo` or gains `deletado_em`. Extend the PostgreSQL IT with:

```java
long firstEpoch = credentials.rotateHashAndEpoch(targetId, encoded);
assertThat(firstEpoch).isEqualTo(2L);
assertThat(credentials.findAuthEpochByCollaboratorId(targetId)).contains(2L);

long invalidated = credentials.invalidateEpoch(targetId);
assertThat(invalidated).isEqualTo(3L);

jdbc.update("UPDATE auth_identity SET status = 'BLOQUEADA' WHERE colaborador_id = ?", targetId);
assertThat(credentials.findAuthEpochByCollaboratorId(targetId)).contains(4L);
```

Update `PasswordSetupServiceTest` so reset-code issuance expects `invalidateEpoch(targetId)` before session revocation, and successful completion expects `rotateHashAndEpoch(...)` rather than the old void upsert.

- [ ] **Step 2: Run the focused tests and confirm RED**

```bash
cd apps/api
./mvnw -q -Dtest='com.projeto.cortex.auth.password.PasswordSecurityMigrationTest,com.projeto.cortex.auth.password.PasswordSetupServiceTest' test
```

Expected: compilation/test failures because V88 and the epoch repository methods do not exist.

- [ ] **Step 3: Implement V88 and atomic repository methods**

Use a new migration; never edit V87. The repository SQL must use `RETURNING auth_epoch`:

```java
long rotateHashAndEpoch(String collaboratorId, String passwordHash);
long invalidateEpoch(String collaboratorId);
Optional<Long> findAuthEpochByCollaboratorId(String collaboratorId);
```

For a new credential, insert `auth_epoch = 1`; for a replacement use `auth_epoch = auth_password_credential.auth_epoch + 1`. `invalidateEpoch` performs `UPDATE ... SET auth_epoch = auth_epoch + 1 ... RETURNING auth_epoch` and fails closed if the credential does not exist. The identity trigger increments only on a transition from `ATIVA` to another status. The collaborator trigger increments only when the linked identity is still `ATIVA`, so the Academy import sequence that first revokes the identity and then deactivates the collaborator cannot double-bump the epoch.

- [ ] **Step 4: Advance the required schema version to 88**

Set `PostgresqlSchemaVersion.REQUIRED` and `cortex.postgresql.required-schema-version` to `88`, then replace exact V87 expectations in readiness, configuration, and clean-start tests with V88.

- [ ] **Step 5: Run unit and real PostgreSQL tests**

```bash
cd apps/api
./mvnw -q -Dtest='com.projeto.cortex.auth.password.PasswordSecurityMigrationTest,com.projeto.cortex.auth.password.PasswordSetupServiceTest' test
./mvnw -q -Ppostgresql-it -Dtest='com.projeto.cortex.auth.password.PasswordSecurityMigrationTest' -Dit.test='com.projeto.cortex.auth.password.PostgresqlPasswordPersistenceIT' verify
```

Expected: unit tests pass and PostgreSQL 18 applies 46 migrations through V88.

- [ ] **Step 6: Commit**

```bash
git add apps/api/src/main/resources/db/migration-postgresql/V88__offline_password_vault_authorization_epoch.sql apps/api/src/main/java/com/projeto/cortex/auth/password apps/api/src/main/java/com/projeto/cortex/config/PostgresqlSchemaVersion.java apps/api/src/main/resources/application-postgresql-common.yml apps/api/src/test
git commit -m "feat(auth): version offline authorization epochs"
```

### Task 2: Issue Strict Version-2 Seven-Day Grants

**Files:**
- Modify: `apps/api/src/main/java/com/projeto/cortex/auth/offline/OfflineGrantClaims.java`
- Modify: `apps/api/src/main/java/com/projeto/cortex/auth/offline/OfflineGrantSubject.java`
- Modify: `apps/api/src/main/java/com/projeto/cortex/auth/offline/OfflineGrantSubjectRepository.java`
- Modify: `apps/api/src/main/java/com/projeto/cortex/auth/offline/OfflineGrantProperties.java`
- Modify: `apps/api/src/main/java/com/projeto/cortex/auth/offline/OfflineGrantService.java`
- Modify: `apps/api/src/main/java/com/projeto/cortex/auth/offline/OfflineGrantSigner.java`
- Test: `apps/api/src/test/java/com/projeto/cortex/auth/offline/OfflineGrantServiceTest.java`
- Test: `apps/api/src/test/java/com/projeto/cortex/auth/offline/OfflineGrantSignerTest.java`
- Test: `apps/api/src/test/java/com/projeto/cortex/auth/offline/OfflineGrantSubjectRepositoryTest.java`
- Test: `apps/api/src/test/java/com/projeto/cortex/auth/offline/OfflineGrantRedactionTest.java`

**Interfaces:**
- Consumes: `OfflineGrantSubject(nome, databaseNow, authEpoch)` from an active Academy identity/password credential.
- Produces: exact JSON claims `{versao:2,...,expiraEm,authEpoch}` signed by the existing RSA key.

- [ ] **Step 1: Write failing exact-claim and lifetime tests**

```java
assertThat(claims.fieldNames()).toIterable().containsExactly(
        "versao", "colaboradorId", "nome", "papelAcesso",
        "escopoGlobal", "obraIds", "emitidoEm", "expiraEm", "authEpoch"
);
assertThat(claims.path("versao").asInt()).isEqualTo(2);
assertThat(claims.path("authEpoch").asLong()).isEqualTo(7L);
assertThat(claims.path("expiraEm").asText())
        .isEqualTo("2030-01-09T03:04:05.123456Z");
```

Change the boundary test to accept `604800` and reject `604801`. Make the subject repository test require active `colaborador`, active `auth_identity`, and a credential with `auth_epoch`.

- [ ] **Step 2: Run focused tests and confirm RED**

```bash
cd apps/api
./mvnw -q -Dtest='com.projeto.cortex.auth.offline.OfflineGrantServiceTest,com.projeto.cortex.auth.offline.OfflineGrantSignerTest,com.projeto.cortex.auth.offline.OfflineGrantSubjectRepositoryTest' test
```

Expected: failures show one-day constants and missing epoch/protocol fields.

- [ ] **Step 3: Implement claims, query, serialization, and ceiling**

Set `OfflineGrantProperties.MAX_TTL_SECONDS = 604_800`, default `ttlSeconds` to that value, and return this exact validation text: `cortex.auth.offline-grant.ttl-seconds deve estar entre 1 e 604800.`. Add `long authEpoch` to the subject and claims records, require it to be at least 1, and serialize it after `expiraEm`.

- [ ] **Step 4: Run focused tests and commit**

```bash
cd apps/api
./mvnw -q -Dtest='com.projeto.cortex.auth.offline.*' test
cd ../..
git add apps/api/src/main/java/com/projeto/cortex/auth/offline apps/api/src/test/java/com/projeto/cortex/auth/offline
git commit -m "feat(auth): issue seven-day offline grants"
```

### Task 3: Parse Current and Legacy Grant Protocols Without Promotion

**Files:**
- Modify: `apps/web/src/features/auth/offlineVault.types.ts`
- Modify: `apps/web/src/features/auth/offlineVault.ts`
- Test: `apps/web/src/features/auth/offlineVault.test.ts`
- Test: `apps/web/src/features/auth/collaborativeOfflineGrant.test.ts`

**Interfaces:**
- Consumes: signed protocol v1 legacy grants and signed protocol v2 current grants.
- Produces: `OfflineGrantClaims = LegacyOfflineGrantClaims | CurrentOfflineGrantClaims`, where only current claims have `authEpoch`.

- [ ] **Step 1: Write failing parser boundary tests**

```ts
expect(await verifySignedOfflineGrant(v1AtExactly24Hours)).toMatchObject({
  claims: { versao: 1 },
});
await expect(verifySignedOfflineGrant(v1Over24Hours)).rejects.toThrow();
expect(await verifySignedOfflineGrant(v2AtExactlySevenDays)).toMatchObject({
  claims: { versao: 2, authEpoch: 7 },
});
await expect(verifySignedOfflineGrant(v2WithoutEpoch)).rejects.toThrow();
await expect(verifySignedOfflineGrant(v2WithExtraField)).rejects.toThrow();
await expect(verifySignedOfflineGrant(v2OverSevenDays)).rejects.toThrow();
```

- [ ] **Step 2: Run the focused test and confirm RED**

```bash
cd apps/web
npx vitest run src/features/auth/offlineVault.test.ts
```

Expected: current parser rejects v2 and still applies one limit to every grant.

- [ ] **Step 3: Implement discriminated exact parsing**

```ts
export type LegacyOfflineGrantClaims = BaseOfflineGrantClaims & {
  versao: 1;
};

export type CurrentOfflineGrantClaims = BaseOfflineGrantClaims & {
  versao: 2;
  authEpoch: number;
};
```

Parse each version with a separate exact-key list. Require `authEpoch` to be a safe integer from 1 through `Number.MAX_SAFE_INTEGER`. Apply `86400` seconds only to v1 and `604800` seconds only to v2. Do not synthesize an epoch for v1.

- [ ] **Step 4: Run focused tests and commit**

```bash
cd apps/web
npx vitest run src/features/auth/offlineVault.test.ts src/features/auth/collaborativeOfflineGrant.test.ts
cd ../..
git add apps/web/src/features/auth/offlineVault.types.ts apps/web/src/features/auth/offlineVault.ts apps/web/src/features/auth/offlineVault.test.ts apps/web/src/features/auth/collaborativeOfflineGrant.test.ts
git commit -m "feat(auth): verify versioned offline grants"
```

### Task 4: Build the Encrypted Collaborative Password Vault

**Files:**
- Create: `apps/web/src/features/auth/passwordOfflineVault.ts`
- Create: `apps/web/src/features/auth/passwordOfflineVault.test.ts`
- Modify: `apps/web/src/features/auth/offlineVault.types.ts`
- Modify: `apps/web/src/features/auth/offlineVaultRepository.ts`
- Modify: `apps/web/src/features/auth/offlineVaultRepository.test.ts`

**Interfaces:**
- Consumes: canonical CPF, password in memory, a verified current signed grant, random source, and clock.
- Produces: `createPasswordOfflineVault`, `openPasswordOfflineVault`, `resealPasswordOfflineVault`, `hasLivePasswordVaultKey`, and `clearPasswordVaultKeys`.

- [ ] **Step 1: Write failing crypto/storage tests**

Use deterministic random fixtures only in tests. Assert the persisted object has exactly:

```ts
{
  key,
  versao: 3,
  cpfSalt,
  cpfVerifier,
  passwordSalt,
  kdf: "PBKDF2-SHA256",
  kdfIterations: 600_000,
  iv,
  ciphertext,
  serverKeyFingerprint,
  atualizadoEm,
  failedAttemptState: { windowStartedAt, failures, blockedUntil },
}
```

Assert serialized metadata does not contain CPF, password, signed `payload`, name, role, collaborator UUID, worksite UUID, scope fingerprint, `authEpoch`, or trusted timestamp. Cover correct open, wrong CPF/password, altered IV/ciphertext/fingerprint/AAD, five failures, successful reset, seven-day boundary, and clock rollback greater than five minutes.

- [ ] **Step 2: Run focused tests and confirm RED**

```bash
cd apps/web
npx vitest run src/features/auth/passwordOfflineVault.test.ts src/features/auth/offlineVaultRepository.test.ts
```

Expected: module/type imports fail because v3 is not implemented.

- [ ] **Step 3: Implement focused crypto primitives**

```ts
export async function createPasswordOfflineVault(input: {
  cpf: string;
  password: string;
  signedGrant: SignedOfflineGrant;
  authenticatedOwnerId: string;
  now?: () => number;
}): Promise<OfflinePasswordVaultMetadata>;

export async function openPasswordOfflineVault(input: {
  cpf: string;
  password: string;
  metadata: OfflinePasswordVaultMetadata;
  now?: () => number;
}): Promise<
  | {
      kind: "UNLOCKED";
      claims: CurrentOfflineGrantClaims;
      metadata: OfflinePasswordVaultMetadata;
    }
  | {
      kind: "REJECTED";
      metadata: OfflinePasswordVaultMetadata;
    }
>;

export async function resealPasswordOfflineVault(
  metadata: OfflinePasswordVaultMetadata,
  signedGrant: SignedOfflineGrant,
  authenticatedOwnerId: string,
  now?: () => number,
): Promise<OfflinePasswordVaultMetadata | "PASSWORD_REQUIRED">;
```

Import the password into Web Crypto as non-extractable PBKDF2 material, derive a non-extractable AES-GCM key, and retain only the `CryptoKey` in a module `Map<string, CryptoKey>`. The AES-GCM plaintext contains `{signedGrant, ownerId, scopeFingerprint, authEpoch, lastTrustedTime}`. AAD is the canonical JSON of `{key,versao,cpfVerifier,serverKeyFingerprint,kdf,kdfIterations}`. Clear temporary UTF-8 password bytes and plaintext buffers in `finally` blocks.

- [ ] **Step 4: Implement bounded failure state and trusted clock update**

Use constants `FAILURE_WINDOW_MS = 900_000`, `MAX_FAILURES = 5`, `BASE_DELAY_MS = 1_000`, and `CLOCK_SKEW_MS = 300_000`. Cryptographic rejection returns `{kind:"REJECTED", metadata}` so the caller can persist the incremented wait state before throwing the single public `OfflinePasswordVaultUnlockError` message `Não foi possível liberar o acesso offline neste aparelho.`. On success, return re-sealed metadata with reset failure state and `lastTrustedTime = max(previous, now)` before the caller activates any session.

- [ ] **Step 5: Upgrade repository typing without deleting v2**

Keep IndexedDB store `cpf_grants`, allow the union `LegacyOfflineCpfGrantMetadata | OfflinePasswordVaultMetadata`, and add:

```ts
export async function deleteCollaborativeOfflineGrantMetadata(key: string): Promise<void>;
export async function replaceLegacyGrantAfterV3Save(
  legacyKey: string | null,
  metadata: OfflinePasswordVaultMetadata,
): Promise<void>;
export async function hasCollaborativePasswordVaultMetadata(): Promise<boolean>;
```

The replacement transaction writes v3 first, reads it back, then deletes only the selected v2 key. `hasCollaborativePasswordVaultMetadata` scans the bounded collaborative store and returns true only for validated version-3 records, so a legacy-only browser is directed to online login rather than offered a password flow it cannot open. Do not clear the object store or change passkey `vaults`.

- [ ] **Step 6: Run tests and commit**

```bash
cd apps/web
npx vitest run src/features/auth/passwordOfflineVault.test.ts src/features/auth/offlineVaultRepository.test.ts
cd ../..
git add apps/web/src/features/auth/passwordOfflineVault.ts apps/web/src/features/auth/passwordOfflineVault.test.ts apps/web/src/features/auth/offlineVault.types.ts apps/web/src/features/auth/offlineVaultRepository.ts apps/web/src/features/auth/offlineVaultRepository.test.ts
git commit -m "feat(auth): encrypt collaborative offline vaults"
```

### Task 5: Prepare v3 on Login and Require Password Offline

**Files:**
- Modify: `apps/web/src/features/auth/collaborativeOfflineGrant.ts`
- Modify: `apps/web/src/features/auth/collaborativeOfflineGrant.test.ts`
- Modify: `apps/web/src/features/auth/authService.ts`
- Modify: `apps/web/src/features/auth/authService.test.ts`
- Modify: `apps/web/src/features/auth/OfflineUnlockPage.tsx`
- Modify: `apps/web/src/features/auth/OfflineUnlockPage.css`
- Modify: `apps/web/src/App.offlineUnlock.test.tsx`

**Interfaces:**
- Consumes: online-authenticated `cpf`, transient `password`, signed current grant, and owner ID.
- Produces: v3 persistence after login and `unlockCollaborativeOfflineGrant(cpf, password, metadata)` for offline use.

- [ ] **Step 1: Write failing orchestration and UI tests**

```ts
expect(saveCollaborativeOfflineGrant).toHaveBeenCalledWith(
  "11144477735",
  "senha individual forte",
  signedGrant,
  profile.colaboradorId,
);
```

Add UI assertions for a password input with `type="password"`, `autoComplete="current-password"`, a submit label `Desbloquear acesso offline`, retained `Usar passkey`, and the same alert for wrong CPF, wrong password, missing v3, tampering, and expiry. Assert the password is never written to local/session storage.

- [ ] **Step 2: Run focused tests and confirm RED**

```bash
cd apps/web
npx vitest run src/features/auth/authService.test.ts src/features/auth/collaborativeOfflineGrant.test.ts src/App.offlineUnlock.test.tsx
```

Expected: old functions accept CPF only and UI has no password field.

- [ ] **Step 3: Implement login preparation and confirmed migration**

```ts
saveCollaborativeOfflineGrant(
  cpf: string,
  password: string,
  signedGrant: SignedOfflineGrant,
  authenticatedOwnerId: string,
): Promise<OfflinePasswordVaultMetadata>

unlockCollaborativeOfflineGrant(
  cpf: string,
  password: string,
  metadata: OfflinePasswordVaultMetadata,
): Promise<void>
```

Locate a matching v2 record but do not open it in the new password flow. Create and persist v3; only after read-back validation delete that matching v2. Reject a server grant with protocol version 1 during v3 creation. `autenticarPorCpf` passes its password directly to the awaited save call and never stores it on an object, closure retained beyond the call, state store, or log.

- [ ] **Step 4: Implement the password unlock form**

Use separate refs and errors for CPF/password validation, but collapse all post-validation unlock errors to `Não foi possível liberar o acesso offline neste aparelho.`. The explanatory copy must say: `Este aparelho precisa de um primeiro login com conexão antes de funcionar offline.`. Keep passkey as an independent secondary action.

- [ ] **Step 5: Run focused tests and commit**

```bash
cd apps/web
npx vitest run src/features/auth/authService.test.ts src/features/auth/collaborativeOfflineGrant.test.ts src/App.offlineUnlock.test.tsx
cd ../..
git add apps/web/src/features/auth/collaborativeOfflineGrant.ts apps/web/src/features/auth/collaborativeOfflineGrant.test.ts apps/web/src/features/auth/authService.ts apps/web/src/features/auth/authService.test.ts apps/web/src/features/auth/OfflineUnlockPage.tsx apps/web/src/features/auth/OfflineUnlockPage.css apps/web/src/App.offlineUnlock.test.tsx
git commit -m "feat(auth): unlock offline data with password"
```

### Task 6: Renew Only With a Live Key or Explicit Reauthentication

**Files:**
- Modify: `apps/web/src/features/auth/renovacaoDoGrantOffline.ts`
- Modify: `apps/web/src/features/auth/renovacaoDoGrantOffline.test.ts`
- Create: `apps/web/src/features/auth/OfflineGrantRenewalPrompt.tsx`
- Create: `apps/web/src/features/auth/OfflineGrantRenewalPrompt.test.tsx`
- Modify: `apps/web/src/App.tsx`
- Modify: `apps/web/src/App.onlineHandoff.test.tsx`

**Interfaces:**
- Consumes: online session, v3 records, newly signed grant, and in-memory `CryptoKey`.
- Produces: `RenovacaoDoGrant = "NAO_APLICAVEL" | "AINDA_VALIDO" | "RENOVADO" | "SEM_REDE" | "SENHA_NECESSARIA"`.

- [ ] **Step 1: Write failing renewal tests**

```ts
expect(await renovarGrantOfflineSePreciso(now)).toBe("RENOVADO");
expect(await renovarDepoisDeReloadSemChave(now)).toBe("SENHA_NECESSARIA");
expect(fetchOfflineGrant).not.toHaveBeenCalledForLegacyV2();
```

The prompt test enters CPF + password, calls `autenticarPorCpf`, closes only on success, and never echoes the password in an error or DOM value after success.

- [ ] **Step 2: Run focused tests and confirm RED**

```bash
cd apps/web
npx vitest run src/features/auth/renovacaoDoGrantOffline.test.ts src/features/auth/OfflineGrantRenewalPrompt.test.tsx src/App.onlineHandoff.test.tsx
```

Expected: renewal currently overwrites a clear v2 grant and has no password-required state.

- [ ] **Step 3: Implement v3 renewal and prompt state**

Filter out every metadata record whose `versao !== 3`. Before fetching a grant, determine whether at least one expiring v3 has a live key. If none does, return `SENHA_NECESSARIA` without network mutation. Re-seal with a fresh IV and updated scope/epoch only after owner verification.

`App.tsx` stores `renewalNeedsPassword: boolean`, sets it when renewal returns `SENHA_NECESSARIA`, and renders `OfflineGrantRenewalPrompt` without interrupting current online work. Successful reauthentication clears the prompt; cancellation keeps the old grant until its signed expiry.

- [ ] **Step 4: Run focused tests and commit**

```bash
cd apps/web
npx vitest run src/features/auth/renovacaoDoGrantOffline.test.ts src/features/auth/OfflineGrantRenewalPrompt.test.tsx src/App.onlineHandoff.test.tsx
cd ../..
git add apps/web/src/features/auth/renovacaoDoGrantOffline.ts apps/web/src/features/auth/renovacaoDoGrantOffline.test.ts apps/web/src/features/auth/OfflineGrantRenewalPrompt.tsx apps/web/src/features/auth/OfflineGrantRenewalPrompt.test.tsx apps/web/src/App.tsx apps/web/src/App.onlineHandoff.test.tsx
git commit -m "feat(auth): reauthenticate expiring offline vaults"
```

### Task 7: Publish One Seven-Day Deployment Contract

**Files:**
- Modify: `apps/api/src/main/resources/application.yml`
- Modify: `.env.example`
- Modify: `.env.postgresql.example`
- Modify: `compose.local.yml`
- Modify: `compose.production.example.yml`
- Modify: `deploy/production/compose.yml`
- Modify: `render.yaml`
- Modify: `scripts/deploy/prepare-local-production.sh`
- Modify: `scripts/security/test-hosted-deployment-contract.sh`
- Modify: `scripts/security/test-local-compose-security.sh`
- Modify: `scripts/security/test-production-publication.sh`
- Modify: `apps/api/src/test/java/com/projeto/cortex/auth/offline/OfflineGrantDeploymentConfigurationTest.java`
- Modify: `docs/dev-runbook.md`
- Modify: `docs/production-runbook.md`
- Modify: `docs/deploy-checklist.md`
- Modify: `docs/operations/cortex-hosted-pilot.md`

**Interfaces:**
- Consumes: environment variable `CORTEX_AUTH_OFFLINE_GRANT_TTL_SECONDS`.
- Produces: the exact value `604800` in default application, local VM, hosted deployment, examples, docs, and negative contract fixtures.

- [ ] **Step 1: Write failing deployment contract assertions**

Replace every positive expectation of `86400` with `604800`, and add a negative regression that mutates one deployment surface back to `86400` and expects the contract to fail.

- [ ] **Step 2: Run contracts and confirm RED**

```bash
bash scripts/security/test-hosted-deployment-contract.sh
bash scripts/security/test-local-compose-security.sh
bash scripts/security/test-production-publication.sh
```

Expected: exact TTL assertions fail on current one-day settings.

- [ ] **Step 3: Update runtime, deployment, and operational documentation**

Set every default/example to `604800`. Document that VM reachability over LAN uses online login, browser-only isolation uses the seven-day vault, v2 rollback data is preserved, and Academy/Zeladoria polling remains disabled until SELECT-only TLS validation.

- [ ] **Step 4: Run contracts and commit**

```bash
bash scripts/security/test-hosted-deployment-contract.sh
bash scripts/security/test-local-compose-security.sh
bash scripts/security/test-normal-runtime-launchers.sh
bash scripts/security/test-production-publication.sh
git add .env.example .env.postgresql.example apps/api/src/main/resources/application.yml apps/api/src/test/java/com/projeto/cortex/auth/offline/OfflineGrantDeploymentConfigurationTest.java compose.local.yml compose.production.example.yml deploy render.yaml scripts docs
git commit -m "chore(deploy): publish seven-day offline policy"
```

### Task 8: Prove the Combined Revision Without Overstating Deployment

**Files:**
- Modify if coverage needs a focused harness: `apps/web/src/features/auth/passwordOfflineVault.test.ts`
- Modify if integration fixtures need epoch checks: `apps/api/src/test/java/com/projeto/cortex/auth/password/PostgresqlPasswordPersistenceIT.java`
- Evidence only, no committed secrets: browser storage/network observations for the later deployed revision.

**Interfaces:**
- Consumes: all preceding commits on one exact local SHA.
- Produces: unit/build/PostgreSQL/deploy evidence now, plus an explicit remaining acceptance checklist for the deployed same SHA.

- [ ] **Step 1: Run complete backend verification sequentially**

```bash
cd apps/api
./mvnw -q test
python3 - <<'PY'
from pathlib import Path
import xml.etree.ElementTree as ET
t=f=e=s=0
for path in Path('target/surefire-reports').glob('TEST-*.xml'):
    root=ET.parse(path).getroot()
    t += int(root.attrib.get('tests', 0))
    f += int(root.attrib.get('failures', 0))
    e += int(root.attrib.get('errors', 0))
    s += int(root.attrib.get('skipped', 0))
print(t, f, e, s)
PY
./mvnw -q -Ppostgresql-it -Dtest='com.projeto.cortex.auth.password.PasswordSecurityMigrationTest' -Dit.test='com.projeto.cortex.auth.password.PostgresqlPasswordPersistenceIT' verify
```

Expected: zero failures/errors and PostgreSQL 18 at V88.

- [ ] **Step 2: Run complete frontend verification sequentially**

```bash
cd apps/web
npm test -- --run
npm run lint
npm run build
```

Expected: all tests pass and the PWA boundary verifier confirms the built shell.

- [ ] **Step 3: Run security/deployment contracts and diff checks**

```bash
cd ../..
bash scripts/security/test-hosted-deployment-contract.sh
bash scripts/security/test-local-compose-security.sh
bash scripts/security/test-normal-runtime-launchers.sh
bash scripts/security/test-production-publication.sh
git diff --check origin/develop..HEAD
```

Expected: every contract passes and the diff has no whitespace errors.

- [ ] **Step 4: Inspect clear persistence and built artifacts**

Search source/dist test fixtures and inspect the fake IndexedDB record produced by the v3 test. The only clear collaborative fields allowed are the exact public metadata listed in Task 4; no raw password, signed payload, person name, role, owner/worksite UUID, scope fingerprint, epoch, or trusted timestamp may appear.

- [ ] **Step 5: Record the deferred real-browser acceptance truthfully**

After the same SHA is deployed, execute: online password login; inspect v3; cut API/network; close/reopen; password unlock; create/edit RDO; reconnect; confirm one outbox application; reconnect again; reset password; inactivate collaborator; test immediately before/after seven days; repeat on a second device. Until that is performed, report implementation/tests as proven and browser/deployment acceptance as pending.

- [ ] **Step 6: Audit final history and hand off**

```bash
git status --short
git log --oneline --decorate origin/develop..HEAD
git rev-list --left-right --count origin/develop...HEAD
```

Expected: clean tree, reviewable small commits, and no push/deploy unless separately authorized.
