import { beforeEach, describe, expect, it, vi } from "vitest";

const mocks = vi.hoisted(() => ({
  apiFetch: vi.fn(),
  createOfflineVault: vi.fn(),
  renewOfflineVault: vi.fn(),
  readResponseBody: vi.fn(),
  saveOfflineVaultMetadata: vi.fn(),
  setSession: vi.fn(),
}));

vi.mock("../../lib/api/apiClient", () => ({
  apiFetch: mocks.apiFetch,
  readResponseBody: mocks.readResponseBody,
  responseErrorMessage: vi.fn(() => "Falha sintética"),
}));
vi.mock("./authSession", () => ({ setSession: mocks.setSession }));
vi.mock("./offlineVault", () => ({
  createOfflineVault: mocks.createOfflineVault,
  renewOfflineVault: mocks.renewOfflineVault,
}));
vi.mock("./offlineVaultRepository", () => ({
  saveOfflineVaultMetadata: mocks.saveOfflineVaultMetadata,
}));

import {
  authenticateWithPasskey,
  registerPasskey,
  renewOfflineAccess,
} from "./passkeyApi";
import { toBase64Url } from "./webauthnCodec";

const challenge = new Uint8Array([1, 2, 3, 4]);
const userId = new Uint8Array([5, 6, 7, 8]);
const credentialId = new Uint8Array([9, 10, 11, 12]);
const prfSalt = crypto.getRandomValues(new Uint8Array(32));
const prfOutput = crypto.getRandomValues(new Uint8Array(32));

describe("passkeyApi", () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it("registra passkey, preserva PRF só em memória e sela o cofre", async () => {
    const create = vi.fn().mockResolvedValue(
      registrationCredential(prfOutput),
    );
    vi.stubGlobal("navigator", { credentials: { create } });
    mockResponses(
      registrationOptions(),
      {
        credentialId: toBase64Url(credentialId),
        name: "Passkey deste dispositivo",
        createdAt: "2026-07-14T12:00:00Z",
        prfSupported: true,
      },
      signedGrant(),
    );
    const metadata = {
      key: "active",
      versao: 1,
      credentialId: toBase64Url(credentialId),
    };
    mocks.createOfflineVault.mockResolvedValue(metadata);

    await expect(registerPasskey()).resolves.toMatchObject({
      offlineVault: "READY",
      summary: { prfSupported: true },
    });

    const createOptions = create.mock.calls[0][0] as CredentialCreationOptions;
    expect(
      new Uint8Array(createOptions.publicKey!.challenge),
    ).toEqual(challenge);
    expect(
      new Uint8Array(
        (createOptions.publicKey!.extensions as unknown as {
          prf: { eval: { first: ArrayBuffer } };
        }).prf.eval.first,
      ),
    ).toEqual(prfSalt);
    expect(mocks.createOfflineVault).toHaveBeenCalledWith(
      expect.objectContaining({
        credentialId: toBase64Url(credentialId),
        rpId: "cortex.example.invalid",
        prfSalt: toBase64Url(prfSalt),
      }),
    );
    expect(mocks.saveOfflineVaultMetadata).toHaveBeenCalledWith(metadata);
    expect(mocks.apiFetch.mock.calls[2][0]).toBe("/auth/offline-grant");
    expect(JSON.stringify(mocks.apiFetch.mock.calls)).not.toContain(
      toBase64Url(prfOutput),
    );
  });

  it("mantém passkey online quando PRF não existe e não cria fallback", async () => {
    const create = vi.fn().mockResolvedValue(
      registrationCredential(null),
    );
    vi.stubGlobal("navigator", { credentials: { create } });
    mockResponses(registrationOptions(), {
      credentialId: toBase64Url(credentialId),
      name: "Passkey online",
      createdAt: "2026-07-14T12:00:00Z",
      prfSupported: false,
    });

    await expect(registerPasskey()).resolves.toMatchObject({
      offlineVault: "PRF_UNAVAILABLE",
    });
    expect(mocks.createOfflineVault).not.toHaveBeenCalled();
    expect(mocks.saveOfflineVaultMetadata).not.toHaveBeenCalled();
    expect(mocks.apiFetch).toHaveBeenCalledTimes(2);
  });

  it("autentica com assertion discoverable e mantém perfil só em memória", async () => {
    const get = vi.fn().mockResolvedValue(assertionCredential());
    vi.stubGlobal("navigator", { credentials: { get } });
    const profile = {
      colaboradorId: "00000000-0000-4000-8000-000000000001",
      nome: "Colaborador Sintético",
      papelAcesso: "ALFA",
      escopoGlobal: true,
      obraIds: [],
      expiraEm: "2099-07-14T20:00:00Z",
    };
    mockResponses(authenticationOptions(), profile);

    await expect(authenticateWithPasskey()).resolves.toEqual(profile);
    expect(mocks.setSession).toHaveBeenCalledWith(profile);
    expect(mocks.apiFetch.mock.calls[0][0]).toBe(
      "/auth/passkeys/authentication/options",
    );
    expect(mocks.apiFetch.mock.calls[1][0]).toBe(
      "/auth/passkeys/authentication/verify",
    );
  });

  it("renova o grant e só substitui o cofre após concluir", async () => {
    const previous = { key: "owner:credential", versao: 1 };
    const renewed = { ...previous, atualizadoEm: "2099-07-14T20:00:00Z" };
    mocks.renewOfflineVault.mockImplementation(
      async (_metadata: unknown, requestGrant: () => Promise<unknown>) => {
        await requestGrant();
        return renewed;
      },
    );
    mockResponses(signedGrant());

    await expect(
      renewOfflineAccess(previous as never),
    ).resolves.toBe("READY");

    expect(mocks.apiFetch).toHaveBeenCalledWith(
      "/auth/offline-grant",
      expect.objectContaining({ method: "POST" }),
    );
    expect(mocks.saveOfflineVaultMetadata).toHaveBeenCalledWith(renewed);
  });
});

function mockResponses(...bodies: unknown[]): void {
  for (const body of bodies) {
    const response = { ok: true, status: 200 } as Response;
    mocks.apiFetch.mockResolvedValueOnce(response);
    mocks.readResponseBody.mockResolvedValueOnce(body);
  }
}

function registrationOptions() {
  return {
    challengeId: "00000000-0000-4000-8000-000000000003",
    rpId: "cortex.example.invalid",
    publicKey: {
      challenge: toBase64Url(challenge),
      rp: { id: "cortex.example.invalid", name: "Córtex" },
      user: {
        id: toBase64Url(userId),
        name: "00000000-0000-4000-8000-000000000001",
        displayName: "Colaborador Sintético",
      },
      pubKeyCredParams: [{ type: "public-key", alg: -7 }],
      authenticatorSelection: {
        residentKey: "required",
        userVerification: "required",
      },
      attestation: "none",
      extensions: {
        prf: { eval: { first: toBase64Url(prfSalt) } },
      },
    },
  };
}

function authenticationOptions() {
  return {
    challengeId: "00000000-0000-4000-8000-000000000004",
    rpId: "cortex.example.invalid",
    publicKey: {
      challenge: toBase64Url(challenge),
      rpId: "cortex.example.invalid",
      userVerification: "required",
    },
  };
}

function registrationCredential(prf: Uint8Array | null) {
  return {
    id: toBase64Url(credentialId),
    rawId: credentialId.buffer,
    type: "public-key",
    authenticatorAttachment: "platform",
    response: {
      clientDataJSON: new Uint8Array([20, 21]).buffer,
      attestationObject: new Uint8Array([22, 23]).buffer,
      getTransports: () => ["internal"],
    },
    getClientExtensionResults: () =>
      prf
        ? { prf: { enabled: true, results: { first: prf.buffer } } }
        : { prf: { enabled: false } },
  } as unknown as PublicKeyCredential;
}

function assertionCredential() {
  return {
    id: toBase64Url(credentialId),
    rawId: credentialId.buffer,
    type: "public-key",
    authenticatorAttachment: "platform",
    response: {
      clientDataJSON: new Uint8Array([30, 31]).buffer,
      authenticatorData: new Uint8Array([32, 33]).buffer,
      signature: new Uint8Array([34, 35]).buffer,
      userHandle: userId.buffer,
    },
    getClientExtensionResults: () => ({}),
  } as unknown as PublicKeyCredential;
}

function signedGrant() {
  return {
    keyId: "offline-test-v1",
    payload: "cGF5bG9hZA",
    signature: "c2lnbmF0dXJl",
    publicKeySpki: "cHVibGljLWtleQ",
  };
}
