import "fake-indexeddb/auto";

import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { scopeFingerprint } from "../../lib/db/localDataNamespace";
import { clearSession, getSession, setSession } from "./authSession";
import {
  loadCollaborativeOfflineGrant,
  OfflineGrantOwnerMismatchError,
  saveCollaborativeOfflineGrant,
  unlockCollaborativeOfflineGrant,
} from "./collaborativeOfflineGrant";
import type {
  CurrentOfflineGrantClaims,
  LegacyOfflineCpfGrantMetadata,
  SignedOfflineGrant,
} from "./offlineVault.types";
import {
  deleteCollaborativeOfflineGrantMetadata,
  listCollaborativeOfflineGrantMetadata,
  listCollaborativeOfflineMetadata,
  saveCollaborativeOfflineGrantMetadata,
} from "./offlineVaultRepository";
import { clearPasswordVaultKeys } from "./passwordOfflineVault";
import { toBase64Url } from "./webauthnCodec";

const NOW = Date.parse("2026-07-14T12:00:00Z");
const CPF = "11144477735";
const PASSWORD = "Senha individual forte 123!";
const OWNER_ID = "00000000-0000-4000-8000-000000000001";
const OTHER_OWNER_ID = "00000000-0000-4000-8000-000000000003";
const WORKSITE_ID = "00000000-0000-4000-8000-000000000002";
const localValues = new Map<string, string>();

describe("grant colaborativo protegido por senha", () => {
  beforeEach(async () => {
    vi.useFakeTimers({ toFake: ["Date"] });
    vi.setSystemTime(NOW);
    clearSession();
    clearPasswordVaultKeys();
    localValues.clear();
    vi.stubGlobal("localStorage", {
      getItem: (key: string) => localValues.get(key) ?? null,
      removeItem: (key: string) => localValues.delete(key),
      setItem: (key: string, value: string) => localValues.set(key, value),
    });
    for (const record of await listCollaborativeOfflineMetadata()) {
      await deleteCollaborativeOfflineGrantMetadata(record.key);
    }
  });

  afterEach(() => {
    clearSession();
    clearPasswordVaultKeys();
    vi.useRealTimers();
    vi.unstubAllGlobals();
  });

  it("confirms v3 before deleting only the matching legacy v2", async () => {
    const fixture = await signedGrantFixture();
    const matchingLegacy = await legacyGrant(CPF, OWNER_ID, fixture.grant);
    const otherLegacy = await legacyGrant(
      "52998224725",
      OTHER_OWNER_ID,
      fixture.grant,
    );
    await saveCollaborativeOfflineGrantMetadata(matchingLegacy);
    await saveCollaborativeOfflineGrantMetadata(otherLegacy);

    const metadata = await saveCollaborativeOfflineGrant(
      CPF,
      PASSWORD,
      fixture.grant,
      OWNER_ID,
    );

    expect(metadata.versao).toBe(3);
    expect(await loadCollaborativeOfflineGrant(CPF)).toEqual(metadata);
    const remainingLegacy = await listCollaborativeOfflineGrantMetadata();
    expect(remainingLegacy.map((record) => record.key))
      .not.toContain(matchingLegacy.key);
    expect(remainingLegacy.map((record) => record.key))
      .toContain(otherLegacy.key);
    const persisted = JSON.stringify(metadata);
    expect(persisted).not.toContain(PASSWORD);
    expect(persisted).not.toContain(fixture.grant.payload);
    expect(persisted).not.toContain(OWNER_ID);
  });

  it("fails closed when the returned grant belongs to another collaborator", async () => {
    const fixture = await signedGrantFixture();

    await expect(saveCollaborativeOfflineGrant(
      CPF,
      PASSWORD,
      fixture.grant,
      OTHER_OWNER_ID,
    )).rejects.toBeInstanceOf(OfflineGrantOwnerMismatchError);

    expect(await loadCollaborativeOfflineGrant(CPF)).toBeNull();
  });

  it("does not promote a legacy protocol-v1 grant into v3", async () => {
    const legacy = await signedLegacyGrantFixture();

    await expect(saveCollaborativeOfflineGrant(
      CPF,
      PASSWORD,
      legacy,
      OWNER_ID,
    )).rejects.toThrow(
      "Não foi possível liberar o acesso offline neste aparelho.",
    );

    expect(await loadCollaborativeOfflineGrant(CPF)).toBeNull();
  });

  it("opens v3 with CPF plus password and activates only the signed scope", async () => {
    const fixture = await signedGrantFixture();
    const metadata = await saveCollaborativeOfflineGrant(
      CPF,
      PASSWORD,
      fixture.grant,
      OWNER_ID,
    );

    await unlockCollaborativeOfflineGrant(CPF, PASSWORD, metadata);

    expect(getSession()).toEqual({
      colaboradorId: fixture.claims.colaboradorId,
      nome: fixture.claims.nome,
      papelAcesso: fixture.claims.papelAcesso,
      escopoGlobal: fixture.claims.escopoGlobal,
      obraIds: fixture.claims.obraIds,
      expiraEm: fixture.claims.expiraEm,
    });
  });

  it("collapses wrong CPF and password while clearing an existing session", async () => {
    const fixture = await signedGrantFixture();
    const metadata = await saveCollaborativeOfflineGrant(
      CPF,
      PASSWORD,
      fixture.grant,
      OWNER_ID,
    );
    setSession(existingProfile());

    await expect(unlockCollaborativeOfflineGrant(
      "52998224725",
      PASSWORD,
      metadata,
    )).rejects.toThrow(
      "Não foi possível liberar o acesso offline neste aparelho.",
    );
    expect(getSession()).toBeNull();

    vi.setSystemTime(NOW + 1_000);
    await expect(unlockCollaborativeOfflineGrant(
      CPF,
      "Outra senha forte 456!",
      metadata,
    )).rejects.toThrow(
      "Não foi possível liberar o acesso offline neste aparelho.",
    );
    expect(getSession()).toBeNull();
  });
});

function existingProfile() {
  return {
    colaboradorId: OTHER_OWNER_ID,
    nome: "Sessão existente",
    papelAcesso: "BETA" as const,
    escopoGlobal: false,
    obraIds: ["00000000-0000-4000-8000-000000000004"],
    expiraEm: "2026-07-14T20:00:00Z",
  };
}

async function legacyGrant(
  cpf: string,
  ownerId: string,
  signedGrant: SignedOfflineGrant,
): Promise<LegacyOfflineCpfGrantMetadata> {
  const salt = crypto.getRandomValues(new Uint8Array(16));
  const material = await crypto.subtle.importKey(
    "raw",
    new TextEncoder().encode(cpf),
    "PBKDF2",
    false,
    ["deriveBits"],
  );
  const verifier = await crypto.subtle.deriveBits(
    {
      name: "PBKDF2",
      hash: "SHA-256",
      salt,
      iterations: 600_000,
    },
    material,
    256,
  );
  return {
    key: crypto.randomUUID(),
    versao: 2,
    cpfSalt: toBase64Url(salt),
    cpfVerifier: toBase64Url(verifier),
    ownerId,
    scopeFingerprint: await scopeFingerprint(
      ownerId,
      `BETA:${WORKSITE_ID}`,
    ),
    signedGrant,
    serverKeyFingerprint: "f".repeat(43),
    atualizadoEm: new Date(NOW).toISOString(),
  };
}

async function signedGrantFixture(): Promise<{
  claims: CurrentOfflineGrantClaims;
  grant: SignedOfflineGrant;
}> {
  const claims: CurrentOfflineGrantClaims = {
    versao: 2,
    colaboradorId: OWNER_ID,
    nome: "Colaborador Sintético",
    papelAcesso: "BETA",
    escopoGlobal: false,
    obraIds: [WORKSITE_ID],
    emitidoEm: "2026-07-14T12:00:00Z",
    expiraEm: "2026-07-21T12:00:00Z",
    authEpoch: 7,
  };
  return { claims, grant: await signClaims(claims) };
}

async function signedLegacyGrantFixture(): Promise<SignedOfflineGrant> {
  return signClaims({
    versao: 1,
    colaboradorId: OWNER_ID,
    nome: "Colaborador Sintético",
    papelAcesso: "BETA",
    escopoGlobal: false,
    obraIds: [WORKSITE_ID],
    emitidoEm: "2026-07-14T12:00:00Z",
    expiraEm: "2026-07-15T12:00:00Z",
  });
}

async function signClaims(claims: object): Promise<SignedOfflineGrant> {
  const keyPair = await crypto.subtle.generateKey(
    {
      name: "RSASSA-PKCS1-v1_5",
      modulusLength: 2048,
      publicExponent: new Uint8Array([1, 0, 1]),
      hash: "SHA-256",
    },
    true,
    ["sign", "verify"],
  );
  const payload = new TextEncoder().encode(JSON.stringify(claims));
  const signature = await crypto.subtle.sign(
    "RSASSA-PKCS1-v1_5",
    keyPair.privateKey,
    payload,
  );
  const publicKeySpki = await crypto.subtle.exportKey("spki", keyPair.publicKey);
  return {
    keyId: "offline-test",
    payload: toBase64Url(payload),
    signature: toBase64Url(signature),
    publicKeySpki: toBase64Url(publicKeySpki),
  };
}
