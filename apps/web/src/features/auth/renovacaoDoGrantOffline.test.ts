// @vitest-environment jsdom
import { beforeEach, describe, expect, it, vi } from "vitest";

const DONO = "10000000-0000-4000-8000-000000000001";
const AGORA = Date.parse("2026-08-04T12:00:00.000Z");

const fetchOfflineGrant = vi.hoisted(() => vi.fn());
const verifySignedOfflineGrant = vi.hoisted(() => vi.fn());
const listCollaborativeOfflineGrantsForOwner = vi.hoisted(() => vi.fn());
const saveCollaborativeOfflineGrantMetadata = vi.hoisted(() => vi.fn());
const estado = vi.hoisted(() => ({ online: true }));

vi.mock("./authApi", () => ({ fetchOfflineGrant }));
vi.mock("./authSession", () => ({
  getSession: () => ({ colaboradorId: DONO }),
  hasOnlineSession: () => estado.online,
}));
vi.mock("./offlineVault", () => ({
  verifySignedOfflineGrant,
  offlineGrantScopeMaterial: () => "ALFA:GLOBAL",
}));
vi.mock("../../lib/db/localDataNamespace", () => ({
  scopeFingerprint: () => Promise.resolve("f".repeat(64)),
}));
vi.mock("./offlineVaultRepository", () => ({
  listCollaborativeOfflineGrantsForOwner,
  saveCollaborativeOfflineGrantMetadata,
}));

const { precisaRenovar, renovarGrantOfflineSePreciso } = await import(
  "./renovacaoDoGrantOffline"
);

function guardado(cpfHash = "a".repeat(64)) {
  return {
    key: cpfHash,
    versao: 1 as const,
    cpfHash,
    ownerId: DONO,
    scopeFingerprint: "0".repeat(64),
    signedGrant: { keyId: "k", payload: "p", signature: "s", publicKeySpki: "x" },
    serverKeyFingerprint: "antigo",
    atualizadoEm: "2026-08-03T12:00:00.000Z",
  };
}

function claims(emitidoEm: string, expiraEm: string) {
  return {
    claims: { colaboradorId: DONO, emitidoEm, expiraEm },
    fingerprint: "novo",
  };
}

beforeEach(() => {
  fetchOfflineGrant.mockReset();
  verifySignedOfflineGrant.mockReset();
  listCollaborativeOfflineGrantsForOwner.mockReset();
  saveCollaborativeOfflineGrantMetadata.mockReset();
  estado.online = true;
  listCollaborativeOfflineGrantsForOwner.mockResolvedValue([guardado()]);
  fetchOfflineGrant.mockResolvedValue({
    keyId: "k2", payload: "p2", signature: "s2", publicKeySpki: "x2",
  });
});

/**
 * O grant nascia no login e morria em 24 horas sem nunca ser reemitido.
 * Passado o prazo, o aparelho parava de abrir até os dados que já estavam
 * nele — numa tela que existe justamente para funcionar quando não há
 * servidor a quem pedir outro.
 */
describe("renovação do grant offline", () => {
  it("troca o grant quando falta pouco para vencer", async () => {
    verifySignedOfflineGrant.mockResolvedValue(
      claims("2026-08-03T12:00:00.000Z", "2026-08-04T12:00:00.000Z"),
    );

    expect(await renovarGrantOfflineSePreciso(AGORA)).toBe("RENOVADO");
    expect(saveCollaborativeOfflineGrantMetadata).toHaveBeenCalledWith(
      expect.objectContaining({
        cpfHash: "a".repeat(64),
        signedGrant: expect.objectContaining({ keyId: "k2" }),
      }),
    );
  });

  /** O CPF em claro não é guardado; a chave do registro não pode mudar. */
  it("preserva a chave do registro, que é o resumo do CPF", async () => {
    verifySignedOfflineGrant.mockResolvedValue(
      claims("2026-08-03T12:00:00.000Z", "2026-08-04T12:00:00.000Z"),
    );

    await renovarGrantOfflineSePreciso(AGORA);

    const gravado = saveCollaborativeOfflineGrantMetadata.mock.calls.at(-1)?.[0];
    expect(gravado.key).toBe("a".repeat(64));
    expect(gravado.key).toBe(gravado.cpfHash);
  });

  it("não pede nada quando ainda há validade de sobra", async () => {
    verifySignedOfflineGrant.mockResolvedValue(
      claims("2026-08-04T11:00:00.000Z", "2026-08-05T11:00:00.000Z"),
    );

    expect(await renovarGrantOfflineSePreciso(AGORA)).toBe("AINDA_VALIDO");
    expect(fetchOfflineGrant).not.toHaveBeenCalled();
  });

  /** Um grant já vencido é exatamente o estado que trancava o aparelho. */
  it("trata o grant ilegível ou vencido como precisando de troca", async () => {
    verifySignedOfflineGrant.mockRejectedValue(new Error("expirou"));

    expect(await precisaRenovar(guardado(), AGORA)).toBe(true);
  });

  it("mantém o grant guardado quando a rede não responde", async () => {
    verifySignedOfflineGrant.mockResolvedValue(
      claims("2026-08-03T12:00:00.000Z", "2026-08-04T12:00:00.000Z"),
    );
    fetchOfflineGrant.mockRejectedValue(new Error("sem rede"));

    expect(await renovarGrantOfflineSePreciso(AGORA)).toBe("SEM_REDE");
    expect(saveCollaborativeOfflineGrantMetadata).not.toHaveBeenCalled();
  });

  /** Sem sessão online não há a quem pedir, e não se toca no que existe. */
  it("não tenta renovar a partir de uma sessão offline", async () => {
    estado.online = false;

    expect(await renovarGrantOfflineSePreciso(AGORA)).toBe("NAO_APLICAVEL");
    expect(fetchOfflineGrant).not.toHaveBeenCalled();
  });

  /** Grant de outra identidade não entra no lugar do desta pessoa. */
  it("descarta um grant que volta com outro dono", async () => {
    verifySignedOfflineGrant
      .mockResolvedValueOnce(
        claims("2026-08-03T12:00:00.000Z", "2026-08-04T12:00:00.000Z"),
      )
      .mockResolvedValueOnce({
        claims: {
          colaboradorId: "10000000-0000-4000-8000-000000000002",
          emitidoEm: "2026-08-04T12:00:00.000Z",
          expiraEm: "2026-08-05T12:00:00.000Z",
        },
        fingerprint: "novo",
      });

    expect(await renovarGrantOfflineSePreciso(AGORA)).toBe("NAO_APLICAVEL");
    expect(saveCollaborativeOfflineGrantMetadata).not.toHaveBeenCalled();
  });
});
