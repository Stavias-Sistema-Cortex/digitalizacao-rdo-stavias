// @vitest-environment jsdom
import { beforeEach, describe, expect, it, vi } from "vitest";

const OWNER_ID = "10000000-0000-4000-8000-000000000001";
const AGORA = Date.parse("2026-08-04T12:00:00.000Z");

const mocks = vi.hoisted(() => ({
  fetchOfflineGrant: vi.fn(),
  hasLivePasswordVaultKey: vi.fn(),
  listCollaborativeOfflineMetadata: vi.fn(),
  readLivePasswordVaultClaims: vi.fn(),
  replaceLegacyGrantAfterV3Save: vi.fn(),
  resealPasswordOfflineVault: vi.fn(),
  verifySignedOfflineGrant: vi.fn(),
  online: true,
}));

vi.mock("./authApi", () => ({
  fetchOfflineGrant: mocks.fetchOfflineGrant,
}));
vi.mock("./authSession", () => ({
  getSession: () => ({ colaboradorId: OWNER_ID }),
  hasOnlineSession: () => mocks.online,
}));
vi.mock("./offlineVault", () => ({
  verifySignedOfflineGrant: mocks.verifySignedOfflineGrant,
}));
vi.mock("./offlineVaultRepository", () => ({
  listCollaborativeOfflineMetadata: mocks.listCollaborativeOfflineMetadata,
  replaceLegacyGrantAfterV3Save: mocks.replaceLegacyGrantAfterV3Save,
}));
vi.mock("./passwordOfflineVault", () => ({
  hasLivePasswordVaultKey: mocks.hasLivePasswordVaultKey,
  isOfflinePasswordVaultMetadata: (value: { versao?: number }) =>
    value.versao === 3,
  readLivePasswordVaultClaims: mocks.readLivePasswordVaultClaims,
  resealPasswordOfflineVault: mocks.resealPasswordOfflineVault,
}));

const { precisaRenovar, renovarGrantOfflineSePreciso } = await import(
  "./renovacaoDoGrantOffline"
);

function passwordVault() {
  return {
    key: "20000000-0000-4000-8000-000000000001",
    versao: 3 as const,
    cpfSalt: "s".repeat(22),
    cpfVerifier: "v".repeat(43),
    passwordSalt: "p".repeat(22),
    kdf: "PBKDF2-SHA256" as const,
    kdfIterations: 600_000 as const,
    iv: "i".repeat(16),
    ciphertext: "c".repeat(64),
    serverKeyFingerprint: "f".repeat(43),
    atualizadoEm: "2026-08-03T12:00:00.000Z",
    failedAttemptState: {
      windowStartedAt: null,
      failures: 0,
      blockedUntil: null,
    },
  };
}

function claims(emitidoEm: string, expiraEm: string) {
  return {
    versao: 2 as const,
    colaboradorId: OWNER_ID,
    nome: "Pessoa Sintética",
    papelAcesso: "ALFA" as const,
    escopoGlobal: true,
    obraIds: [],
    emitidoEm,
    expiraEm,
    authEpoch: 7,
  };
}

beforeEach(() => {
  vi.clearAllMocks();
  mocks.online = true;
  mocks.listCollaborativeOfflineMetadata.mockResolvedValue([passwordVault()]);
  mocks.hasLivePasswordVaultKey.mockReturnValue(true);
  mocks.readLivePasswordVaultClaims.mockResolvedValue(claims(
    "2026-07-29T12:00:00.000Z",
    "2026-08-05T12:00:00.000Z",
  ));
  mocks.fetchOfflineGrant.mockResolvedValue({
    keyId: "k2",
    payload: "p2",
    signature: "s2",
    publicKeySpki: "x2",
  });
  mocks.verifySignedOfflineGrant.mockResolvedValue({
    claims: claims(
      "2026-08-04T12:00:00.000Z",
      "2026-08-11T12:00:00.000Z",
    ),
    fingerprint: "novo",
  });
  mocks.resealPasswordOfflineVault.mockResolvedValue({
    ...passwordVault(),
    iv: "j".repeat(16),
    atualizadoEm: new Date(AGORA).toISOString(),
  });
});

describe("renovação do cofre offline v3", () => {
  it("reseals an expiring v3 only when its key is live", async () => {
    expect(await renovarGrantOfflineSePreciso(AGORA)).toBe("RENOVADO");
    expect(mocks.fetchOfflineGrant).toHaveBeenCalledTimes(1);
    expect(mocks.resealPasswordOfflineVault).toHaveBeenCalledWith(
      expect.objectContaining({ versao: 3 }),
      expect.objectContaining({ keyId: "k2" }),
      OWNER_ID,
      expect.any(Function),
    );
    expect(mocks.replaceLegacyGrantAfterV3Save).toHaveBeenCalledWith(
      null,
      expect.objectContaining({ iv: "j".repeat(16) }),
    );
  });

  it("requires password after reload before any network mutation", async () => {
    mocks.hasLivePasswordVaultKey.mockReturnValue(false);

    expect(await renovarGrantOfflineSePreciso(AGORA))
      .toBe("SENHA_NECESSARIA");
    expect(mocks.fetchOfflineGrant).not.toHaveBeenCalled();
    expect(mocks.resealPasswordOfflineVault).not.toHaveBeenCalled();
  });

  it("ignores legacy v2 records and never extends their clear grant", async () => {
    mocks.listCollaborativeOfflineMetadata.mockResolvedValue([
      { versao: 2, key: "legacy" },
    ]);

    expect(await renovarGrantOfflineSePreciso(AGORA))
      .toBe("NAO_APLICAVEL");
    expect(mocks.fetchOfflineGrant).not.toHaveBeenCalled();
  });

  it("does not fetch while the live signed grant still has two thirds left", async () => {
    const current = claims(
      "2026-08-04T11:00:00.000Z",
      "2026-08-11T11:00:00.000Z",
    );
    mocks.readLivePasswordVaultClaims.mockResolvedValue(current);

    expect(await precisaRenovar(passwordVault(), AGORA)).toBe(false);
    expect(await renovarGrantOfflineSePreciso(AGORA)).toBe("AINDA_VALIDO");
    expect(mocks.fetchOfflineGrant).not.toHaveBeenCalled();
  });

  it("keeps the existing vault when the network cannot renew it", async () => {
    mocks.fetchOfflineGrant.mockRejectedValue(new Error("sem rede"));

    expect(await renovarGrantOfflineSePreciso(AGORA)).toBe("SEM_REDE");
    expect(mocks.replaceLegacyGrantAfterV3Save).not.toHaveBeenCalled();
  });

  it("does not renew from an offline-only session", async () => {
    mocks.online = false;

    expect(await renovarGrantOfflineSePreciso(AGORA))
      .toBe("NAO_APLICAVEL");
    expect(mocks.fetchOfflineGrant).not.toHaveBeenCalled();
  });

  it("rejects a newly signed grant for another owner", async () => {
    mocks.verifySignedOfflineGrant.mockResolvedValue({
      claims: {
        ...claims(
          "2026-08-04T12:00:00.000Z",
          "2026-08-11T12:00:00.000Z",
        ),
        colaboradorId: "10000000-0000-4000-8000-000000000002",
      },
      fingerprint: "novo",
    });

    expect(await renovarGrantOfflineSePreciso(AGORA))
      .toBe("NAO_APLICAVEL");
    expect(mocks.resealPasswordOfflineVault).not.toHaveBeenCalled();
  });
});
