# Córtex Automatic Offline Renewal — Device Capsule PWA Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace password/passkey renewal prompts with a same-device encrypted authorization capsule that is created and refreshed automatically while a valid online session exists, while still requiring CPF plus password or passkey PRF to unlock offline data.

**Architecture:** The PWA adds three stores to the existing cortex-auth-vaults IndexedDB: encrypted grant capsules, non-extractable device AES/HMAC keys, and opaque renewal state. A trusted build-time keyring and distinct historical/current verification paths bind an opened password or passkey vault to the newest valid capsule for the same owner and authEpoch. An idempotent single-flight scheduler coordinates tabs with Web Locks or an IndexedDB lease, applies persistent throttle/backoff, and writes capsules with revision compare-and-swap without awaiting Web Crypto inside a transaction.

**Tech Stack:** React 19, TypeScript 6, Vite 8, Vitest 4, idb 8, fake-indexeddb, Web Crypto, IndexedDB, Web Locks, BroadcastChannel, Service Worker/PWA.

**Spec:** docs/superpowers/specs/2026-08-17-automatic-offline-grant-renewal-design.md

## Global Constraints

- This plan depends on docs/superpowers/plans/2026-08-17-automatic-offline-renewal-01-auth-epoch-session.md. Implement Plan 1 first on the same unreleased feature branch so this plan can consume its stable marker/global authEpoch contract; do not merge Plan 1 alone to `develop`.
- The legacy operational-namespace reconciler in specification lines 401-444 belongs to Plan 3. This plan must not open, copy, rename, prune, acknowledge, or delete operational databases or their outboxes.
- The signed grant window remains at most seven days. A successful authenticated connection may move the window forward, but the client never lengthens signed timestamps.
- Offline unlock still requires CPF plus password or passkey PRF. The device capsule is authorization material, not a local authentication factor.
- Never persist a password, Argon2id hash, password-derived key, raw grant, ownerId, scope, authEpoch, CPF, or raw session marker in the new capsule stores.
- Device AES-256-GCM and HMAC-SHA-256 keys must be extractable: false and limited to encrypt/decrypt or sign.
- A fingerprint stored beside ciphertext is authenticated metadata only. Trust comes exclusively from the keyring compiled into the PWA build. Production obtains that keyring only from the protected GitHub Environment variable `CORTEX_OFFLINE_GRANT_TRUST_KEYRING_JSON`; Plan 1 does not produce it.
- One ACTIVE key is the signer currently loaded by the API; during a planned rotation the compiled bundle may contain the old and new keys as two ACTIVE verification entries while `VITE_CORTEX_OFFLINE_GRANT_PUBLIC_KEY_SHA256` selects the one actually signing. NEW accepts either ACTIVE signature during that bounded overlap. After the signer switch is proven, the old key becomes RETIRED and verifies only according to cutoff/notAfter policy. REVOKED never verifies.
- Legacy grants without authEpoch never consume a capsule.
- lastTrustedTime is monotonic and is never reduced by local clock rollback, a stale response, or a racing tab.
- Web Crypto work occurs outside readwrite IndexedDB transactions. The transaction only rereads raw state, compares revision, and puts or aborts.
- A 401/403 blocks only the captured ownerLookup plus sessionLookup generation. A new login, including the same owner, receives another marker and may try again. Every production session ingress acquires one device-wide serialized ingress authority **before** its network request and holds it through durable publication; a timed-out/taken-over response cannot publish. Before any caller exposes the new session in memory or by `BroadcastChannel`, it commits the opaque owner/session pair to one fixed `grant_capsule_state` row under that authority. Capsule CAS validates that shared row in the same transaction, so delayed cross-tab delivery cannot authorize a stale response.
- Network, 5xx, and transient IndexedDB failures back off with jitter from one minute to one hour. Sync and visibility triggers return without calling the API during backoff.
- Capsule reset operates only on the three approved stores: it clears capsule/key records and selectively removes renewal, current-session, and capsule-lease records from `grant_capsule_state`, while atomically replacing the generation record. The fixed root lease fences that transaction and is consumed/deleted before commit, allowing immediate reacquisition under the new generation. During serialized session establishment only, the reset may preserve the exact still-owned fixed ingress record; other resets preserve none. The reset transaction validates its root and any preserved ingress authority and advances the sidecar generation first, so every stale owner lease becomes unable to write. `UNSUPPORTED` capability is not corruption and never triggers reset; `CORRUPT` material does trigger this fenced reset, and device keys/capsules are recreated only under a validated current-session or session-ingress authority. Reset must not clear vaults, cpf_grants, any operational database, or any outbox.
- LoginPage, OfflineUnlockPage, ordinary offline-unavailable notices, PDF/XLSX, RDO persistence, and synchronization semantics remain unchanged.
- Unit tests, CI, exact-revision deployment, and authenticated browser acceptance are separate evidence layers.
- A push to `develop` auto-starts production publication. Plan 1 and Plan 2 may have separate commits on one feature branch, but neither may reach `develop` or any deployment until Plan 3 is complete and the combined Plans 1-3 gates pass for the same candidate SHA.

## Scope and file structure

Create focused modules rather than expanding offlineVault.ts into a second scheduler:

- apps/web/src/features/auth/offlineGrantTrustKeyring.ts owns trusted signer policy only.
- apps/web/src/features/auth/offlineGrantVerifier.ts parses and verifies signed grants in issuance, current-use, and historical-binding modes.
- apps/web/src/features/auth/grantCapsule.types.ts owns persisted and in-memory capsule types.
- apps/web/src/features/auth/deviceGrantKeys.ts owns feature probing, non-extractable key bootstrap, and blind HMAC lookups.
- apps/web/src/features/auth/grantCapsuleCrypto.ts owns capsule AAD, encryption, decryption, and monotonic validation.
- apps/web/src/features/auth/grantCapsuleLock.ts owns Web Locks, the durable fallback lease, the fixed bootstrap/reset locks, and the sidecar-generation fence.
- apps/web/src/features/auth/onlineSessionIngress.ts serializes every online-session request and durable publication across tabs before any cookie profile enters memory.
- apps/web/src/features/auth/renovacaoDoGrantOffline.ts owns scheduling policy, single-flight, throttle, backoff, response classification, and CAS retry.
- apps/web/src/features/auth/offlineVaultRepository.ts remains the only IndexedDB access layer for auth vaults and capsules.
- Password and passkey modules continue to own their local factor and only consume a validated current capsule after the factor opens its historical binding.

---

### Task 1: Consume the opaque server-session generation

**Files:**
- Modify: apps/web/src/features/auth/authSession.ts:14-24, 52-71, 175-195
- Modify: apps/web/src/features/auth/authProfile.ts:3-35
- Modify: apps/web/src/features/auth/authApi.ts:1-18, 48-71, 109-119
- Modify: apps/web/src/features/auth/authService.ts:1-105
- Modify: apps/web/src/features/auth/emailOtpApi.ts:1-81
- Modify: apps/web/src/features/auth/EmailOtpAccessForm.tsx:1-24
- Modify: apps/web/src/features/auth/retomadaDaSessao.ts:1-83
- Modify: apps/web/src/features/auth/passkeyApi.ts:105-139, 235-264
- Modify: apps/web/src/features/auth/offlineVault.ts:206-229
- Modify: apps/web/src/App.tsx:146-170
- Create: apps/web/src/features/auth/authProfile.test.ts
- Create: apps/web/src/features/auth/authProfile.typecheck.ts
- Test: apps/web/src/features/auth/authSession.test.ts
- Test: apps/web/src/features/auth/authApi.test.ts
- Test: apps/web/src/features/auth/authService.test.ts
- Test: apps/web/src/features/auth/emailOtpApi.test.ts
- Test: apps/web/src/features/auth/retomadaDaSessao.test.ts
- Test: apps/web/src/features/auth/passkeyApi.test.ts
- Test: apps/web/src/features/auth/offlineVault.test.ts

**Interfaces:**
- Consumes: Plan 1 session/login JSON field sessionMarker: string, stable for reloads of one cookie and changed by every new authentication.
- Produces: `BaseAuthProfile`, `OnlineAuthProfile`, `OfflineAuthProfile`, their union `AuthProfile`, `isOnlineAuthProfile(profile): profile is OnlineAuthProfile`, `getOnlineSession(): OnlineAuthProfile | null`, the low-level document-local `setSession(session: OnlineAuthProfile)`, `setOfflineSession(session: OfflineAuthProfile)`, `captureOnlineAuthSessionFence(session): OnlineAuthSessionFence`, and one canonical `parseAuthProfile(body: unknown): OnlineAuthProfile` used by password login, session restore, OTP, passkey login, and reconnection. `sessionMarker` is mandatory only on `OnlineAuthProfile` and impossible on `OfflineAuthProfile`. Task 5 removes direct production use of the low-level setter in favor of the durable shared-fence publication boundary.

- [ ] **Step 1: Write failing parser and session-generation tests**

Add these cases to authProfile.test.ts and authSession.test.ts:

~~~ts
it("requires the opaque session marker returned by the cookie session", () => {
  const profile = parseAuthProfile({
    colaboradorId: "00000000-0000-4000-8000-000000000001",
    nome: "Colaborador Córtex",
    papelAcesso: "ALFA",
    escopoGlobal: true,
    obraIds: [],
    expiraEm: "2099-08-17T12:00:00Z",
    sessionMarker: "Y".repeat(43),
  });

  expect(profile.sessionMarker).toBe("Y".repeat(43));
  expect(() => parseAuthProfile({
    ...profile,
    sessionMarker: undefined,
  })).toThrow("Perfil de autenticação inválido");
  expect(() => parseAuthProfile({
    ...profile,
    sessionMarker: "contains space",
  })).toThrow("Perfil de autenticação inválido");
});

it("keeps the same cookie generation and retires only another marker", () => {
  firstTab.setSession({ ...profile, sessionMarker: "A".repeat(43) });
  secondTab.setSession({ ...profile, sessionMarker: "A".repeat(43) });

  expect(firstTab.getOnlineSession()?.sessionMarker).toBe("A".repeat(43));
  expect(broadcast.postMessage).toHaveBeenLastCalledWith({
    type: "SESSION_REPLACED",
    sessionMarker: "A".repeat(43),
  });

  secondTab.setSession({ ...profile, sessionMarker: "B".repeat(43) });
  expect(firstTab.getSession()).toBeNull();
  expect(secondTab.getOnlineSession()?.sessionMarker).toBe("B".repeat(43));
});
~~~

Add a synchronous write-fence test. Capturing a fence for marker A yields an
unaborted signal and `assertCurrent()` succeeds. Reapplying A leaves that fence
valid; logout, offline activation, or marker B synchronously aborts it before
the session-change broadcast is posted, and `assertCurrent()` then throws.
Receiving B from another tab does the same; receiving A does not. This fence is
memory-only and never serializes the raw marker.

In passkeyApi.test.ts, assert that authentication uses parseAuthProfile rather than a private duplicate:

~~~ts
expect(mocks.parseAuthProfile).toHaveBeenCalledWith(
  expect.objectContaining({ sessionMarker: "S".repeat(43) }),
);
~~~

Before running TypeScript, add compile-time/runtime cases proving
`setOfflineSession({...base})` accepts no marker, `setSession({...base})` is
rejected without one, `getOnlineSession()` returns null for an offline
principal, and an offline unlock never manufactures a marker. Put the
compile-only assignments in `authProfile.typecheck.ts`, which is under
`tsconfig.app.json`'s included `src` tree and is not excluded like
`*.test.ts`:

~~~ts
function assertProfileTypesOnly(base: BaseAuthProfile): void {
  setOfflineSession(base);
  setSession({ ...base, sessionMarker: "S".repeat(43) });
  // @ts-expect-error An online session always has a server marker.
  setSession(base);
  // @ts-expect-error An offline principal can never carry a session marker.
  setOfflineSession({ ...base, sessionMarker: "S".repeat(43) });
}

void assertProfileTypesOnly;
~~~

This file remains side-effect free and exists only so the normal application
TypeScript project checks the online/offline boundary.

- [ ] **Step 2: Run the focused tests and record RED**

Run:

~~~bash
cd apps/web
npm test -- src/features/auth/authProfile.test.ts src/features/auth/authSession.test.ts src/features/auth/passkeyApi.test.ts
npx tsc -b
~~~

Expected: FAIL because the current single `AuthProfile` cannot distinguish online from offline sessions, the broadcast payload is a string, same-marker replacement is not ignored, and passkeyApi still uses its private parser.

- [ ] **Step 3: Add the marker to the canonical profile contract**

Split the profile contract and make only the online parser enforce a bounded base64url marker:

~~~ts
export type BaseAuthProfile = {
  colaboradorId: string;
  nome: string;
  papelAcesso: "ALFA" | "BETA";
  escopoGlobal: boolean;
  obraIds: string[];
  expiraEm: string;
};

export type OnlineAuthProfile = BaseAuthProfile & {
  sessionMarker: string;
};

export type OfflineAuthProfile = BaseAuthProfile & {
  sessionMarker?: never;
};

export type AuthProfile = OnlineAuthProfile | OfflineAuthProfile;

export type OnlineAuthSessionFence = {
  readonly sessionMarker: string;
  readonly signal: AbortSignal;
  assertCurrent(): void;
};

export function isOnlineAuthProfile(
  profile: AuthProfile | null,
): profile is OnlineAuthProfile {
  return profile !== null &&
    typeof (profile as { sessionMarker?: unknown }).sessionMarker === "string";
}

export function getOnlineSession(): OnlineAuthProfile | null {
  expireInvalidSessions();
  return onlineSession;
}

export function captureOnlineAuthSessionFence(
  session: OnlineAuthProfile,
): OnlineAuthSessionFence;

const SESSION_MARKER_PATTERN = /^[A-Za-z0-9_-]{43}$/;

const sessionMarker = requiredString(data.sessionMarker);
if (!sessionMarker || !SESSION_MARKER_PATTERN.test(sessionMarker)) {
  throw new Error("Perfil de autenticação inválido.");
}

return {
  colaboradorId,
  nome,
  papelAcesso,
  escopoGlobal,
  obraIds,
  expiraEm,
  sessionMarker,
};
~~~

Give `setSession` the parameter `OnlineAuthProfile` and `setOfflineSession` the parameter `OfflineAuthProfile`; keep `getSession(): AuthProfile | null` for authorization consumers. The online canonicalizer revalidates the 43-character base64url marker at runtime, while the offline canonicalizer rejects any own `sessionMarker` property. `activateOfflineGrant` constructs only `OfflineAuthProfile`. `authApi`, password auth, OTP, passkey, and reconnection all return/accept `OnlineAuthProfile` through `parseAuthProfile`. Maintain one document-local `AbortController` for the current online marker. Same-marker restoration reuses it; logout, offline activation, expiration, owner change, or a different marker aborts it synchronously before replacing/clearing session state. `captureOnlineAuthSessionFence` closes over that exact controller and makes `assertCurrent()` compare the still-current marker and controller identity, so an old fence cannot become valid again after same-owner relogin.

This controller is defense-in-depth within one document, not the final
cross-tab commit authority. Keep the raw marker memory-only. Task 5 derives the
HMAC lookups, commits the fixed opaque state row before invoking this setter,
and validates that row in every capsule-writing transaction.

Replace string broadcasts with the exact object union and ignore a replacement carrying the marker already held by that tab:

~~~ts
type AuthSessionBroadcastMessage =
  | { type: "LOGOUT" }
  | { type: "SESSION_REPLACED"; sessionMarker: string };

channel.postMessage({
  type: "SESSION_REPLACED",
  sessionMarker: canonical.sessionMarker,
} satisfies AuthSessionBroadcastMessage);

channel.onmessage = (event: MessageEvent<unknown>) => {
  const message = parseAuthSessionBroadcastMessage(event.data);
  if (message?.type === "LOGOUT") {
    clearSessionLocally();
  } else if (
    message?.type === "SESSION_REPLACED" &&
    onlineSession?.sessionMarker !== message.sessionMarker
  ) {
    clearSessionLocally();
  }
};
~~~

Delete the private parseProfile function from passkeyApi.ts, import parseAuthProfile from authProfile.ts, and use it for the passkey verification response. Use `getOnlineSession()` or `isOnlineAuthProfile(session)` before reading the marker. Add the online `sessionMarker` to App.tsx sessionScope so a new login of the same owner invalidates effects captured for the prior generation; an offline profile must never enter that scheduler scope.

- [ ] **Step 4: Run the focused tests and record GREEN**

Run:

~~~bash
cd apps/web
npm test -- src/features/auth/authProfile.test.ts src/features/auth/authSession.test.ts src/features/auth/authApi.test.ts src/features/auth/authService.test.ts src/features/auth/emailOtpApi.test.ts src/features/auth/retomadaDaSessao.test.ts src/features/auth/passkeyApi.test.ts src/features/auth/offlineVault.test.ts
npx tsc -b
~~~

Expected: PASS.

- [ ] **Step 5: Commit the PWA session contract**

~~~bash
git add apps/web/src/features/auth/authSession.ts apps/web/src/features/auth/authProfile.ts apps/web/src/features/auth/authProfile.typecheck.ts apps/web/src/features/auth/authApi.ts apps/web/src/features/auth/authService.ts apps/web/src/features/auth/emailOtpApi.ts apps/web/src/features/auth/EmailOtpAccessForm.tsx apps/web/src/features/auth/retomadaDaSessao.ts apps/web/src/features/auth/passkeyApi.ts apps/web/src/features/auth/offlineVault.ts apps/web/src/App.tsx apps/web/src/features/auth/authProfile.test.ts apps/web/src/features/auth/authSession.test.ts apps/web/src/features/auth/authApi.test.ts apps/web/src/features/auth/authService.test.ts apps/web/src/features/auth/emailOtpApi.test.ts apps/web/src/features/auth/retomadaDaSessao.test.ts apps/web/src/features/auth/passkeyApi.test.ts apps/web/src/features/auth/offlineVault.test.ts
git commit -m "feat(auth): consume opaque session generation"
~~~

---

### Task 2: Replace record-supplied trust with a build-time signer keyring

**Files:**
- Create: apps/web/src/features/auth/offlineGrantTrustKeyring.ts
- Create: apps/web/src/features/auth/offlineGrantTrustKeyring.test.ts
- Create: apps/web/src/features/auth/offlineGrantVerifier.ts
- Create: apps/web/src/features/auth/offlineGrantVerifier.test.ts
- Modify: apps/web/src/features/auth/offlineVault.ts:19-36, 229-292, 391-493
- Modify: apps/web/src/features/auth/offlineVault.types.ts:1-30
- Modify: apps/web/src/vite-env.d.ts:1-12
- Modify: apps/web/Dockerfile:1-26
- Modify: apps/web/validate-docker-build-args.sh:1-28
- Modify: apps/web/src/dockerBuildArgs.test.ts
- Modify: .env.example
- Modify: .env.postgresql.example
- Modify: compose.local.yml:76-87
- Modify: compose.production.example.yml:87-94
- Modify: deploy/production/compose.yml:155-162
- Modify: scripts/dev/run-compose.sh:30-42
- Modify: scripts/deploy/prepare-local-production.sh:145-220, 280-295
- Modify: deploy/production/README.md:54-64
- Modify: docs/deploy-checklist.md:121-159
- Modify: docs/operations/cortex-hosted-pilot.md:19-24, 52-63
- Modify: docs/production-runbook.md:77-88
- Modify: .github/workflows/api-ci.yml:100-115
- Modify: .github/workflows/production.yml:70-80, 130-170, 260-275, 345-405
- Modify: scripts/security/test-local-compose-security.sh
- Modify: scripts/security/test-normal-runtime-launchers.sh
- Modify: scripts/security/test-production-publication.sh
- Modify: scripts/security/test-production-workflow-contract.sh
- Modify: scripts/security/test-production-workflow-contract-regressions.sh
- Modify: apps/api/src/test/java/com/projeto/cortex/security/ProductionSecurityContractTest.java
- Test: scripts/deploy/test-trigger-and-wait-render.sh
- Test: scripts/deploy/test-deploy-and-verify-cloudflare-pages.sh
- Modify: scripts/deploy/trigger-and-wait-render.sh
- Modify: scripts/deploy/deploy-and-verify-cloudflare-pages.sh
- Test: apps/web/src/features/auth/offlineVault.test.ts
- Test: apps/web/src/pwaServiceWorkerContract.test.ts

**Interfaces:**
- Consumes: SignedOfflineGrant and OfflineGrantClaims from offlineVault.types.ts.
- Produces:

~~~ts
export type OfflineGrantKeyStatus = "ACTIVE" | "RETIRED" | "REVOKED";

export type OfflineGrantTrustedKey = {
  keyId: string;
  fingerprint: string;
  status: OfflineGrantKeyStatus;
  retiredCutoff: string | null;
  notAfter: string;
};

export type OfflineGrantTrustKeyring = {
  revision: string;
  keys: readonly OfflineGrantTrustedKey[];
};

export type VerifiedCurrentOfflineGrant = {
  verification: "NEW" | "CURRENT";
  claims: CurrentOfflineGrantClaims;
  fingerprint: string;
  trustedKey: OfflineGrantTrustedKey;
};

export type VerifiedHistoricalOfflineGrant = {
  verification: "HISTORICAL";
  claims: CurrentOfflineGrantClaims;
  fingerprint: string;
  trustedKey: OfflineGrantTrustedKey;
};

export function loadCompiledOfflineGrantKeyring(): OfflineGrantTrustKeyring;

export function verifyNewOfflineGrant(
  grant: SignedOfflineGrant,
  options?: { now?: () => number; keyring?: OfflineGrantTrustKeyring },
): Promise<VerifiedCurrentOfflineGrant>;

export function verifyCurrentOfflineGrant(
  grant: SignedOfflineGrant,
  options?: { now?: () => number; keyring?: OfflineGrantTrustKeyring },
): Promise<VerifiedCurrentOfflineGrant>;

export function verifyHistoricalOfflineGrant(
  grant: SignedOfflineGrant,
  options?: { now?: () => number; keyring?: OfflineGrantTrustKeyring },
): Promise<VerifiedHistoricalOfflineGrant>;
~~~

- [ ] **Step 1: Write failing keyring and verifier tests**

Move the RSA fixture builder currently at offlineVault.test.ts:320-380 into offlineGrantVerifier.test.ts. Add explicit policy cases:

~~~ts
it("rejects an attacker key even when its envelope fingerprint is self-consistent", async () => {
  const attacker = await signedGrantFixture(currentClaims());
  const trusted = await signedGrantFixture(currentClaims());

  await expect(verifyNewOfflineGrant(attacker.grant, {
    now: () => NOW,
    keyring: keyring({
      keyId: trusted.grant.keyId,
      fingerprint: trusted.fingerprint,
      status: "ACTIVE",
      retiredCutoff: null,
      notAfter: "2027-08-17T00:00:00Z",
    }),
  })).rejects.toThrow("não é confiável");
});

it.each([
  ["ACTIVE", "NEW", true],
  ["RETIRED", "NEW", false],
  ["REVOKED", "NEW", false],
] as const)("applies %s to %s verification", async (status, _mode, accepted) => {
  const fixture = await signedGrantFixture(currentClaims());
  const operation = verifyNewOfflineGrant(fixture.grant, {
    now: () => NOW,
    keyring: keyring({
      keyId: fixture.grant.keyId,
      fingerprint: fixture.fingerprint,
      status,
      retiredCutoff: status === "RETIRED"
        ? "2026-08-17T13:00:00Z"
        : null,
      notAfter: "2026-08-24T12:00:00Z",
    }),
  });

  if (accepted) {
    await expect(operation).resolves.toMatchObject({ claims: currentClaims() });
  } else {
    await expect(operation).rejects.toThrow();
  }
});

it("uses an expired active grant only as a historical binding", async () => {
  const fixture = await signedGrantFixture(currentClaims({
    emitidoEm: "2026-08-10T12:00:00Z",
    expiraEm: "2026-08-17T12:00:00Z",
  }));
  const trust = activeKeyring(fixture);

  await expect(verifyHistoricalOfflineGrant(fixture.grant, {
    now: () => Date.parse("2026-08-17T12:00:01Z"),
    keyring: trust,
  })).resolves.toMatchObject({
    claims: { authEpoch: 7 },
  });
  await expect(verifyCurrentOfflineGrant(fixture.grant, {
    now: () => Date.parse("2026-08-17T12:00:01Z"),
    keyring: trust,
  })).rejects.toThrow("expirou");
});
~~~

Use the following matrix verbatim in parameterized tests; each accepted result asserts the exact `verification`, `claims`, `fingerprint`, and `trustedKey` shape, and each rejection asserts no claims object is returned:

| Verification | `ACTIVE` | `RETIRED` | `REVOKED` |
| --- | --- | --- | --- |
| `NEW` | Accept only a schema-valid v2 grant with valid UUID owner, safe non-negative `authEpoch`, original TTL `0 < expiraEm - emitidoEm <= 7 days`, and `now < min(key.notAfter, claims.expiraEm)`. | Reject. | Reject. |
| `CURRENT` | Accept only with the same schema/owner/epoch/original-TTL checks and `now < min(key.notAfter, claims.expiraEm)`. | Additionally require non-null cutoff, `emitidoEm < retiredCutoff`, and `now < min(key.notAfter, claims.expiraEm)`. | Reject. |
| `HISTORICAL` | Require exact schema/signature/keyId/fingerprint, valid UUID owner, safe non-negative `authEpoch`, original TTL `0 < expiraEm - emitidoEm <= 7 days`, and `now < key.notAfter`; `now` may be at or after `claims.expiraEm`, and the result never activates a session. | Apply every historical check, plus non-null cutoff, `emitidoEm < retiredCutoff`, and `now < key.notAfter`; `claims.expiraEm` may already have passed. | Reject, even for an otherwise valid historical binding. |

Also cover: zero/negative TTL; TTL over seven days; legacy v1 prohibited from capsule binding; historical claims whose owner or epoch disagree with the encrypted binding in Tasks 6/7; ACTIVE/HISTORICAL at exactly `notAfter`; RETIRED issued exactly at cutoff; RETIRED at exactly `notAfter`; CURRENT at exactly `expiraEm`; REVOKED in all three modes; keyId/fingerprint mismatch; malformed keyring; zero or more than two ACTIVE keys in production validation; two ACTIVE keys accepted only when the separately configured signer fingerprint selects exactly one of them; duplicate keyId/fingerprint; extra JSON fields. Use `<`, not `<=`, for every cutoff/deadline comparison.

- [ ] **Step 2: Run the focused tests and record RED**

Run:

~~~bash
cd apps/web
npm test -- src/features/auth/offlineGrantTrustKeyring.test.ts src/features/auth/offlineGrantVerifier.test.ts src/features/auth/offlineVault.test.ts
~~~

Expected: FAIL because the keyring modules and verification modes do not exist.

- [ ] **Step 3: Implement strict keyring parsing and three verification modes**

The build variable is compiled into the Vite bundle, parsed once, and never read from IndexedDB:

~~~ts
const KEYRING_ENV = "VITE_CORTEX_OFFLINE_GRANT_TRUST_KEYRING_JSON";
let compiled: OfflineGrantTrustKeyring | null = null;

export function loadCompiledOfflineGrantKeyring(): OfflineGrantTrustKeyring {
  if (compiled !== null) {
    return compiled;
  }
  const raw = import.meta.env.VITE_CORTEX_OFFLINE_GRANT_TRUST_KEYRING_JSON;
  if (!raw) {
    throw new Error(KEYRING_ENV + " não foi configurado.");
  }
  compiled = parseOfflineGrantTrustKeyring(raw);
  return compiled;
}
~~~

The keyring JSON shape is exact:

~~~json
{
  "revision": "2026-08-17.1",
  "keys": [
    {
      "keyId": "offline-production-v1",
      "fingerprint": "y4dPPatQjmG1FsEkgmK9vpzULEIIXq0aunFfyBvNIyw",
      "status": "ACTIVE",
      "retiredCutoff": null,
      "notAfter": "2027-08-17T00:00:00Z"
    }
  ]
}
~~~

Verification first hashes `publicKeySpki`, then requires one keyring entry whose `keyId` and `fingerprint` both match. Implement the table from Step 1 exactly. `NEW` accepts only ACTIVE plus a currently valid grant. `CURRENT` accepts ACTIVE, or RETIRED only before cutoff/deadlines. `HISTORICAL` may pass `expiraEm`, but still requires exact v2 schema, a valid owner, `authEpoch`, a valid signature, `0 < original signed TTL <= 7 days`, and `now < key.notAfter` for both ACTIVE and RETIRED; RETIRED historical material additionally requires issuance before cutoff. Tasks 6/7 compare the returned owner and epoch to their encrypted binding before consuming it. REVOKED always throws. `verifyHistoricalOfflineGrant` never calls `activateOfflineGrant` or returns `AuthProfile`.

Keep verifySignedOfflineGrant exported from offlineVault.ts as a temporary strict-current wrapper so consumers migrate without weakening:

~~~ts
export async function verifySignedOfflineGrant(
  grant: SignedOfflineGrant,
  policy: { now?: () => number } = {},
): Promise<VerifiedCurrentOfflineGrant> {
  return verifyCurrentOfflineGrant(grant, policy);
}
~~~

- [ ] **Step 4: Change build validation from one fingerprint to the exact keyring and app revision**

Add `VITE_CORTEX_OFFLINE_GRANT_TRUST_KEYRING_JSON` and `VITE_CORTEX_RELEASE_SHA` to vite-env.d.ts and the Docker build args. `validate-docker-build-args.sh` parses the exact JSON with Node, requires one ACTIVE entry normally and permits exactly two only for the planned rotation overlap, rejects duplicate `keyId`/fingerprint, rejects invalid timestamps/status, and requires `VITE_CORTEX_OFFLINE_GRANT_PUBLIC_KEY_SHA256` to select exactly one ACTIVE entry. It also requires the release SHA to match `^[0-9a-f]{40}$`. Keep `VITE_CORTEX_OFFLINE_GRANT_PUBLIC_KEY_SHA256`: it remains the independently configured fingerprint of the signer public key really loaded by Render, rather than being inferred from array order.

Test the shell/build boundary with:

~~~ts
expect(runValidation({
  VITE_CORTEX_RELEASE_SHA: "a".repeat(40),
  VITE_CORTEX_OFFLINE_GRANT_PUBLIC_KEY_SHA256: "A".repeat(43),
  VITE_CORTEX_OFFLINE_GRANT_TRUST_KEYRING_JSON: JSON.stringify({
    revision: "2026-08-17.1",
    keys: [{
      keyId: "offline-test-v1",
      fingerprint: "A".repeat(43),
      status: "ACTIVE",
      retiredCutoff: null,
      notAfter: "2027-08-17T00:00:00Z",
    }],
  }),
})).toMatchObject({ status: 0 });
~~~

In `production.yml`, read the source only from the protected `production` Environment and validate it before the first external mutation:

~~~yaml
- name: Validate the production offline trust keyring
  id: offline_trust
  shell: bash
  env:
    TRUST_KEYRING_JSON: ${{ vars.CORTEX_OFFLINE_GRANT_TRUST_KEYRING_JSON }}
    EXPECTED_PUBLIC_KEY_FINGERPRINT: ${{ vars.VITE_CORTEX_OFFLINE_GRANT_PUBLIC_KEY_SHA256 }}
  run: |
    set -euo pipefail
    node <<'NODE'
    const fs = require("node:fs");
    const raw = process.env.TRUST_KEYRING_JSON ?? "";
    const expected = process.env.EXPECTED_PUBLIC_KEY_FINGERPRINT ?? "";
    const keyring = JSON.parse(raw);
    if (Object.keys(keyring).sort().join(",") !== "keys,revision") throw new Error("invalid keyring shape");
    if (!Array.isArray(keyring.keys)) throw new Error("invalid keyring keys");
    const active = keyring.keys.filter((entry) => entry.status === "ACTIVE");
    if (active.length < 1 || active.length > 2) throw new Error("one or two ACTIVE keys are required");
    const selectedSigner = active.filter((entry) => entry.fingerprint === expected);
    if (selectedSigner.length !== 1) throw new Error("signer fingerprint must select one ACTIVE key");
    fs.appendFileSync(process.env.GITHUB_OUTPUT,
      `keyring_json=${JSON.stringify(keyring)}\n` +
      `keyring_revision=${keyring.revision}\n` +
      `offline_public_key_fingerprint=${selectedSigner[0].fingerprint}\n`);
    NODE
~~~

The actual implementation must reuse the same exact parser rules as `offlineGrantTrustKeyring.test.ts`, including canonical base64url, unique IDs/fingerprints, exact entry keys, timestamp ordering, status/cutoff combinations, the one-or-two ACTIVE bound, and exact signer selection; the abbreviated workflow snippet above shows the protected-source/output boundary, not permission to duplicate weaker validation. Plan 1 does not generate or own this keyring. `CORTEX_OFFLINE_GRANT_TRUST_KEYRING_JSON` is a reviewed public variable in the GitHub `production` Environment. The existing protected fingerprint remains an independent cross-check and selects the current API signer from the ACTIVE set. Derive `offline_public_key_fingerprint` from that selected entry only after equality succeeds.

Pass `${{ steps.offline_trust.outputs.keyring_json }}` unchanged as `VITE_CORTEX_OFFLINE_GRANT_TRUST_KEYRING_JSON` and `${{ github.sha }}` as `VITE_CORTEX_RELEASE_SHA` to both the published PWA image and the Cloudflare Pages build. Pass `${{ steps.offline_trust.outputs.offline_public_key_fingerprint }}` as the existing `VITE_CORTEX_OFFLINE_GRANT_PUBLIC_KEY_SHA256` build arg and as `CORTEX_OFFLINE_GRANT_PUBLIC_KEY_SHA256` to the Render/Cloudflare verification wrappers. Those wrappers already compare readiness field `offlineGrantPublicKeySha256` to the API signer public key actually loaded at runtime; keep that check and make `test-trigger-and-wait-render.sh` and `test-deploy-and-verify-cloudflare-pages.sh` reject a readiness fingerprint different from the ACTIVE entry. Record only keyring revision and fingerprint in release evidence, never the raw JSON through an untrusted output/log.

`api-ci.yml` and the pre-publication `web-gate` do not read the protected production Environment. Give each an explicit test-only exact keyring, matching test fingerprint, and 40-character fixture release SHA. Their purpose is to exercise the build contract; only the `publish` job compiles the protected production value.

- [ ] **Step 5: Propagate the paired keyring/fingerprint through every current local and hosted contract**

Add `VITE_CORTEX_OFFLINE_GRANT_TRUST_KEYRING_JSON` beside the retained fingerprint, and `VITE_CORTEX_RELEASE_SHA` beside the other immutable web build inputs, in `.env.example`, `.env.postgresql.example`, all three Compose build-arg blocks, `run-compose.sh`, current deployment runbooks, and contract fixtures. `run-compose.sh` and `prepare-local-production.sh` derive the release value from `git rev-parse HEAD` and fail unless it is 40 lowercase hex characters. `prepare-local-production.sh` derives an exact one-ACTIVE local keyring from `CORTEX_AUTH_OFFLINE_GRANT_KEY_ID` plus the fingerprint it already derives from the real matching private/public PEM pair; use a one-year `notAfter`, write keyring, fingerprint, and release SHA to the mode-0600 runtime env, and never place the private key in JSON.

Update the listed security contracts so they fail when: the keyring is missing; JSON has extra keys; ACTIVE count is zero or greater than two; the retained signer fingerprint selects zero or more than one ACTIVE entry; Docker/Pages receives a different JSON string or release SHA; Render/Pages readiness differs from the selected signer fingerprint; or a production workflow reads the keyring from `secrets`, repository text, Plan 1 output, or a non-Environment source. Add a planned-rotation contract that passes through four states without a verification gap: old-only ACTIVE; old+new ACTIVE while the old signer remains selected; old+new ACTIVE after the selected signer switches to new; and new ACTIVE plus old RETIRED with cutoff/notAfter. Keep historical files `docs/superpowers/plans/2026-07-27-cortex-cloudflare-render-neon.md` and `docs/verification/cortex-3/hosted-pilot-evidence.md` unchanged as historical records; they are the only intentionally unpaired documentation hits.

Run the complete set of current config/publication gates, not only Vitest:

~~~bash
bash scripts/security/test-local-compose-security.sh
bash scripts/security/test-normal-runtime-launchers.sh
bash scripts/security/test-production-workflow-contract.sh
bash scripts/security/test-production-workflow-contract-regressions.sh
bash scripts/deploy/test-trigger-and-wait-render.sh
bash scripts/deploy/test-deploy-and-verify-cloudflare-pages.sh
bash scripts/security/test-production-publication.sh
cd apps/api
./mvnw --batch-mode -Dtest=ProductionSecurityContractTest test
~~~

Expected: PASS. The publication umbrella must execute both deployment-wrapper suites and the workflow positive/negative contracts. The Render and Pages wrappers continue consuming only the derived fingerprint because they validate the real API signer readiness; the raw keyring is a PWA build input, not a runtime API secret.

- [ ] **Step 6: Run focused tests and record GREEN**

Run:

~~~bash
cd apps/web
npm test -- src/features/auth/offlineGrantTrustKeyring.test.ts src/features/auth/offlineGrantVerifier.test.ts src/features/auth/offlineVault.test.ts src/dockerBuildArgs.test.ts src/pwaServiceWorkerContract.test.ts
~~~

Expected: PASS.

- [ ] **Step 7: Commit trusted grant verification**

~~~bash
git add apps/web/src/features/auth/offlineGrantTrustKeyring.ts apps/web/src/features/auth/offlineGrantTrustKeyring.test.ts apps/web/src/features/auth/offlineGrantVerifier.ts apps/web/src/features/auth/offlineGrantVerifier.test.ts apps/web/src/features/auth/offlineVault.ts apps/web/src/features/auth/offlineVault.types.ts apps/web/src/features/auth/offlineVault.test.ts apps/web/src/vite-env.d.ts apps/web/Dockerfile apps/web/validate-docker-build-args.sh apps/web/src/dockerBuildArgs.test.ts apps/web/src/pwaServiceWorkerContract.test.ts .env.example .env.postgresql.example compose.local.yml compose.production.example.yml deploy/production/compose.yml scripts/dev/run-compose.sh scripts/deploy/prepare-local-production.sh scripts/deploy/trigger-and-wait-render.sh scripts/deploy/deploy-and-verify-cloudflare-pages.sh scripts/deploy/test-trigger-and-wait-render.sh scripts/deploy/test-deploy-and-verify-cloudflare-pages.sh deploy/production/README.md docs/deploy-checklist.md docs/operations/cortex-hosted-pilot.md docs/production-runbook.md .github/workflows/api-ci.yml .github/workflows/production.yml scripts/security/test-local-compose-security.sh scripts/security/test-normal-runtime-launchers.sh scripts/security/test-production-publication.sh scripts/security/test-production-workflow-contract.sh scripts/security/test-production-workflow-contract-regressions.sh apps/api/src/test/java/com/projeto/cortex/security/ProductionSecurityContractTest.java
git commit -m "feat(auth): compile offline grant trust keyring"
~~~

---

### Task 3: Upgrade IndexedDB and bootstrap non-extractable device keys

**Files:**
- Create: apps/web/src/features/auth/grantCapsule.types.ts
- Create: apps/web/src/features/auth/grantCapsuleLock.ts
- Create: apps/web/src/features/auth/grantCapsuleLock.test.ts
- Create: apps/web/src/features/auth/onlineSessionIngress.ts
- Create: apps/web/src/features/auth/onlineSessionIngress.test.ts
- Create: apps/web/src/features/auth/deviceGrantKeys.ts
- Create: apps/web/src/features/auth/deviceGrantKeys.test.ts
- Modify: apps/web/src/features/auth/offlineVaultRepository.ts:11-27, 160-197
- Rewrite tests: apps/web/src/features/auth/offlineVaultRepository.test.ts

**Interfaces:**
- Consumes: idb openDB, browser Web Crypto, and Task 1 `OnlineAuthProfile`/`isOnlineAuthProfile` so key creation is impossible from an offline principal.
- Produces:

~~~ts
export type DeviceGrantKeyRecord = {
  key: "device-keyset:v1";
  versao: 1;
  kind: "DEVICE_KEYS";
  deviceKeyGeneration: DeviceGrantKeyGeneration;
  encryptionKey: CryptoKey;
  lookupKey: CryptoKey;
};

export type DeviceKeyProbeRecord = {
  key: `probe:${string}`;
  versao: 1;
  kind: "PROBE";
  probeKey: CryptoKey;
};

export type GrantCapsuleRecord = {
  ownerLookup: string;
  versao: 1;
  revision: string;
  deviceKeyGeneration: DeviceGrantKeyGeneration;
  iv: string;
  ciphertext: string;
  serverKeyFingerprint: string;
  atualizadoEm: string;
};

export type GrantCapsuleRenewalState = {
  key: string;
  versao: 1;
  kind: "RENEWAL";
  revision: string;
  ownerLookup: string;
  sessionLookup: string;
  attempts: number;
  nextAttemptAt: string | null;
  nextEligibleIssueAt: string | null;
  terminalReason: "AUTH" | "CONTENT" | null;
  keyringRevision: string;
  appRevision: string;
  lastSuccessAt: string | null;
};

export type CurrentOnlineSessionGenerationRecord = {
  key: "current-online-session:v1";
  versao: 1;
  kind: "CURRENT_ONLINE_SESSION";
  revision: string;
  deviceKeyGeneration: DeviceGrantKeyGeneration;
  ownerLookup: string;
  sessionLookup: string;
};

export type OnlineSessionIngressRecord = {
  key: "online-session-ingress:v1";
  versao: 1;
  kind: "ONLINE_SESSION_INGRESS";
  holderId: GrantCapsuleHolderId;
  ingressKind: "AUTHENTICATION" | "SESSION_RESUME";
  expiresAt: string;
};

export type OnlineSessionIngressAuthority = {
  readonly holderId: GrantCapsuleHolderId;
  readonly ingressKind: "AUTHENTICATION" | "SESSION_RESUME";
  assertOwned(): Promise<void>;
};

export async function runWithOnlineSessionIngress<T>(
  ingressKind: "AUTHENTICATION" | "SESSION_RESUME",
  task: (authority: OnlineSessionIngressAuthority) => Promise<T>,
): Promise<T>;

export type GrantCapsuleLeaseRecord = {
  key: `lease:${string}`;
  versao: 1;
  kind: "LEASE";
  holderId: GrantCapsuleHolderId;
  sidecarGeneration: GrantCapsuleSidecarGeneration;
  purpose: "ROOT" | "OWNER";
  ownerLookup: string | null;
  sessionLookup: string | null;
  expiresAt: string;
};

// All three values are independently generated 128-bit base64url tokens
// (22 chars). None is derived from another generation or from user data.
// Runtime parsers require /^[A-Za-z0-9_-]{22}$/ exactly.
export type GrantCapsuleHolderId = string;
export type GrantCapsuleSidecarGeneration = string;
export type DeviceGrantKeyGeneration = string;

export type GrantCapsuleSidecarGenerationRecord = {
  key: "sidecar-generation:v1";
  versao: 1;
  kind: "SIDECAR_GENERATION";
  sidecarGeneration: GrantCapsuleSidecarGeneration;
};

export type GrantCapsuleLease = {
  readonly key: string;
  readonly holderId: GrantCapsuleHolderId;
  readonly sidecarGeneration: GrantCapsuleSidecarGeneration;
  readonly purpose: "ROOT" | "OWNER";
  readonly ownerLookup: string | null;
  readonly sessionLookup: string | null;
  assertOwned(): Promise<void>;
};

export const GRANT_CAPSULE_LEASE_TTL_MS = 45_000;
export const GRANT_CAPSULE_HEARTBEAT_INTERVAL_MS = 15_000;

export async function withGrantCapsuleLock<T>(
  ownerLookup: string,
  sessionLookup: string,
  task: (lease: GrantCapsuleLease) => Promise<T>,
): Promise<T>;

export async function withDeviceGrantKeyBootstrapLock<T>(
  ingress: OnlineSessionIngressAuthority | null,
  task: (lease: GrantCapsuleLease) => Promise<T>,
): Promise<T>;

export async function withDeviceGrantKeyRecoveryLock<T>(
  task: (lease: GrantCapsuleLease) => Promise<T>,
): Promise<T>;

export async function loadDeviceGrantKeys(): Promise<DeviceGrantKeyRecord | null>;
export async function addDeviceGrantKeysIfAbsent(
  candidate: DeviceGrantKeyRecord,
  lease: GrantCapsuleLease,
): Promise<DeviceGrantKeyRecord>;
export async function probePersistedNonExtractableCryptoKey(
  lease: GrantCapsuleLease,
): Promise<boolean>;
export type DeviceGrantKeyBootstrapResult =
  | {
      kind: "READY";
      keys: DeviceGrantKeyRecord;
      recoveredFrom: "NONE" | "CORRUPT_RESET";
    }
  | { kind: "NEEDS_ONLINE" }
  | {
      kind: "UNSUPPORTED";
      reason: "PERSISTED_NON_EXTRACTABLE_CRYPTO_KEY_UNSUPPORTED";
    }
  | {
      kind: "CORRUPT";
      reason:
        | "INVALID_DEVICE_KEY_RECORD"
        | "DEVICE_KEY_USE_FAILED"
        | "INVALID_CURRENT_ONLINE_SESSION"
        | "INVALID_LEASE_RECORD"
        | "INVALID_SIDECAR_GENERATION";
      resetPerformed: true;
    };

export type DeviceKeyBootstrapAuthority =
  | {
      kind: "CURRENT_SESSION";
      profile: OnlineAuthProfile;
      sessionFence: OnlineAuthSessionFence;
      expectedCurrentSession: CurrentOnlineSessionGenerationRecord;
    }
  | {
      kind: "SESSION_INGRESS";
      profile: OnlineAuthProfile;
      ingress: OnlineSessionIngressAuthority;
    };

export async function bootstrapDeviceGrantKeys(input: {
  authority: DeviceKeyBootstrapAuthority | null;
}): Promise<DeviceGrantKeyBootstrapResult>;

export async function resetGrantCapsuleSidecar(): Promise<void>;
~~~

`CurrentOnlineSessionGenerationRecord` contains only HMAC lookups made with the
non-extractable device key; it never contains the raw marker, collaborator ID,
CPF, name, role, cookie, or grant. It is one device-wide pointer because the
existing auth model also replaces the origin-wide cookie/session across tabs.
Task 5 is the only production writer and commits it before publishing an online
session to memory or `BroadcastChannel`.

- [ ] **Step 1: Write failing schema, migration, probe, and race tests**

Use fake-indexeddb/auto. Preserve existing v3 stores and records while asserting the new store names:

~~~ts
expect([...database.objectStoreNames]).toEqual(expect.arrayContaining([
  "vaults",
  "cpf_grants",
  "grant_capsules",
  "grant_capsule_keys",
  "grant_capsule_state",
]));
expect(await database.get("vaults", legacyPasskey.key)).toEqual(legacyPasskey);
expect(await database.get("cpf_grants", legacyPassword.key)).toEqual(
  legacyPassword,
);
~~~

Prove the keys cannot be exported after a real IndexedDB round-trip:

~~~ts
const result = await bootstrapDeviceGrantKeys({
  authority: sessionIngressAuthority(onlineProfile),
});
expect(result.kind).toBe("READY");
if (result.kind !== "READY") throw new Error("keys not ready");

await expect(crypto.subtle.exportKey(
  "raw",
  result.keys.encryptionKey,
)).rejects.toThrow();
await expect(crypto.subtle.exportKey(
  "raw",
  result.keys.lookupKey,
)).rejects.toThrow();
expect(result.keys.encryptionKey.usages.sort()).toEqual(["decrypt", "encrypt"]);
expect(result.keys.lookupKey.usages).toEqual(["sign"]);
~~~

Launch two online bootstraps concurrently with a barrier before addDeviceGrantKeysIfAbsent and assert both return the same deviceKeyGeneration and persisted CryptoKey objects. Run this once through `navigator.locks` and once with `navigator.locks` absent, proving the fixed root lease in `grant_capsule_state` serializes the fallback independently of any ownerLookup. Cover expiry exactly at 45 seconds, heartbeat exactly every 15 seconds, takeover at expiry, conditional heartbeat, and conditional release with an injected clock: if A expires, B acquires, and A finally unwinds, A must not delete or extend B's record. An expiresAt more than 45 seconds beyond the acquisition instant is malformed; test both exact maximum and maximum-plus-one boundaries. Lease parsing requires ROOT with both lookups null and OWNER with two exact 43-character base64url lookups; every mixed/null/extra-field shape is rejected. Add a capability-probe failure test in which a fresh probe cannot structured-clone a `CryptoKey`; expect `UNSUPPORTED`, no store clear, and byte-for-byte preservation of all three sidecar stores, vaults, and cpf_grants.

Add separate corruption cases for a persisted device record with a plain object in place of a `CryptoKey`, wrong usages, wrong algorithm, malformed/noncanonical `deviceKeyGeneration`, and a key that fails encrypt/decrypt. Prove every new keyset gets a fresh independent 16-byte CSPRNG generation encoded as an exact 22-character base64url token; it must differ from both holderId and sidecarGeneration, survive the CryptoKey round-trip unchanged, and be the same generation carried by every capsule sealed with that keyset. Also reject malformed lease and sidecar-generation records, including holder/generation tokens outside the exact format and expirations farther in the future than one maximum lease duration. Renewal-state parser corruption belongs to Task 5 when that CRUD exists. Define the lease-corruption boundary precisely: acquisition may transactionally replace only the malformed/expired row for the exact lock key it is trying to acquire, provided the generation record and every other row inspected for bootstrap integrity are valid. A malformed unrelated lease discovered by the normal bootstrap integrity scan is `INVALID_LEASE_RECORD` and routes to the dedicated recovery lock; a malformed generation does the same. The recovery acquisition deliberately ignores unrelated malformed state payloads while fencing their deletion, as specified below. With no `DeviceKeyBootstrapAuthority`, expect one atomic three-store reset and `CORRUPT`; with a valid current-session or serialized-ingress authority, expect the same atomic reset followed by a new pair and `READY` with `recoveredFrom: "CORRUPT_RESET"`. A missing keyset with no authority returns `NEEDS_ONLINE` and writes nothing. A missing keyset with a valid authority may create it.

Parse `CURRENT_ONLINE_SESSION` as an exact fixed-key record with canonical
revision, device-key generation, ownerLookup, and sessionLookup. Reject extra,
missing, raw-marker, collaborator-ID, or wrong-generation fields. The shared
current-session writer itself belongs to Task 5. The fixed ingress writer is
implemented in this task because it must exist before any session network
request. Schema/reset tests prove both opaque record shapes survive ordinary
open; a fenced sidecar reset removes the current-session row but may preserve
only the exact actively owned ingress row supplied to that reset.

Parse `ONLINE_SESSION_INGRESS` as one exact fixed-key record: canonical
22-character holder, closed ingress-kind union, finite ISO expiry no farther
than one lease TTL, and no extra fields. A malformed/expired exact ingress row
is conditionally replaceable by the next acquisition; it is never interpreted
as an authenticated profile or capsule authority after ownership expires.

Force capsule clear, key clear, or selective state cleanup in the reset transaction to fail and assert the transaction aborts so nothing is partially reset and the sidecar generation is not advanced. Race reset against a lease-bound key add/probe: reset must advance generation and win atomically; the stale capability then fails `assertOwned` and cannot write a key/probe record. Capsule/state stale-writer coverage belongs to Tasks 4/5 after those APIs exist. Assert `vaults`, `cpf_grants`, and a separately opened operational outbox remain byte-for-byte unchanged; within `grant_capsule_state`, a successful reset retains the new generation fence plus at most the exact unexpired `ONLINE_SESSION_INGRESS` authority supplied to the reset, and removes renewal, current-session, plus every consumed/stale capsule lease row, including root. A reset outside session establishment preserves no ingress row.

For upgrade coordination, hold a v3 connection open, start v4 open, assert blocked is reported as transient, deliver BroadcastChannel versionchange, close the old connection, and then assert the schema advances without deletion.

- [ ] **Step 2: Run repository/key tests and record RED**

Run:

~~~bash
cd apps/web
npm test -- src/features/auth/offlineVaultRepository.test.ts src/features/auth/deviceGrantKeys.test.ts src/features/auth/grantCapsuleLock.test.ts
npm test -- src/features/auth/onlineSessionIngress.test.ts
~~~

Expected: FAIL because DB version 3 has none of the three approved capsule stores, durable state-store fencing, or key bootstrap.

- [ ] **Step 3: Upgrade the repository to version 4**

Extend OfflineVaultDbSchema:

~~~ts
interface OfflineVaultDbSchema extends DBSchema {
  vaults: {
    key: string;
    value: OfflineVaultMetadata;
    indexes: { "by-updated-at": string; "by-owner": string };
  };
  cpf_grants: {
    key: string;
    value: CollaborativeOfflineMetadata;
    indexes: {
      "by-updated-at": string;
      "by-owner": string;
      "by-cpf-lookup": string;
    };
  };
  grant_capsules: {
    key: string;
    value: GrantCapsuleRecord;
  };
  grant_capsule_keys: {
    key: string;
    value: DeviceGrantKeyRecord | DeviceKeyProbeRecord;
  };
  grant_capsule_state: {
    key: string;
    value:
      | GrantCapsuleRenewalState
      | CurrentOnlineSessionGenerationRecord
      | OnlineSessionIngressRecord
      | GrantCapsuleLeaseRecord
      | GrantCapsuleSidecarGenerationRecord;
  };
}
~~~

During oldVersion less than 4, create exactly the three stores from the approved spec and add the `by-cpf-lookup` index to the existing `cpf_grants` store. Do not clear vaults or cpf_grants. Cache the opened connection, close it in blocking(), post CORTEX_AUTH_DB_VERSIONCHANGE over BroadcastChannel, reset the cached promise in terminated(), and surface blocked() as a typed transient OfflineVaultDatabaseBlockedError. Never delete or recreate the database on blocked.

Implement the only authorized reset as a fixed-root-lock operation across exactly the three stores. The transaction revalidates the exact root holder and captured generation from `grant_capsule_state`, writes a new random generation, clears capsules and keys, cursor-deletes `RENEWAL`, `CURRENT_ONLINE_SESSION`, plus every lease row including the consumed root, and then commits. It never calls `clear()` on `grant_capsule_state`, because the new generation fence must survive the transaction:

~~~ts
async function resetGrantCapsuleSidecarWithLease(
  lease: GrantCapsuleLease,
  ingress: OnlineSessionIngressAuthority | null,
): Promise<void> {
  const database = await authVaultDatabase();
  const transaction = database.transaction(
    [
      "grant_capsules",
      "grant_capsule_keys",
      "grant_capsule_state",
    ],
    "readwrite",
  );
  await assertLeaseOwnedIn(transaction, lease);
  if (ingress !== null) {
    await assertOnlineSessionIngressOwnedIn(transaction, ingress);
  }
  const next = createNextSidecarGenerationRecord();
  await replaceGenerationAndConsumeRootLeaseIn(transaction, lease, next);
  await transaction.objectStore("grant_capsules").clear();
  await transaction.objectStore("grant_capsule_keys").clear();
  await deleteResettableStateRowsIn(transaction, next, ingress);
  await transaction.done;
}
~~~

The public reset wrapper acquires fixed root lock `device-sidecar-root:v1`, invokes this transaction, treats the consumed root row as an already-completed release, and returns only after rereading the new generation. A fresh root acquisition must succeed immediately without advancing the clock. A caller that discovers corruption while holding an owner lock must unwind that lock first, then reset, then restart once; no nested owner/reset lock is allowed. No error path may call `deleteDB`, indiscriminately clear `grant_capsule_state`, clear `vaults`/`cpf_grants`, or open an operational database.

- [ ] **Step 4: Implement probe and add-if-absent key bootstrap**

Generate the independent keyset generation and keys exactly as follows:

~~~ts
const deviceKeyGeneration = toBase64Url(
  crypto.getRandomValues(new Uint8Array(16)),
);
if (!/^[A-Za-z0-9_-]{22}$/.test(deviceKeyGeneration)) {
  throw new Error("Geração de chave inválida");
}
const encryptionKey = await crypto.subtle.generateKey(
  { name: "AES-GCM", length: 256 },
  false,
  ["encrypt", "decrypt"],
);
const lookupKey = await crypto.subtle.generateKey(
  { name: "HMAC", hash: "SHA-256", length: 256 },
  false,
  ["sign"],
);
~~~

The repository add operation receives the root lease and uses one readwrite transaction:

~~~ts
const transaction = database.transaction(
  ["grant_capsule_keys", "grant_capsule_state"],
  "readwrite",
);
await assertLeaseOwnedIn(transaction, lease);
const keyStore = transaction.objectStore("grant_capsule_keys");
const existing = await keyStore.get(DEVICE_KEYS_ID);
if (existing && isDeviceGrantKeyRecord(existing)) {
  await transaction.done;
  return existing;
}
await keyStore.add(candidate);
await transaction.done;
return candidate;
~~~

Implement `withGrantCapsuleLock` in this task, not a later task, with the fixed exported constants `GRANT_CAPSULE_LEASE_TTL_MS = 45_000` and `GRANT_CAPSULE_HEARTBEAT_INTERVAL_MS = 15_000` plus an injectable clock. It always creates a typed durable lease row in `grant_capsule_state`, even when `navigator.locks` also provides the outer exclusion. OWNER acquisition receives both opaque `ownerLookup` and `sessionLookup`; the record and capability bind both, while ROOT binds neither. `assertOwned` validates the exact purpose, owner/session pair, row, non-expired deadline, and unchanged generation. Heartbeat/update and release are conditional on the same holder, owner/session pair, and generation; stale A can never extend or delete successor B. A lease whose stored generation differs from the current generation is immediately reclaimable regardless of its old expiresAt. Missing, expired, or a malformed row for the exact lock key being acquired is replaced only by one conditional readwrite transaction when the generation and surrounding integrity scan are valid; unrelated malformed rows are not silently healed.

Implement `runWithOnlineSessionIngress(...)` in this task as a separate fixed
device-wide serialization boundary. It acquires Web Lock
`cortex-auth-online-session-ingress-v1` when available and always writes the
typed `online-session-ingress:v1` fallback row before the callback starts. The
row contains only a random 128-bit holder, ingress kind, and bounded expiry;
it contains no marker, owner, CPF, grant, or cookie. Heartbeat and conditional
release use the same 45-second/15-second bounds as other locks. The callback
and its network request remain inside this boundary. If A expires and B takes
over, A's `assertOwned()` and any later current-session commit fail. A malformed
or expired exact ingress row is replaceable atomically without treating the
rest of the sidecar as trusted. The active ingress record is independent of
the capsule key generation so a fenced corruption reset may preserve exactly
that row while replacing every cryptographic generation; all other ingress
rows are impossible because the key is fixed.

Tests serialize `/auth/session` A and login B in both Web Lock and fallback
modes: whichever acquires second sends no network request until the first
finishes, then becomes the final durable/current session. Also expire A during
its response, let B take over, and prove A cannot publish memory, broadcast, or
write `CURRENT_ONLINE_SESSION`. This network-through-publication lock, not
arrival order or BroadcastChannel timing, is the authority for session ingress.

Solve first-install and generation-corruption without a circular dependency by reserving typed state rows `sidecar-generation:v1` and `lease:device-sidecar-root:v1`. `withDeviceGrantKeyBootstrapLock(ingress, task)` opens one readwrite transaction spanning `grant_capsules`, `grant_capsule_keys`, and `grant_capsule_state`: it parses the generation; proves all three stores are otherwise empty **except for the exact still-owned fixed ingress row supplied by the caller** before creating one random generation; or classifies a missing generation beside any capsule, key, renewal, current-session, unrelated/foreign ingress, or capsule-lease material as corruption. It validates the supplied ingress in that same transaction. This atomic three-store snapshot removes the check-then-act window. In the valid/empty-or-owned-ingress cases the same transaction conditionally acquires the ROOT lease tied to that chosen generation, with null owner/session, or loses to the current unexpired holder. Thus no capsule capability is required before a first session establishes on a truly empty device, and two tabs converge on one generation/holder. Normal owner/session locks never create or repair the generation; they fail closed until the root path succeeds.

`withDeviceGrantKeyRecoveryLock` is the only escape from a corrupted integrity scan. It opens a readwrite transaction spanning the same three stores but interprets only the fixed generation/root rows plus whether other material exists; unrelated lease/renewal payloads are treated as opaque rows that the fenced reset will delete, not as prerequisites for acquiring recovery. If the generation is valid, it conditionally acquires/replaces only the missing, expired, malformed, or stale-generation fixed ROOT row. If generation is malformed or missing beside material, it creates a fresh recovery generation and fixed ROOT row atomically in that same transaction. It never replaces a valid unexpired ROOT holder. Two recovery tabs therefore converge on one holder; the winner receives a ROOT capability for the generation it just persisted and immediately calls the three-store reset, which validates that exact capability before deleting corrupt rows. Tests cover malformed unrelated lease, malformed root row, malformed/missing generation, an active competing root, and two recovery tabs. None may reset without first owning this recovery capability.

Use the recovery primitive for every reset caused by integrity corruption. The reset transaction validates the recovery ROOT holder, advances generation again for the clean sidecar, consumes/deletes all lease rows including root, clears capsule/key records, and selectively deletes renewal/current-session rows atomically. Every old/recovery-generation lease becomes immediately stale, and the next normal root acquisition succeeds immediately without waiting 45 seconds. If bootstrap detects a corrupt persisted key/state/generation, it must return/unwind any normal root callback, call `withDeviceGrantKeyRecoveryLock`, complete the reset, then reacquire `withDeviceGrantKeyBootstrapLock` under the clean generation before generating/adding/probing. No stale capability is reused and no nested root lock occurs. Test missing generation with and without existing material, malformed generation, malformed unrelated lease, two-tab root recovery, immediate reset-to-reacquire, and the exact CORRUPT-online sequence `detect -> release -> recovery-acquire -> reset three-store sidecar -> normal-reacquire -> add/probe -> READY`.

The key add and temporary probe put/delete transactions include `grant_capsule_state` and validate the supplied capability in the same transaction as `grant_capsule_keys`. Before returning READY, persist a temporary non-extractable AES key, reread it, encrypt/decrypt a fixed byte array, assert exportKey rejects, and delete only the probe record under the same fence.

Keep capability and corruption classification disjoint. A failure to persist/use the fresh temporary probe returns `UNSUPPORTED` and leaves all existing records untouched. A malformed persisted key, generation, or unrelated lease found by the bootstrap integrity scan returns the corresponding typed `CORRUPT.reason`; only the malformed/expired lease row for the exact normal acquisition key is self-healed, as defined above. Task 5 owns renewal-state parsing and recovery. Route `CORRUPT` through `withDeviceGrantKeyRecoveryLock` and `resetGrantCapsuleSidecarWithLease` exactly once; without a `DeviceKeyBootstrapAuthority`, finish the reset and return `CORRUPT` without recreation.

After reset and normal-root reacquisition, revalidate the same authority before
generating a key. `CURRENT_SESSION` requires the same unexpired profile,
document-local fence, and fixed opaque owner/session pair; logout, relogin,
owner change, or expiry returns `NEEDS_ONLINE`. `SESSION_INGRESS` does not call
`getOnlineSession()` because the verified response is deliberately not yet in
memory: it requires the same unexpired parsed profile and the exact ingress
authority that was acquired before the network request and preserved by the
reset transaction. Takeover/expiry returns `NEEDS_ONLINE`. Only then generate a
new pair and return READY with `recoveredFrom: "CORRUPT_RESET"`, leaving capsule
creation to the authenticated scheduler or ingress publisher. Add barriers for
logout, same-owner/new-marker relogin, and ingress takeover between reset and
reacquire. Never create device keys from `getSession()` when it holds an
offline principal, and never treat a raw parsed profile without one of these
two authorities as permission to recreate the sidecar.

- [ ] **Step 5: Run repository/key tests and record GREEN**

Run:

~~~bash
cd apps/web
npm test -- src/features/auth/offlineVaultRepository.test.ts src/features/auth/deviceGrantKeys.test.ts src/features/auth/grantCapsuleLock.test.ts
npm test -- src/features/auth/onlineSessionIngress.test.ts
~~~

Expected: PASS, including the held-old-connection migration case.

- [ ] **Step 6: Commit the auth database and device keys**

~~~bash
git add apps/web/src/features/auth/grantCapsule.types.ts apps/web/src/features/auth/grantCapsuleLock.ts apps/web/src/features/auth/grantCapsuleLock.test.ts apps/web/src/features/auth/onlineSessionIngress.ts apps/web/src/features/auth/onlineSessionIngress.test.ts apps/web/src/features/auth/deviceGrantKeys.ts apps/web/src/features/auth/deviceGrantKeys.test.ts apps/web/src/features/auth/offlineVaultRepository.ts apps/web/src/features/auth/offlineVaultRepository.test.ts
git commit -m "feat(auth): persist encrypted capsule device keys"
~~~

---

### Task 4: Encrypt capsules, derive blind lookups, and make writes monotonic

**Files:**
- Create: apps/web/src/features/auth/grantCapsuleCrypto.ts
- Create: apps/web/src/features/auth/grantCapsuleCrypto.test.ts
- Modify: apps/web/src/features/auth/grantCapsuleLock.ts
- Test: apps/web/src/features/auth/grantCapsuleLock.test.ts
- Modify: apps/web/src/features/auth/deviceGrantKeys.ts
- Test: apps/web/src/features/auth/deviceGrantKeys.test.ts
- Modify: apps/web/src/features/auth/offlineVaultRepository.ts
- Test: apps/web/src/features/auth/offlineVaultRepository.test.ts

**Interfaces:**
- Consumes: DeviceGrantKeyRecord, GrantCapsuleRecord, verifyNewOfflineGrant, verifyCurrentOfflineGrant, and offlineGrantScopeMaterial.
- Produces:

~~~ts
export type GrantCapsulePlaintext = {
  signedGrant: SignedOfflineGrant;
  ownerId: string;
  scopeFingerprint: string;
  authEpoch: number;
  lastTrustedTime: string;
};

export async function ownerLookup(
  lookupKey: CryptoKey,
  ownerId: string,
): Promise<string>;

export async function cpfLookup(
  lookupKey: CryptoKey,
  canonicalCpf: string,
): Promise<string>;

export async function sessionLookup(
  lookupKey: CryptoKey,
  marker: string,
): Promise<string>;

export async function sealGrantCapsule(input: {
  keys: DeviceGrantKeyRecord;
  ownerLookup: string;
  signedGrant: SignedOfflineGrant;
  verified: VerifiedCurrentOfflineGrant;
  trustedTimes: readonly string[];
  now: number;
}): Promise<GrantCapsuleRecord>;

export async function openGrantCapsule(input: {
  keys: DeviceGrantKeyRecord;
  record: GrantCapsuleRecord;
  now: number;
}): Promise<{
  record: GrantCapsuleRecord;
  plaintext: GrantCapsulePlaintext;
  verified: VerifiedCurrentOfflineGrant;
}>;

export class GrantCapsuleCorruptionError extends Error {
  readonly kind = "CORRUPT";
}

export async function compareAndSwapGrantCapsule(input: {
  lease: GrantCapsuleLease;
  sessionFence: OnlineAuthSessionFence;
  expectedSessionLookup: string;
  expectedCurrentSession: CurrentOnlineSessionGenerationRecord;
  expectedRevision: string | null;
  candidate: GrantCapsuleRecord;
}): Promise<"WRITTEN" | "CONFLICT" | "STALE_GENERATION">;
~~~

- [ ] **Step 1: Write failing crypto and opaque-storage tests**

Assert exact HMAC domains and separation:

~~~ts
const owner = await ownerLookup(keys.lookupKey, OWNER_ID);
const cpf = await cpfLookup(keys.lookupKey, "10649626613");
const session = await sessionLookup(keys.lookupKey, "M".repeat(43));

expect(owner).toMatch(/^[A-Za-z0-9_-]{43}$/);
expect(cpf).toMatch(/^[A-Za-z0-9_-]{43}$/);
expect(session).toMatch(/^[A-Za-z0-9_-]{43}$/);
expect(new Set([owner, cpf, session]).size).toBe(3);
~~~

Seal a fixture, JSON-stringify the persisted record, and assert it contains none of ownerId, CPF, name, role, scope fingerprint, authEpoch, raw session marker, raw payload, or signed grant. Seal the same plaintext twice under one persisted AES key with deterministic random fixtures and prove each call consumes a fresh 12-byte CSPRNG IV, stores it as canonical 16-character base64url, and produces distinct ciphertext; an IV of any other decoded length or noncanonical spelling is rejected before decrypt. Tamper each AAD field, IV, ciphertext, deviceKeyGeneration, and fingerprint; `openGrantCapsule` must reject with typed `GrantCapsuleCorruptionError`, never with `UNSUPPORTED`.

Assert monotonic trusted time:

~~~ts
const opened = await openGrantCapsule({
  keys,
  record: await sealGrantCapsule({
    keys,
    ownerLookup: OWNER_LOOKUP,
    signedGrant: newer.grant,
    verified: newer.verified,
    trustedTimes: [
      "2026-08-17T13:00:00Z",
      "2026-08-17T15:00:00Z",
    ],
    now: Date.parse("2026-08-17T14:00:00Z"),
  }),
  now: Date.parse("2026-08-17T14:00:00Z"),
});

expect(opened.plaintext.lastTrustedTime).toBe("2026-08-17T15:00:00.000Z");
~~~

- [ ] **Step 2: Write failing lock and CAS race tests**

Reuse the Task 3 lock primitive and assert two callers with one owner/session pair execute one at a time. Remove navigator.locks and prove the durable fallback excludes the second caller until release or expiry. After A expires and B wins, resume A after its simulated network await and prove A's `assertOwned`, capsule CAS, heartbeat, and release all fail without changing B's raw capsule/lease bytes. Separately pause the IndexedDB transaction after it starts, replace the online session, and prove `OnlineAuthSessionFence.signal` aborts the transaction before `put`; same-marker restoration must not abort it. Then simulate a second tab committing current-session pair B while tab A's BroadcastChannel delivery is deliberately withheld. A's in-memory fence remains un-aborted, but its CAS must reject because the fixed shared row no longer equals A. Renewal-state takeover coverage begins in Task 5, where that CAS exists.

For CAS, prepare revision R1, build candidates R2-newer and R3-older outside transactions, let R2 win, and assert R3 receives CONFLICT. Reopen/decrypt R2 and prove emitidoEm and authEpoch did not regress. Spy on crypto.subtle and assert no promise from encrypt/decrypt/sign is created while the readwrite transaction is active.

- [ ] **Step 3: Run capsule crypto/lock/repository tests and record RED**

Run:

~~~bash
cd apps/web
npm test -- src/features/auth/grantCapsuleCrypto.test.ts src/features/auth/grantCapsuleLock.test.ts src/features/auth/deviceGrantKeys.test.ts src/features/auth/offlineVaultRepository.test.ts
~~~

Expected: FAIL because capsule crypto, locks, and CAS are absent.

- [ ] **Step 4: Implement blind lookups and authenticated capsule encryption**

Derive lookup material with one canonical byte sequence:

~~~ts
async function blindLookup(
  key: CryptoKey,
  domain: "owner:v1" | "cpf:v1" | "session:v1",
  value: string,
): Promise<string> {
  const bytes = new TextEncoder().encode(domain + "\u0000" + value);
  try {
    return toBase64Url(await crypto.subtle.sign("HMAC", key, bytes));
  } finally {
    bytes.fill(0);
  }
}
~~~

Generate revision with 16 random bytes. Independently generate a fresh 96-bit AES-GCM nonce for **every** `sealGrantCapsule` call with `crypto.getRandomValues(new Uint8Array(12))`; never derive it from revision, owner, time, or a prior IV, and never reuse it when resealing identical plaintext. Encode it canonically into the record and pass the decoded 12 bytes as `iv` to Web Crypto. Capsule AAD is canonical JSON containing versao, revision, ownerLookup, deviceKeyGeneration, algorithm AES-256-GCM, and serverKeyFingerprint. The ciphertext plaintext has exactly signedGrant, ownerId, scopeFingerprint, authEpoch, and lastTrustedTime. `sealGrantCapsule` persists the maximum of every supplied trusted time and the signed `emitidoEm`; its callers pass both historical-vault and prior-capsule values when available. `openGrantCapsule` first requires the canonical IV to decode to exactly 12 bytes, then verifies generation, decrypts, parses exact keys, verifies the signed grant for current use with an effective instant of `max(now, lastTrustedTime)`, recomputes scope, and compares owner/authEpoch/fingerprint. A malformed persisted record, AAD/decryption failure, key-generation mismatch, or impossible plaintext throws `GrantCapsuleCorruptionError`; an untrusted/retired/revoked signed grant remains a policy/content error so callers do not confuse signer revocation with damaged local storage.

- [ ] **Step 5: Implement Web Lock, fallback lease, and raw-record CAS**

Use lock names `cortex-auth-grant-capsule:` plus the opaque ownerLookup/sessionLookup pair. The fallback lease record key is `lease:` plus that opaque lock name and contains purpose, both lookups, holderId, sidecarGeneration, and a bounded expiresAt. The Task 3 primitive passes a purpose OWNER `GrantCapsuleLease` bound to that exact pair into the callback. `compareAndSwapGrantCapsule` receives the caller's captured `expectedSessionLookup`, `expectedCurrentSession`, and document-local `OnlineAuthSessionFence`; it rejects ROOT capabilities and OWNER capabilities for another owner/session pair, then validates purpose, holder, expiry, candidate ownerLookup, expected sessionLookup, sidecar generation, persisted device-key generation, and the fixed `current-online-session:v1` row in the same IndexedDB transaction as the target write. That row must carry the same ownerLookup/sessionLookup/deviceKeyGeneration; revision is parsed but does not invalidate a same-marker reload. After obtaining the cached database handle, call `sessionFence.assertCurrent()` and synchronously open the transaction/register a one-shot abort listener on `sessionFence.signal`, with no await between those operations. Remove the listener only after completion/abort. The local signal closes session replacement while IndexedDB requests are pending in one document; the shared row closes delayed BroadcastChannel delivery from another tab. An earlier standalone `assertOwned()` is useful before network work but is not the commit fence. Tests pass a root capability, a second owner's capability, a correct-owner/wrong-session capability, an already-aborted session fence, a fence aborted mid-transaction, a shared row replaced by another tab before commit, and a candidate encrypted under a key generation removed by reset; all invalid cases produce zero writes.

CAS implementation performs no cryptography:

~~~ts
const transaction = database.transaction(
  ["grant_capsules", "grant_capsule_keys", "grant_capsule_state"],
  "readwrite",
);
await assertLeaseOwnedIn(transaction, lease);
await assertPersistedDeviceKeyGenerationIn(
  transaction,
  candidate.deviceKeyGeneration,
);
await assertCurrentOnlineSessionIn(
  transaction,
  expectedCurrentSession,
);
const capsuleStore = transaction.objectStore("grant_capsules");
const current = await capsuleStore.get(candidate.ownerLookup);
const currentRevision = current?.revision ?? null;
if (currentRevision !== expectedRevision) {
  transaction.abort();
  return "CONFLICT";
}
await capsuleStore.put(candidate);
await transaction.done;
return "WRITTEN";
~~~

- [ ] **Step 6: Run capsule crypto/lock/repository tests and record GREEN**

Run:

~~~bash
cd apps/web
npm test -- src/features/auth/grantCapsuleCrypto.test.ts src/features/auth/grantCapsuleLock.test.ts src/features/auth/deviceGrantKeys.test.ts src/features/auth/offlineVaultRepository.test.ts
~~~

Expected: PASS.

- [ ] **Step 7: Commit capsule cryptography and concurrency**

~~~bash
git add apps/web/src/features/auth/grantCapsuleCrypto.ts apps/web/src/features/auth/grantCapsuleCrypto.test.ts apps/web/src/features/auth/grantCapsuleLock.ts apps/web/src/features/auth/grantCapsuleLock.test.ts apps/web/src/features/auth/deviceGrantKeys.ts apps/web/src/features/auth/deviceGrantKeys.test.ts apps/web/src/features/auth/offlineVaultRepository.ts apps/web/src/features/auth/offlineVaultRepository.test.ts
git commit -m "feat(auth): seal capsule with monotonic CAS"
~~~

---

### Task 5: Replace manual renewal with an automatic single-flight scheduler

**Files:**
- Create: apps/web/src/features/auth/currentOnlineSessionGeneration.ts
- Create: apps/web/src/features/auth/currentOnlineSessionGeneration.test.ts
- Rewrite: apps/web/src/features/auth/renovacaoDoGrantOffline.ts
- Rewrite tests: apps/web/src/features/auth/renovacaoDoGrantOffline.test.ts
- Modify: apps/web/src/features/auth/authSession.ts
- Test: apps/web/src/features/auth/authSession.test.ts
- Modify: apps/web/src/features/auth/authService.ts
- Test: apps/web/src/features/auth/authService.test.ts
- Modify: apps/web/src/features/auth/emailOtpApi.ts
- Test: apps/web/src/features/auth/emailOtpApi.test.ts
- Modify: apps/web/src/features/auth/passkeyApi.ts
- Test: apps/web/src/features/auth/passkeyApi.test.ts
- Modify: apps/web/src/features/auth/retomadaDaSessao.ts
- Test: apps/web/src/features/auth/retomadaDaSessao.test.ts
- Modify: apps/web/src/features/auth/offlineVault.ts
- Test: apps/web/src/features/auth/offlineVault.test.ts
- Modify: apps/web/src/lib/api/apiClient.ts
- Test: apps/web/src/lib/api/apiClient.test.ts
- Modify: apps/web/src/features/auth/authApi.ts:109-145
- Test: apps/web/src/features/auth/authApi.test.ts
- Modify: apps/web/src/features/auth/offlineVaultRepository.ts
- Test: apps/web/src/features/auth/offlineVaultRepository.test.ts

**Interfaces:**
- Consumes: Task 1 `getOnlineSession(): OnlineAuthProfile | null`, `OnlineAuthProfile.sessionMarker`, Task 3 `runWithOnlineSessionIngress(...)`, `bootstrapDeviceGrantKeys({ authority })`, blind lookup functions, fetchOfflineGrant, keyring revision, capsule crypto, repository state/lease/CAS.
- Produces:

~~~ts
export type AutomaticGrantTrigger =
  | "LOCAL_DATA_READY"
  | "SESSION_RESUMED"
  | "SYNC_COMPLETED"
  | "VISIBLE";

export type AutomaticGrantResult =
  | "NAO_APLICAVEL"
  | "PREPARADO"
  | "RENOVADO"
  | "AINDA_VALIDO"
  | "IGNORADO_BACKOFF"
  | "BLOQUEADO_NESTA_SESSAO"
  | "SEM_SUPORTE";

export type AutomaticOfflineGrantDependencies = {
  now: () => number;
  random: () => number;
  appRevision: string;
  getOnlineSession: () => OnlineAuthProfile | null;
  fetchOfflineGrant: () => Promise<SignedOfflineGrant>;
};

export function createAutomaticOfflineGrantEnsurer(
  dependencies: AutomaticOfflineGrantDependencies,
): (trigger: AutomaticGrantTrigger) => Promise<AutomaticGrantResult>;

export const garantirGrantOfflineAutomatico:
  (trigger: AutomaticGrantTrigger) => Promise<AutomaticGrantResult>;

export function cancelAutomaticOfflineGrantForCurrentDocument(): void;

export async function establishOnlineSession(
  profile: OnlineAuthProfile,
  ingress: OnlineSessionIngressAuthority,
): Promise<void>;

export type OnlineSessionRetirementAuthority = {
  profile: OnlineAuthProfile;
  sessionFence: OnlineAuthSessionFence;
  expectedCurrentSession: CurrentOnlineSessionGenerationRecord | null;
};

export async function captureOnlineSessionRetirementAuthority():
  Promise<OnlineSessionRetirementAuthority | null>;

export async function retireOnlineSession(
  authority: OnlineSessionRetirementAuthority | null,
): Promise<"RETIRED" | "ALREADY_REPLACED">;

export async function commitCurrentOnlineSessionGeneration(input: {
  keys: DeviceGrantKeyRecord;
  profile: OnlineAuthProfile;
  ingress: OnlineSessionIngressAuthority;
}): Promise<CurrentOnlineSessionGenerationRecord>;

export async function retireCurrentOnlineSessionGeneration(input: {
  expectedOwnerLookup: string;
  expectedSessionLookup: string;
}): Promise<"RETIRED" | "ALREADY_REPLACED">;

export async function loadGrantCapsuleRenewalState(
  key: string,
): Promise<GrantCapsuleRenewalState | null>;

export class GrantCapsuleStateCorruptionError extends Error {
  readonly kind = "INVALID_RENEWAL_STATE";
}

export type RenewalStateWriteAuthority =
  | {
      kind: "CURRENT_SESSION";
      sessionFence: OnlineAuthSessionFence;
      expectedCurrentSession: CurrentOnlineSessionGenerationRecord;
    }
  | {
      kind: "CAPTURED_AUTH_FAILURE";
      expectedOwnerLookup: string;
      expectedSessionLookup: string;
    };

export async function compareAndSwapGrantCapsuleRenewalState(input: {
  lease: GrantCapsuleLease;
  authority: RenewalStateWriteAuthority;
  expectedRevision: string | null;
  candidate: GrantCapsuleRenewalState;
}): Promise<"WRITTEN" | "CONFLICT">;

export async function compareAndSwapGrantCapsuleAndRenewalState(input: {
  lease: GrantCapsuleLease;
  sessionFence: OnlineAuthSessionFence;
  expectedCurrentSession: CurrentOnlineSessionGenerationRecord;
  expectedCapsuleRevision: string | null;
  expectedStateRevision: string | null;
  candidateCapsule: GrantCapsuleRecord;
  candidateState: GrantCapsuleRenewalState;
}): Promise<"WRITTEN" | "CONFLICT" | "STALE_GENERATION">;
~~~

- [ ] **Step 1: Replace old renewal expectations with failing scheduler tests**

Delete the old expectation that reload returns SENHA_NECESSARIA. Add deterministic cases with fake time and random:

~~~ts
it("creates a missing capsule without requesting CPF password or passkey", async () => {
  const ensure = createAutomaticOfflineGrantEnsurer(dependencies({
    now: () => NOW,
    random: () => 0.5,
  }));

  await expect(ensure("LOCAL_DATA_READY")).resolves.toBe("PREPARADO");
  expect(fetchOfflineGrant).toHaveBeenCalledTimes(1);
  expect(loginWithCpf).not.toHaveBeenCalled();
  expect(navigator.credentials.get).not.toHaveBeenCalled();
});

it("persists the six-hour throttle across module reload", async () => {
  await ensure("LOCAL_DATA_READY");
  const before = await loadGrantCapsule(OWNER_LOOKUP);
  vi.resetModules();
  const reloaded = await import("./renovacaoDoGrantOffline");

  await expect(reloaded.garantirGrantOfflineAutomatico(
    "SYNC_COMPLETED",
  )).resolves.toBe("AINDA_VALIDO");
  expect(fetchOfflineGrant).toHaveBeenCalledTimes(1);
  expect((await loadGrantCapsule(OWNER_LOOKUP))?.revision)
    .toBe(before?.revision);
});

it("renews only after the persisted six-hour boundary", async () => {
  await ensure("LOCAL_DATA_READY");
  const before = await loadGrantCapsule(OWNER_LOOKUP);

  clock.advanceBy(6 * 60 * 60 * 1_000 + 1);
  await expect(ensure("SYNC_COMPLETED")).resolves.toBe("RENOVADO");

  expect(fetchOfflineGrant).toHaveBeenCalledTimes(2);
  expect((await loadGrantCapsule(OWNER_LOOKUP))?.revision)
    .not.toBe(before?.revision);
});
~~~

Add cases for all four simultaneous triggers returning the same in-flight promise; one API call across two tabs under Web Lock; fallback lease; transient backoff at 1m, 2m, 4m through 1h with deterministic jitter; no API call during backoff; 401 and 403 blocked until sessionLookup changes; 422/new-response signature/keyring/owner errors blocked until session/keyring/app revision changes; same owner with new marker retries; 401 clearing auth memory still persists block for the captured pair; offline session never calls API. Prove renewal-state parsing rejects extra/malformed fields and far-future deadlines with `GrantCapsuleStateCorruptionError`, then unwinds the owner lock and uses the same fixed-root reset/revalidation path before one restart. Its revision CAS prevents an older tab from overwriting attempts, nextAttemptAt, terminal reason, or lastSuccessAt. Pass ROOT, wrong-owner, and correct-owner/wrong-session capabilities and expect only the correctly bound owner/session lease to write. Separately, a typed `GrantCapsuleCorruptionError` or corrupt device key invokes the atomic three-store reset and may recreate only because the scheduler has captured a still-current `OnlineAuthProfile`; it is not stored as terminal CONTENT.

Add `currentOnlineSessionGeneration.test.ts` before implementation. The
production caller acquires `runWithOnlineSessionIngress(...)` before each
password/passkey/OTP/session-resume request and holds it through
`establishOnlineSession(profile, ingress)`. Pause `/auth/session` A, start login
B in another tab, and prove B sends no request until A either publishes or
loses its ingress authority. Repeat under the durable fallback, expire/take
over A, then prove A's late response cannot write the fixed row, publish memory,
or broadcast. This prevents last-response-wins before it reaches the cookie
profile publication boundary.

After marker A is established, let tab B establish marker B and commit the
fixed opaque row **before** posting its BroadcastChannel message. Hold that
message instead of delivering it. Tab A still has marker A in memory, but a
capsule/state CAS under A returns stale with zero writes. Delivering the message
later only clears A's UI state; it is not the security boundary. Re-establishing
the same marker through a newly owned ingress remains valid. Conditional
retirement by A after B won returns `ALREADY_REPLACED` and cannot delete B's row
or broadcast a global logout.

Also prove production activation order: password login, passkey login, OTP,
session bootstrap, and online resumption enter the ingress boundary before
network and await `establishOnlineSession(profile, ingress)`; the fixed row is
readable before `setSession` publishes memory/broadcast. Fresh login captures
and retires the old pair inside the ingress boundary before its authentication
request. Every protected request captures
`OnlineSessionRetirementAuthority` **before** fetch. A protected 401 retires
only that captured pair and clears memory only if its local fence is still
current. Test same-document A request -> install B -> late 401 A, and the same
race across tabs: both preserve B's row and in-memory session. `UNSUPPORTED`
may still establish an online-only in-memory session under the still-owned
ingress, but no capsule/state writer runs without a persisted shared pair.

- [ ] **Step 2: Add failing stale-response and monotonic-CAS tests**

Exercise these races:

~~~ts
const oldRequest = deferred<SignedOfflineGrant>();
fetchOfflineGrant.mockReturnValueOnce(oldRequest.promise);
const first = ensure("LOCAL_DATA_READY");

setSession({ ...profile, sessionMarker: "N".repeat(43) });
oldRequest.resolve(oldGrant.grant);

await expect(first).resolves.toBe("NAO_APLICAVEL");
expect(await loadGrantCapsule(OWNER_LOOKUP)).toBeNull();
~~~

Repeat for logout, owner change, and relogin of the same owner. Add a second barrier **after** the post-fetch session recheck and all Web Crypto work but before the combined CAS: replace the session there and expect the captured `OnlineAuthSessionFence` to reject before transaction open. Add a third barrier after transaction open but before `put`: replacing the session must fire the fence signal, abort both capsule/state writes, and leave raw records byte-for-byte unchanged. Same-marker restoration remains valid. Add the cross-tab barrier independently: B commits the fixed shared pair, delivery of B's BroadcastChannel message to A is delayed, and A's already-open flight must still fail the transactional shared-row comparison. Add a takeover barrier after the response: let A's lease expire, let B write both capsule and renewal state, then resume A; A must return without changing either raw record or B's lease. In a capsule or state CAS conflict, reload/decrypt the winner outside the transaction, compare authEpoch, emitidoEm, lastTrustedTime, and scheduling state, and retry only when the candidate remains non-regressive.

- [ ] **Step 3: Run scheduler/API tests and record RED**

Run:

~~~bash
cd apps/web
npm test -- src/features/auth/currentOnlineSessionGeneration.test.ts src/features/auth/onlineSessionIngress.test.ts src/features/auth/renovacaoDoGrantOffline.test.ts src/features/auth/authSession.test.ts src/features/auth/authService.test.ts src/features/auth/emailOtpApi.test.ts src/features/auth/passkeyApi.test.ts src/features/auth/retomadaDaSessao.test.ts src/features/auth/offlineVault.test.ts src/features/auth/authApi.test.ts src/lib/api/apiClient.test.ts src/features/auth/offlineVaultRepository.test.ts
~~~

Expected: FAIL because the current function depends on a live password key and has no state machine.

- [ ] **Step 4: Implement persistent throttle and failure classification**

Use six hours from the last confirmed signed issuance:

~~~ts
const ISSUE_INTERVAL_MS = 6 * 60 * 60 * 1_000;
const BACKOFF_MIN_MS = 60_000;
const BACKOFF_MAX_MS = 60 * 60 * 1_000;

function transientDelay(attempts: number, random: number): number {
  const base = Math.min(
    BACKOFF_MAX_MS,
    BACKOFF_MIN_MS * 2 ** Math.max(0, attempts - 1),
  );
  return Math.max(
    BACKOFF_MIN_MS,
    Math.min(
      BACKOFF_MAX_MS,
      Math.floor(base * (0.75 + random * 0.5)),
    ),
  );
}
~~~

Add exact random=0 and random=1 boundary tests at the first and capped attempts: the returned delay is never below one minute or above one hour.

Implement `currentOnlineSessionGeneration.ts` as the production session
publication boundary. Every ingress wrapper first enters
`runWithOnlineSessionIngress(kind, callback)` and performs its network request
inside that callback. `establishOnlineSession(profile, ingress)` asserts the
same authority, bootstraps/loads the non-extractable device key with a
`SESSION_INGRESS` bootstrap authority, derives ownerLookup/sessionLookup, and
commits `current-online-session:v1` in one readwrite transaction over
`grant_capsule_keys` plus `grant_capsule_state`. That transaction rereads the
keyset and sidecar generation, validates the exact unexpired ingress row, and
writes only while the caller still owns it. A late response after lease
takeover is `ALREADY_REPLACED`; it never reaches memory. Only after
`transaction.done` may the winner call the existing in-memory setter, dispatch
`AUTH_SESSION_CHANGED_EVENT`, or post `SESSION_REPLACED`. For `UNSUPPORTED`,
the still-owned ingress may establish online memory explicitly as online-only;
every capsule path returns `SEM_SUPORTE` and no fake shared pair is created.

`captureOnlineSessionRetirementAuthority()` captures the profile, local fence,
device keys if supported, and exact fixed row before any protected request.
`retireOnlineSession(authority)` conditionally deletes the fixed row only when
it still equals that captured pair and clears memory only while the captured
fence remains current. `RETIRED` may publish logout;
`ALREADY_REPLACED` preserves the new same-document or cross-tab session and
must not broadcast logout over it. `apiFetch` obtains this authority before
`fetch`, not after a 401. Update every production ingress
(`initializeAuthSession`, CPF login, OTP, passkey login, and
`retomarSessaoOnline`) to wrap network plus establishment. Update fresh-login
starts, confirmed logout, offline activation, and protected 401 handling to use
the captured conditional retirement API. The low-level memory setter remains a
test/internal seam; add a source-boundary test that the named production
modules do not import it directly. This serialized network-through-publication
boundary plus the IndexedDB row, not response/BroadcastChannel delivery order,
is the cross-tab commit authority.

Because a fenced corruption reset deliberately deletes the current-session row
with the old key generation, the online recovery path must recommit either the
same still-current profile under its local/shared `CURRENT_SESSION` authority
or the verified not-yet-published profile under its still-owned
`SESSION_INGRESS` authority. The reset preserves only that exact ingress row;
takeover/expiry during recovery leaves no row or memory publication for the
stale response.

State key is ownerLookup plus a separator plus sessionLookup. Obtain both only from `getOnlineSession()` and return `NAO_APLICAVEL` for an offline profile. Pass a `CURRENT_SESSION` authority containing that exact profile, its local fence, and parsed fixed row to `bootstrapDeviceGrantKeys`; only this online path, or the separate still-owned session-ingress path above, may recover/create keys after a `CORRUPT` reset. Recheck parsed state after acquiring the cross-tab lock. Every state candidate gets a fresh random revision and is persisted through `compareAndSwapGrantCapsuleRenewalState`, whose transaction rereads the lease row and generation from `grant_capsule_state`. It requires an OWNER capability for the candidate ownerLookup and exact candidate sessionLookup key; ROOT/wrong-owner/wrong-session capabilities fail closed. `CURRENT_SESSION` authority also validates the fixed shared pair and local abort fence in that transaction. The sole exception is `CAPTURED_AUTH_FAILURE`: it is accepted only for a candidate whose `terminalReason` is `AUTH`, whose key matches the lease's captured pair, and after no capsule write; this lets a 401 persist its terminal classification after `apiFetch` conditionally retires/clears that captured session. It cannot write CONTENT/backoff/success or a capsule. A missing capsule emits immediately. A confirmed issuance younger than six hours returns AINDA_VALIDO without changing capsule or state revision. Only a trigger after the persisted six-hour boundary may fetch and replace the capsule. Transient failure CAS-writes attempts and nextAttemptAt. 401/403 CAS-writes terminalReason AUTH. 422 or an invalid newly fetched signature/fingerprint/owner CAS-writes CONTENT with current keyringRevision and appRevision. Existing local key/ciphertext/AAD corruption first unwinds the owner lock, calls the fixed-root-lock `resetGrantCapsuleSidecar()`, and restarts once under a newly captured online generation; it never becomes CONTENT and never clears auth vaults or outbox.

- [ ] **Step 5: Implement captured-generation validation and CAS retry**

Before fetch, capture the `OnlineAuthProfile`, its `OnlineAuthSessionFence`, the parsed fixed `CurrentOnlineSessionGenerationRecord`, ownerLookup, sessionLookup, capsule revision, state revision, device-key generation, and lease capability, then call both fence/lease assertions immediately before the request. The fixed record must already equal the derived pair. After fetch, call `getOnlineSession()` again, recompute both lookups, reassert the fence and lease, and discard unless all still match; never fall back to `getSession()`. Verify response with verifyNewOfflineGrant, require same owner, non-regressive authEpoch and emitidoEm, maximum seven days, and derived scope/fingerprint. Open and validate the current capsule outside the transaction and seal both candidates outside the transaction. Reassert `sessionFence.assertCurrent()` after the last Web Crypto await, then commit a successful issuance through `compareAndSwapGrantCapsuleAndRenewalState`: one transaction spans all three approved stores; before opening it, the repository obtains its cached DB handle, synchronously reasserts the session fence, opens the transaction, and registers `sessionFence.signal` to abort it. The same transaction validates lease purpose/owner/session, the fixed shared current-session pair, the persisted device-keyset generation, and both expected revisions before writing capsule plus renewal state. A reset, same-document replacement, or cross-tab replacement whose BroadcastChannel delivery is still pending therefore produces conflict/abort and zero writes. Failure/backoff paths use the state-only CAS with the closed authority union described above; specifically, a captured `401` may persist terminal AUTH for the old owner/session pair after `apiFetch` conditionally retires/clears memory, but it never calls either capsule-writing CAS. On CONFLICT, reopen and reevaluate before one bounded retry, so a success cannot leave capsule and lastSuccess/throttle state partially advanced. Add the barriers `load G1 -> reset to G2 -> acquire owner lease G2 -> try G1 candidate`, `post-crypto fence A -> set session B -> combined CAS`, and `B commits shared pair -> delay B broadcast to A -> A combined CAS`, expecting zero capsule/state writes.

The module Map key is ownerLookup plus sessionLookup and stores the entire promise. finally removes only the same promise instance, preventing one completion from deleting a newer flight.

- [ ] **Step 6: Run scheduler/API tests and record GREEN**

Run:

~~~bash
cd apps/web
npm test -- src/features/auth/currentOnlineSessionGeneration.test.ts src/features/auth/onlineSessionIngress.test.ts src/features/auth/renovacaoDoGrantOffline.test.ts src/features/auth/authSession.test.ts src/features/auth/authService.test.ts src/features/auth/emailOtpApi.test.ts src/features/auth/passkeyApi.test.ts src/features/auth/retomadaDaSessao.test.ts src/features/auth/offlineVault.test.ts src/features/auth/authApi.test.ts src/lib/api/apiClient.test.ts src/features/auth/grantCapsuleCrypto.test.ts src/features/auth/grantCapsuleLock.test.ts src/features/auth/offlineVaultRepository.test.ts
~~~

Expected: PASS, including the withheld-BroadcastChannel cross-tab fence case.

- [ ] **Step 7: Commit automatic renewal policy**

~~~bash
git add apps/web/src/features/auth/currentOnlineSessionGeneration.ts apps/web/src/features/auth/currentOnlineSessionGeneration.test.ts apps/web/src/features/auth/renovacaoDoGrantOffline.ts apps/web/src/features/auth/renovacaoDoGrantOffline.test.ts apps/web/src/features/auth/authSession.ts apps/web/src/features/auth/authSession.test.ts apps/web/src/features/auth/authService.ts apps/web/src/features/auth/authService.test.ts apps/web/src/features/auth/emailOtpApi.ts apps/web/src/features/auth/emailOtpApi.test.ts apps/web/src/features/auth/passkeyApi.ts apps/web/src/features/auth/passkeyApi.test.ts apps/web/src/features/auth/retomadaDaSessao.ts apps/web/src/features/auth/retomadaDaSessao.test.ts apps/web/src/features/auth/offlineVault.ts apps/web/src/features/auth/offlineVault.test.ts apps/web/src/features/auth/authApi.ts apps/web/src/features/auth/authApi.test.ts apps/web/src/lib/api/apiClient.ts apps/web/src/lib/api/apiClient.test.ts apps/web/src/features/auth/offlineVaultRepository.ts apps/web/src/features/auth/offlineVaultRepository.test.ts
git commit -m "feat(auth): renew offline capsule automatically"
~~~

---

### Task 6: Bind password vaults to current capsules and migrate v3 incrementally

**Files:**
- Modify: apps/web/src/features/auth/offlineVault.types.ts:62-86
- Modify: apps/web/src/features/auth/passwordOfflineVault.ts:35-54, 66-314, 327-483
- Modify: apps/web/src/features/auth/collaborativeOfflineGrant.ts:29-167
- Modify: apps/web/src/features/auth/offlineVaultRepository.ts
- Test: apps/web/src/features/auth/passwordOfflineVault.test.ts
- Test: apps/web/src/features/auth/collaborativeOfflineGrant.test.ts
- Test: apps/web/src/features/auth/offlineVaultRepository.test.ts

**Interfaces:**
- Consumes: device cpfLookup/ownerLookup, historical/current verifiers, current capsule, and live password key optimization.
- Produces:

~~~ts
export type LegacyOfflinePasswordVaultMetadata = {
  key: string;
  versao: 3;
  cpfLookup?: string;
  cpfSalt: string;
  cpfVerifier: string;
  passwordSalt: string;
  kdf: "PBKDF2-SHA256";
  kdfIterations: 600_000;
  iv: string;
  ciphertext: string;
  serverKeyFingerprint: string;
  atualizadoEm: string;
  failedAttemptState: OfflinePasswordVaultFailureState;
};

export type OfflinePasswordVaultMetadata = {
  key: string;
  versao: 4;
  cpfLookup: string;
  passwordSalt: string;
  kdf: "PBKDF2-SHA256";
  kdfIterations: 600_000;
  iv: string;
  ciphertext: string;
  serverKeyFingerprint: string;
  atualizadoEm: string;
  failedAttemptState: OfflinePasswordVaultFailureState;
};

export type PasswordVaultMetadata =
  | LegacyOfflinePasswordVaultMetadata
  | OfflinePasswordVaultMetadata;

export type PasswordVaultHistoricalBinding = {
  signedGrant: SignedOfflineGrant;
  verified: VerifiedHistoricalOfflineGrant;
  ownerId: string;
  scopeFingerprint: string;
  authEpoch: number;
  lastTrustedTime: string;
};

export async function openPasswordOfflineVault(input: {
  cpf: string;
  password: string;
  metadata: PasswordVaultMetadata;
  now?: () => number;
}): Promise<
  | {
      kind: "OPENED";
      binding: PasswordVaultHistoricalBinding;
      metadata: PasswordVaultMetadata;
    }
  | { kind: "REJECTED"; metadata: PasswordVaultMetadata }
>;

export async function runLegacyPasswordVaultMigrationBatch(input: {
  cpf: string;
  cpfLookup: string;
  budget: 1;
}): Promise<{ examined: 0 | 1; indexed: 0 | 1 }>;
~~~

- [ ] **Step 1: Write failing expired-binding and epoch-isolation tests**

Create a password vault whose embedded signed grant is expired but valid as historical material. Store a current capsule for the same owner and epoch:

~~~ts
await expect(unlockCollaborativeOfflineGrant(
  CPF,
  PASSWORD,
  legacyVaultWithExpiredGrant,
)).resolves.toBeUndefined();

expect(getOfflineGrant()).toMatchObject({
  authEpoch: 7,
  emitidoEm: "2026-08-17T15:00:00Z",
});
~~~

Then vary capsule owner, authEpoch, fingerprint, ciphertext, scope, emitidoEm older than the historical binding, and lastTrustedTime regression. Each must reject without replacing the stored vault or capsule. Keep a pre-reset vault and a post-reset capsule; prove the old password cannot regain access through the new epoch.

Add the fallback case: if no valid capsule exists, the embedded grant activates only before its own expiraEm. A legacy grant without authEpoch never consults a capsule.

- [ ] **Step 2: Write failing v4 direct-lookup and bounded-migration tests**

After creating a v4 vault, inspect persisted JSON:

~~~ts
expect(serialized).not.toContain(CPF);
expect(serialized).not.toContain(OWNER_ID);
expect(serialized).not.toContain(signedGrant.payload);
expect(metadata).toMatchObject({
  versao: 4,
  cpfLookup: expect.stringMatching(/^[A-Za-z0-9_-]{43}$/),
});
expect("cpfSalt" in metadata).toBe(false);
expect("cpfVerifier" in metadata).toBe(false);
~~~

Seed 25 v3 vaults for different people and two for the current CPF. One batch must perform exactly one 600,000-iteration verifier, attach cpfLookup only after a match, and leave all other records unchanged. After a confirmed v4 put and reread, converge duplicates indexed for the same CPF while preserving every other CPF. Assert no unbounded PBKDF2 loop occurs in the login submit promise.

Assert the two persisted metadata schemas by exact key set, not only `toMatchObject`:

~~~ts
expect(Object.keys(v3).sort()).toEqual([
  "atualizadoEm", "ciphertext", "cpfLookup", "cpfSalt", "cpfVerifier",
  "failedAttemptState", "iv", "kdf", "kdfIterations", "key",
  "passwordSalt", "serverKeyFingerprint", "versao",
].sort());
expect(Object.keys(v4).sort()).toEqual([
  "atualizadoEm", "ciphertext", "cpfLookup", "failedAttemptState", "iv",
  "kdf", "kdfIterations", "key", "passwordSalt",
  "serverKeyFingerprint", "versao",
].sort());
~~~

For an unindexed v3 record, omit `cpfLookup` entirely and assert the same v3 list minus that key. Reject unknown metadata keys before PBKDF2/decryption. The decrypted plaintext for both versions has exactly `signedGrant`, `ownerId`, `scopeFingerprint`, `authEpoch`, and `lastTrustedTime`; those fields never move into public metadata.

- [ ] **Step 3: Run password/repository tests and record RED**

Run:

~~~bash
cd apps/web
npm test -- src/features/auth/passwordOfflineVault.test.ts src/features/auth/collaborativeOfflineGrant.test.ts src/features/auth/offlineVaultRepository.test.ts
~~~

Expected: FAIL because v3 rejects an expired grant before historical binding, there is no capsule lookup, and CPF lookup still scans twenty records.

- [ ] **Step 4: Implement v4 password envelopes and historical opening**

For v4, derive cpfLookup from the device HMAC key before repository lookup. Do not persist cpfSalt/cpfVerifier. Continue deriving the non-extractable AES key from passwordSalt with PBKDF2-SHA256 at 600,000 iterations. v4 AAD contains key, versao, cpfLookup, serverKeyFingerprint, kdf, and kdfIterations.

Opening a password vault decrypts first, calls verifyHistoricalOfflineGrant, requires `verified.claims.colaboradorId === plaintext.ownerId`, `verified.claims.authEpoch === plaintext.authEpoch`, exact scope/fingerprint, and monotonic lastTrustedTime, and returns the exact `PasswordVaultHistoricalBinding` above without activating a session. collaborativeOfflineGrant then computes ownerLookup, opens the current capsule, requires exact owner and authEpoch plus capsule emitidoEm not earlier than the historical grant, and activates only capsule claims. If the capsule is merely absent or unusable by trust/expiry policy, call verifyCurrentOfflineGrant on the embedded signedGrant and activate only when it remains current. If opening reports typed local `CORRUPT`, unwind any owner lock, call the fixed-lock fenced reset, never recreate keys while offline, and only then try the embedded strict-current fallback; preserve the new generation fence inside `grant_capsule_state`, password vault metadata, and operational work.

After successful activation, reseal the password vault with the current grant and a fresh IV only when its live derived key is still in memory. Failure to perform this defensive optimization must not discard a valid capsule-backed unlock.

- [ ] **Step 5: Implement direct lookup and one-record idle migration**

Use the `by-cpf-lookup` index installed by Task 3 during the version-4 DB upgrade. An old v3 may receive the optional opaque cpfLookup without changing its ciphertext or v3 AAD; that lookup is only a candidate locator and never authorizes decryption.

Repository operations:

~~~ts
export async function loadPasswordVaultsByCpfLookup(
  lookup: string,
): Promise<PasswordVaultMetadata[]>;

export async function listLegacyPasswordVaultsWithoutLookup(
  limit: 1,
): Promise<LegacyOfflinePasswordVaultMetadata[]>;

export async function attachCpfLookupAfterVerifierMatch(
  key: string,
  lookup: string,
): Promise<void>;

export async function replacePasswordVaultAfterConfirmedSave(
  metadata: OfflinePasswordVaultMetadata,
): Promise<void>;
~~~

The login flow creates and confirms v4 first. Schedule runLegacyPasswordVaultMigrationBatch with requestIdleCallback; use a short setTimeout fallback where requestIdleCallback is absent. Pass budget: 1. A cancellation returned by the scheduler clears the callback on logout/session replacement.

- [ ] **Step 6: Run password/repository tests and record GREEN**

Run:

~~~bash
cd apps/web
npm test -- src/features/auth/passwordOfflineVault.test.ts src/features/auth/collaborativeOfflineGrant.test.ts src/features/auth/offlineVaultRepository.test.ts src/features/auth/authService.test.ts
~~~

Expected: PASS.

- [ ] **Step 7: Commit password vault/capsule binding**

~~~bash
git add apps/web/src/features/auth/offlineVault.types.ts apps/web/src/features/auth/passwordOfflineVault.ts apps/web/src/features/auth/collaborativeOfflineGrant.ts apps/web/src/features/auth/offlineVaultRepository.ts apps/web/src/features/auth/passwordOfflineVault.test.ts apps/web/src/features/auth/collaborativeOfflineGrant.test.ts apps/web/src/features/auth/offlineVaultRepository.test.ts apps/web/src/features/auth/authService.test.ts
git commit -m "feat(auth): bind password vaults to device capsules"
~~~

---

### Task 7: Bind passkey PRF vaults to the capsule and reprovision on epoch change

**Files:**
- Modify: apps/web/src/features/auth/offlineVault.ts:38-197, 495-556
- Modify: apps/web/src/features/auth/passkeyApi.ts:49-152
- Modify: apps/web/src/features/auth/offlineVaultRepository.ts:29-70
- Test: apps/web/src/features/auth/offlineVault.test.ts
- Test: apps/web/src/features/auth/passkeyApi.test.ts
- Test: apps/web/src/features/auth/DeviceSecurityPage.policy.test.ts

**Interfaces:**
- Consumes: passkey PRF output, historical/current verifiers, ownerLookup, current capsule, and confirmed vault repository writes.
- Produces:

~~~ts
export async function unlockOfflineVault(
  metadata: OfflineVaultMetadata,
  options?: { now?: () => number },
): Promise<OfflineUnlockResult>;

export async function preparePasskeyOfflineVaultAfterAuthentication(input: {
  profile: OnlineAuthProfile;
  now?: () => number;
}): Promise<"READY" | "PRF_UNAVAILABLE" | "NO_EXISTING_PRF_VAULT">;

export async function replaceOfflineVaultAfterConfirmedSave(
  previousKey: string | null,
  metadata: OfflineVaultMetadata,
): Promise<void>;
~~~

- [ ] **Step 1: Write failing passkey owner/epoch/capsule tests**

With a PRF fixture, open an expired embedded grant and a current same-owner/same-epoch capsule; expect UNLOCKED. Repeat with another owner, another authEpoch, legacy v1 grant, regressive emitidoEm, tampered capsule, and revoked key; expect rejection and no offline session.

For passkey registration, enforce the sequence:

~~~ts
expect(callOrder).toEqual([
  "registration-verify",
  "offline-grant",
  "vault-put",
  "vault-reread",
  "ready",
]);
~~~

For an online passkey login after authEpoch advances, return PRF from a follow-up ceremony, create/re-read the replacement vault, then delete the previous record. If PRF is unavailable, keep online login successful but return PRF_UNAVAILABLE and preserve the old vault without letting it consume the new capsule.

- [ ] **Step 2: Run passkey tests and record RED**

Run:

~~~bash
cd apps/web
npm test -- src/features/auth/offlineVault.test.ts src/features/auth/passkeyApi.test.ts src/features/auth/DeviceSecurityPage.policy.test.ts
~~~

Expected: FAIL because passkey vaults activate their embedded grant directly, renewal requires PRF, and writes are not reread before READY.

- [ ] **Step 3: Separate PRF authentication from capsule authorization**

After PRF decrypts the embedded signedGrant, verify it as historical binding. Require exact equality between the historical verifier's valid UUID owner/authEpoch and the encrypted vault binding before computing ownerLookup. Open the current capsule and require the same owner/authEpoch. Activate capsule claims only after strict current verification. If no capsule is usable by presence/trust/expiry policy, verify the embedded grant through the strict current path. If local opening reports typed `CORRUPT`, unwind any owner lock, invoke the fixed-root fenced reset across only the three sidecar stores, retain only the new generation row in state, and do not create keys from the offline passkey principal before applying that strict-current fallback.

Remove renewOfflineVault as the automatic renewal path. The capsule scheduler never calls navigator.credentials. Keep a named PRF reprovision function used only after an explicit passkey login or registration.

- [ ] **Step 4: Make registration and reprovision confirmation-safe**

After createOfflineVault, save through replaceOfflineVaultAfterConfirmedSave, reread, validate exact equality, and only then report READY. During epoch reprovision, preserve the prior record until the new PRF vault is persisted and reread. Cadastrar a passkey must not clear or replace the online session; it uses the session/epoch returned by Plan 1.

- [ ] **Step 5: Run passkey tests and record GREEN**

Run:

~~~bash
cd apps/web
npm test -- src/features/auth/offlineVault.test.ts src/features/auth/passkeyApi.test.ts src/features/auth/DeviceSecurityPage.policy.test.ts
~~~

Expected: PASS.

- [ ] **Step 6: Commit passkey capsule continuity**

~~~bash
git add apps/web/src/features/auth/offlineVault.ts apps/web/src/features/auth/passkeyApi.ts apps/web/src/features/auth/offlineVaultRepository.ts apps/web/src/features/auth/offlineVault.test.ts apps/web/src/features/auth/passkeyApi.test.ts apps/web/src/features/auth/DeviceSecurityPage.policy.test.ts
git commit -m "feat(auth): bind passkey vaults to current capsules"
~~~

---

### Task 8: Wire automatic triggers and remove every renewal prompt path

**Files:**
- Modify: apps/web/src/App.tsx:1-35, 146-170, 245-308, 342-349
- Modify: apps/web/src/features/auth/retomadaDaSessao.ts:37-83
- Modify: apps/web/src/features/auth/authService.ts:44-134
- Modify: apps/web/src/features/auth/authSession.ts:73-96, 152-173
- Delete: apps/web/src/features/auth/OfflineGrantRenewalPrompt.tsx
- Delete: apps/web/src/features/auth/OfflineGrantRenewalPrompt.test.tsx
- Modify: apps/web/src/index.css:360-401
- Rewrite tests: apps/web/src/App.onlineHandoff.test.tsx
- Modify tests: apps/web/src/App.authPolicy.test.ts
- Modify tests: apps/web/src/features/auth/retomadaDaSessao.test.ts
- Modify tests: apps/web/src/features/auth/authService.test.ts
- Modify tests: apps/web/src/features/auth/authSession.test.ts
- Test: apps/web/src/App.offlineUnlock.test.tsx
- Test: apps/web/src/features/auth/LoginPage.behavior.test.tsx

**Interfaces:**
- Consumes: garantirGrantOfflineAutomatico(trigger) and cancelAutomaticOfflineGrantForCurrentDocument().
- Produces: four fire-and-forget trigger paths with no modal, password request, passkey request, or effect on sync result.

- [ ] **Step 1: Rewrite the App test to make prompt removal fail first**

Remove the prompt mock from App.onlineHandoff.test.tsx and replace the old test at lines 142-150:

~~~tsx
it("prepares offline access silently after local data is ready", async () => {
  activeSession = profile;
  render(<App />);

  await waitFor(() => {
    expect(mocks.garantirGrantOfflineAutomatico)
      .toHaveBeenCalledWith("LOCAL_DATA_READY");
  });
  expect(screen.queryByText(/Renovar acesso offline/i))
    .not.toBeInTheDocument();
  expect(screen.queryByLabelText(/Senha para renovar/i))
    .not.toBeInTheDocument();
});
~~~

Add tests for:

- RETOMADA schedules SESSION_RESUMED after setSession.
- SYNC_COMPLETED_EVENT schedules SYNC_COMPLETED but does not alter the sync completion event/result.
- visibilitychange schedules VISIBLE only when visible, online, and an online session exists.
- repeated events during backoff do not render notices.
- logout and a `SESSION_REPLACED` object carrying a different marker cancel the current-document flight and clear live password keys; the same marker is ignored.
- LoginPage and OfflineUnlockPage continue to render their existing copy and controls.

- [ ] **Step 2: Run App/session tests and record RED**

Run:

~~~bash
cd apps/web
npm test -- src/App.onlineHandoff.test.tsx src/App.authPolicy.test.ts src/App.offlineUnlock.test.tsx src/features/auth/retomadaDaSessao.test.ts src/features/auth/authService.test.ts src/features/auth/authSession.test.ts src/features/auth/LoginPage.behavior.test.tsx
~~~

Expected: FAIL because App still imports/renders OfflineGrantRenewalPrompt and the four automatic triggers are not wired.

- [ ] **Step 3: Remove prompt code and CSS**

Delete OfflineGrantRenewalPrompt.tsx and its test. Remove:

- OfflineGrantRenewalPrompt import.
- renewalNeedsPassword state.
- the result branch for SENHA_NECESSARIA.
- modal rendering.
- auth-renewal-prompt CSS at index.css:360-401.

No replacement banner or toast is added. Persistent terminal state remains queryable by the device-security feature through repository state, without interrupting work.

- [ ] **Step 4: Wire all four triggers**

Use one fire-and-forget helper that never throws into React:

~~~ts
function scheduleOfflinePreparation(trigger: AutomaticGrantTrigger): void {
  void garantirGrantOfflineAutomatico(trigger).catch(() => undefined);
}
~~~

After localDataReady becomes true, call LOCAL_DATA_READY. When the reconnect wrapper receives RETOMADA, call SESSION_RESUMED. Add SYNC_COMPLETED_EVENT after the existing durable event. Add document visibilitychange and require visibilityState visible, navigator.onLine, and `getOnlineSession() !== null` before VISIBLE; `hasOfflineSession()` or a generic `getSession()` is not enough.

In authSession local clear and cross-tab replacement, call clearPasswordVaultKeys and cancelAutomaticOfflineGrantForCurrentDocument. Do not clear capsules belonging to other owners.

- [ ] **Step 5: Run App/session tests and record GREEN**

Run:

~~~bash
cd apps/web
npm test -- src/App.onlineHandoff.test.tsx src/App.authPolicy.test.ts src/App.offlineUnlock.test.tsx src/features/auth/retomadaDaSessao.test.ts src/features/auth/authService.test.ts src/features/auth/authSession.test.ts src/features/auth/LoginPage.behavior.test.tsx
~~~

Expected: PASS and no test imports OfflineGrantRenewalPrompt.

- [ ] **Step 6: Prove the deleted prompt has no references**

Run:

~~~bash
if rg -n "OfflineGrantRenewalPrompt|SENHA_NECESSARIA|auth-renewal-prompt|Senha para renovar|Renovar acesso offline" apps/web/src; then
  echo "manual offline-renewal prompt references remain" >&2
  exit 1
fi
~~~

Expected: no output.

- [ ] **Step 7: Commit trigger integration and prompt removal**

~~~bash
git add -A apps/web/src/App.tsx apps/web/src/features/auth/retomadaDaSessao.ts apps/web/src/features/auth/authService.ts apps/web/src/features/auth/authSession.ts apps/web/src/features/auth/OfflineGrantRenewalPrompt.tsx apps/web/src/features/auth/OfflineGrantRenewalPrompt.test.tsx apps/web/src/index.css apps/web/src/App.onlineHandoff.test.tsx apps/web/src/App.authPolicy.test.ts apps/web/src/App.offlineUnlock.test.tsx apps/web/src/features/auth/retomadaDaSessao.test.ts apps/web/src/features/auth/authService.test.ts apps/web/src/features/auth/authSession.test.ts apps/web/src/features/auth/LoginPage.behavior.test.tsx
git commit -m "feat(auth): remove manual offline renewal prompt"
~~~

---

### Task 9: Prove persistence and auth-store isolation before release

**Files:**
- Create: apps/web/src/features/auth/automaticOfflineGrantJourney.test.ts
- Create: apps/web/src/features/auth/deviceGrantCapsuleBoundary.test.ts
- Modify: apps/web/src/pwaServiceWorkerContract.test.ts

**Interfaces:**
- Consumes: all PWA capsule modules and the Plan 1 backend contract.
- Produces: commit-ready automated persistence and isolation evidence for the unreleased combined candidate. It does not claim browser, deployment, or Plan 3 old-namespace evidence.

- [ ] **Step 1: Write the failing full fake-IndexedDB journey**

Use fake-indexeddb, real Web Crypto, and vi.resetModules:

~~~ts
it("survives module reload and preserves operational work byte for byte", async () => {
  await onlineLoginAndPreparePasswordVault();
  const capsuleBefore = await readRawCapsule();

  vi.resetModules();
  mockNetworkOffline();
  await unlockWithCpfAndPassword();
  await createAndEditOfflineRdo();

  const outboxAfterOfflineWork = await readRawOperationalOutbox();
  mockNetworkOnline();
  await dispatchDurableSyncCompleted();
  const capsuleInsideThrottle = await readRawCapsule();

  expect(capsuleBefore.ciphertext).not.toContain(signedGrant.payload);
  expect(outboxAfterOfflineWork).toEqual(expectedRdoOutbox);
  expect(await readRawOperationalOutbox()).toEqual(outboxAfterOfflineWork);
  expect(capsuleInsideThrottle.revision).toBe(capsuleBefore.revision);

  clock.advanceBy(6 * 60 * 60 * 1_000 + 1);
  await dispatchDurableSyncCompleted();
  const capsuleAfterEligibleRenewal = await readRawCapsule();
  expect(capsuleAfterEligibleRenewal.revision)
    .not.toBe(capsuleBefore.revision);
});
~~~

Add: a sync/reload/visibility trigger at `5h59m59s` preserves the exact revision and performs no grant POST; the first eligible trigger at `6h00m00.001s` performs one renewal and atomically changes capsule plus state revisions. Feature-probe `UNSUPPORTED` keeps all existing sidecar/vault/grant records and writes no clear fallback. Persisted-key/ciphertext `CORRUPT` advances the generation fence, clears capsule/key records and selectively removes renewal/current-session/capsule-lease rows across exactly the three approved stores in one transaction, retains the new generation plus vaults/cpf_grants/outbox, rejects every stale writer, and consumes the root lease. It preserves an ingress row only when the exact unexpired authority owns the online response being established; otherwise it preserves none and does not recreate anything until a `CURRENT_SESSION` or `SESSION_INGRESS` bootstrap authority exists. Also prove 401 aborts concurrent sync through the existing guard and preserves the queue; 5xx/403/IndexedDB failure after a completed sync does not turn that sync into failure.

- [ ] **Step 2: Add a static and runtime boundary test**

deviceGrantCapsuleBoundary.test.ts reads the source graph beginning at renovacaoDoGrantOffline.ts and fails if it imports cortexDb, getCortexDb, outbox repositories, RDO stores, object upload, photo upload, sync push/pull/ack, or financeiro. At runtime, spy on indexedDB.open and assert capsule renewal opens only cortex-auth-vaults.

The PWA service-worker contract must continue to avoid caching /api authentication responses and must not place grants, cookies, or capsule logic in the service worker.

- [ ] **Step 3: Run integration tests and record RED or GREEN with evidence**

Run:

~~~bash
cd apps/web
npm test -- src/features/auth/automaticOfflineGrantJourney.test.ts src/features/auth/deviceGrantCapsuleBoundary.test.ts src/pwaServiceWorkerContract.test.ts
~~~

Expected before completing prior tasks: RED at missing capsule behavior. Expected after Tasks 1-8: PASS.

- [ ] **Step 4: Run the complete PWA quality gate**

Run:

~~~bash
cd apps/web
npm test
npm run lint
npm run build
~~~

Expected: all tests pass, lint exits 0, and production PWA build plus boundary verification exits 0.

- [ ] **Step 5: Commit the pre-release automated proof**

~~~bash
git add apps/web/src/features/auth/automaticOfflineGrantJourney.test.ts apps/web/src/features/auth/deviceGrantCapsuleBoundary.test.ts apps/web/src/pwaServiceWorkerContract.test.ts
git commit -m "test(auth): prove automatic offline capsule boundaries"
~~~

---

## Post-release browser acceptance owned by the combined Plan 3 gate

The following acceptance is deliberately **not** a Task 9 prerequisite and is
never committed into the candidate SHA it is meant to verify. Plan 3's combined
release gate invokes this runbook only after Render and Cloudflare serve the
exact combined Plans 1–3 revision.

### Perform the real Edge/Chrome feature probe on the exact deployed revision

Use the same published revision in Render and Cloudflare. In a corporate Edge profile:

1. Log in online once after the combined Plans 1-3 cutover.
2. In DevTools Application, inspect cortex-auth-vaults. Confirm grant_capsules contains opaque ownerLookup/ciphertext only; grant_capsule_keys contains CryptoKey objects; grant_capsule_state contains only opaque renewal keys, random lease holders/generations, and deadlines.
3. In DevTools Console, load the persisted keys through the debug-free application path used by the test harness and verify exportKey rejects. Do not add a production debug export.
4. Reload, close the full browser, reopen, and confirm no renewal prompt appears and the persisted non-extractable key can still open a capsule.
5. Disconnect network and reload the installed PWA. Unlock with CPF/password.
6. Repeat in a separate prepared profile with passkey/PRF.
7. Create and edit an RDO offline. Record its local outbox ID.
8. Reconnect less than six hours after the confirmed issuance. Confirm exactly one idempotent replay, zero new grant POSTs, and the same capsule revision.
9. In a separately prepared browser profile whose confirmed issuance is older than six hours, trigger sync/visibility, confirm one silent POST `/api/auth/offline-grant`, and confirm revision advances. Do not move the system clock backward or call a production debug hook; prepare this profile more than six real hours before the acceptance window.
10. Confirm the outbox item disappears only after server confirmation.
11. Disconnect again and prove both prepared profiles still unlock.
12. Decode only the signed test evidence through the existing verifier and confirm expiraEm minus emitidoEm is no more than seven days.
13. Open two tabs during the eligible renewal from step 9 and confirm one POST `/api/auth/offline-grant`.

Repeat the structured-clone feature probe in every supported corporate browser. A fake-indexeddb unit test is not browser evidence.

### Record evidence without overstating the seven-day soak

Create docs/verification/2026-08-17-automatic-offline-grant-renewal-pwa.md containing:

- exact Git SHA;
- exact production workflow/run;
- Render served revision;
- Cloudflare artifact revision;
- browser/version and device;
- screenshots or exported redacted observations for stores;
- network trace showing one grant POST;
- network/store trace showing a sub-six-hour sync performs no grant POST and preserves revision;
- separate over-six-hour trace showing one grant POST and a newer revision;
- online→offline→RDO→reconnect result;
- password and passkey results separately;
- signed TTL calculation;
- deterministic reduced-TTL test result;
- seven-day soak status as a separate ongoing observation until real time elapses.

Do not describe a deterministic clock test as a completed seven-day real-world soak.

Store this as post-deployment evidence outside the release candidate. If the
team later commits the verification document, use a separate documentation-only
commit and state prominently that every observation applies to the recorded
deployed SHA, not to the later documentation commit. Never use that later commit
as same-revision deployment evidence.

---

## Execution dependency and handoff

1. Start from one unreleased feature branch. Implement and commit Plan 1 there first so Task 1 consumes the real `sessionMarker` and global `authEpoch` contract. Do not merge/push Plan 1 alone to `develop`; `develop` auto-publishes.
2. On that same unreleased branch, execute this plan's Tasks 1-9 in order. Each task keeps its RED, GREEN, and commit boundary, but those commits are not independent release units.
3. Implement Plan 3, legacy operational-namespace reconciliation, and pass its separate review gate before the combined branch is eligible for `develop`. Plan 3 owns old operational databases/outboxes; its absence does not permit this plan to inspect or mutate them.
4. Run the complete Plan 1 backend/integration gate, this plan's PWA/config/publication gates, and Plan 3's reconciliation/sync gates against one candidate SHA. Resolve every failure before release.
5. Before the one push, provision and revalidate the protected public keyring
   variable. `gh variable list --env production` currently has no
   `CORTEX_OFFLINE_GRANT_TRUST_KEYRING_JSON`, so absence is a hard release
   blocker, not something the workflow may discover after `develop`
   auto-publishes. Read the exact non-secret current
   `CORTEX_AUTH_OFFLINE_GRANT_KEY_ID` from the Render service environment and
   export it as `CORTEX_CURRENT_OFFLINE_KEY_ID`; never guess it from source.
   Then run this without printing the JSON:

   ~~~bash
   REPO_ROOT="$(git rev-parse --show-toplevel)"
   REPO="Stavias-Sistema-Cortex/digitalizacao-rdo-stavias"
   KEYRING_FILE="$(mktemp)"
   trap 'rm -f "$KEYRING_FILE" "$KEYRING_FILE.readback"' EXIT
   chmod 600 "$KEYRING_FILE"
   test -n "${CORTEX_CURRENT_OFFLINE_KEY_ID:?Read the current Render key id first}"
   FINGERPRINT="$(gh variable get VITE_CORTEX_OFFLINE_GRANT_PUBLIC_KEY_SHA256 --env production --repo "$REPO")"
   KEY_ID="$CORTEX_CURRENT_OFFLINE_KEY_ID" FINGERPRINT="$FINGERPRINT" \
     node -e 'const fs=require("node:fs"); const value={revision:"2026-08-17.1",keys:[{keyId:process.env.KEY_ID,fingerprint:process.env.FINGERPRINT,status:"ACTIVE",retiredCutoff:null,notAfter:"2027-08-17T00:00:00Z"}]}; fs.writeFileSync(process.argv[1],JSON.stringify(value));' \
     "$KEYRING_FILE"
   (
     cd "$REPO_ROOT/apps/web"
     VITE_CORTEX_AUTH_MODE=postgresql \
     VITE_CORTEX_RELEASE_SHA="$(git rev-parse HEAD)" \
     VITE_CORTEX_OFFLINE_GRANT_PUBLIC_KEY_SHA256="$FINGERPRINT" \
     VITE_CORTEX_OFFLINE_GRANT_TRUST_KEYRING_JSON="$(<"$KEYRING_FILE")" \
       ./validate-docker-build-args.sh
   )
   gh variable set CORTEX_OFFLINE_GRANT_TRUST_KEYRING_JSON \
     --env production --repo "$REPO" < "$KEYRING_FILE"
   gh variable get CORTEX_OFFLINE_GRANT_TRUST_KEYRING_JSON \
     --env production --repo "$REPO" > "$KEYRING_FILE.readback"
   node -e '
     const fs = require("node:fs");
     const [expectedPath, actualPath] = process.argv.slice(1);
     const expected = fs.readFileSync(expectedPath, "utf8");
     const actual = fs.readFileSync(actualPath, "utf8").replace(/\r?\n$/, "");
     if (actual !== expected) process.exit(1);
   ' "$KEYRING_FILE" "$KEYRING_FILE.readback"
   ~~~

   Verify the key ID against a freshly issued production grant or the Render
   environment and the fingerprint against `/api/health`; do not continue on a
   mismatch. This protected-variable mutation is pre-release configuration,
   not a code commit and not deployment evidence.
6. Only after all three plans, gates, and the protected-variable preflight pass,
   merge/push the combined candidate once to `develop`. Let the automatic
   production workflow publish that exact SHA; do not run an earlier manual
   deploy of Plan 1 or Plan 2.
7. After Render and Cloudflare both prove the same SHA and signer
   fingerprint/keyring chain, perform the browser journey and record
   authenticated acceptance separately.

No new runtime package is required. idb and fake-indexeddb already exist. Web Crypto, IndexedDB, Web Locks, BroadcastChannel, requestIdleCallback, and Service Worker are browser APIs. Use the lease and timer fallbacks described above when Web Locks or requestIdleCallback is unavailable.
