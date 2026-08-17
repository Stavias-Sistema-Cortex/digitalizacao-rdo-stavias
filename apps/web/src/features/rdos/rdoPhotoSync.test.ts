import "fake-indexeddb/auto";

import { deleteDB } from "idb";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { clearSession, setSession } from "../auth/authSession";
import { closeCortexDb, getCortexDb } from "../../lib/db/cortexDb";
import type { LocalRdoRecord, RdoAttachmentRecord } from "../../lib/db/db.types";
import { databaseNameForScope } from "../../lib/db/localDataNamespace";

const mocks = vi.hoisted(() => ({ apiFetch: vi.fn(), body: vi.fn() }));
vi.mock("../../lib/api/apiClient", async (importOriginal) => ({
  ...(await importOriginal<typeof import("../../lib/api/apiClient")>()),
  apiFetch: mocks.apiFetch,
  readResponseBody: mocks.body,
}));

import {
  garantirArquivoDaFoto,
  processRdoPhotoUploads,
  semearFichasDeAnexoDoServidor,
} from "./rdoPhotoSync";

const OBRA_ID = "00000000-0000-4000-8000-000000000501";
const USER_ID = "00000000-0000-4000-8000-000000000502";
const RDO_ID = "00000000-0000-4000-8000-000000000503";
const ATTACHMENT_ID = "00000000-0000-4000-8000-000000000504";
const OBJECT_ID = "00000000-0000-4000-8000-000000000505";
const FOTO = new Blob(["foto de obra"], { type: "image/jpeg" });
let databaseName = "";
let fotoSha256 = "";

function hex(bytes: ArrayBuffer): string {
  return Array.from(new Uint8Array(bytes), (byte) =>
    byte.toString(16).padStart(2, "0"),
  ).join("");
}

function rdoNoServidor(overrides: Partial<LocalRdoRecord> = {}): LocalRdoRecord {
  return {
    id: RDO_ID,
    obraId: OBRA_ID,
    programacaoId: null,
    numeroRdo: "RDO-0033",
    dataRdo: "2026-08-13",
    statusRdo: "RASCUNHO",
    syncStatus: "SYNCED",
    versaoEntidade: 3,
    payload: {},
    createdAt: "2026-08-13T12:00:00.000Z",
    updatedAt: "2026-08-13T12:00:00.000Z",
    ...overrides,
  };
}

function fichaComFoto(
  overrides: Partial<RdoAttachmentRecord> = {},
): RdoAttachmentRecord {
  return {
    id: ATTACHMENT_ID,
    rdoId: RDO_ID,
    obraId: OBRA_ID,
    tipo: "FOTO",
    nome: "frente-de-servico.jpg",
    nomeOriginal: "frente-de-servico.jpg",
    mimeType: "image/jpeg",
    tamanhoBytes: FOTO.size,
    tamanhoOriginalBytes: FOTO.size,
    tamanhoComprimidoBytes: FOTO.size,
    arquivo: FOTO,
    syncStatus: "PENDING_SYNC",
    ultimoErro: null,
    metadata: {},
    createdAt: "2026-08-13T12:00:00.000Z",
    updatedAt: "2026-08-13T12:00:00.000Z",
    removedAt: null,
    ...overrides,
  };
}

beforeEach(async () => {
  vi.stubGlobal("window", new EventTarget());
  setSession({
    colaboradorId: USER_ID,
    nome: "A",
    papelAcesso: "BETA",
    escopoGlobal: false,
    obraIds: [OBRA_ID],
    expiraEm: new Date(Date.now() + 60_000).toISOString(),
  });
  databaseName = await databaseNameForScope(USER_ID, `BETA:${OBRA_ID}`);
  fotoSha256 = hex(await crypto.subtle.digest("SHA-256", await FOTO.arrayBuffer()));
});

afterEach(async () => {
  await closeCortexDb();
  await deleteDB(databaseName);
  clearSession();
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
  mocks.apiFetch.mockReset();
  mocks.body.mockReset();
});

describe("processRdoPhotoUploads", () => {
  it("sobe o binário, amarra ao anexo e grava a morada na ficha", async () => {
    const database = await getCortexDb();
    await database.put("rdos", rdoNoServidor());
    await database.put("rdo_attachments", fichaComFoto());

    mocks.apiFetch
      .mockResolvedValueOnce(new Response("{}", { status: 201 }))
      .mockResolvedValueOnce(new Response(null, { status: 204 }));
    mocks.body.mockResolvedValueOnce({ id: OBJECT_ID, sha256: fotoSha256 });

    await expect(processRdoPhotoUploads()).resolves.toEqual({
      enviadas: 1,
      falhas: 0,
    });

    const [uploadUrl, uploadInit] = mocks.apiFetch.mock.calls[0];
    expect(uploadUrl).toBe(`/objetos?obraId=${OBRA_ID}`);
    expect(uploadInit.method).toBe("POST");
    expect(uploadInit.body).toBeInstanceOf(FormData);

    const [bindUrl, bindInit] = mocks.apiFetch.mock.calls[1];
    expect(bindUrl).toBe(`/rdos/${RDO_ID}/anexos/${ATTACHMENT_ID}/objeto`);
    expect(bindInit.method).toBe("PUT");
    expect(JSON.parse(bindInit.body as string)).toEqual({
      objetoId: OBJECT_ID,
      sha256: fotoSha256,
    });

    expect(await database.get("rdo_attachments", ATTACHMENT_ID)).toMatchObject({
      storedObjectId: OBJECT_ID,
      sha256: fotoSha256,
      syncStatus: "SYNCED",
      ultimoErro: null,
    });
  });

  it("espera o RDO existir no servidor antes de gastar o upload", async () => {
    const database = await getCortexDb();
    await database.put("rdos", rdoNoServidor({ versaoEntidade: null }));
    await database.put("rdo_attachments", fichaComFoto());

    await expect(processRdoPhotoUploads()).resolves.toEqual({
      enviadas: 0,
      falhas: 0,
    });
    expect(mocks.apiFetch).not.toHaveBeenCalled();
    const ficha = await database.get("rdo_attachments", ATTACHMENT_ID);
    expect(ficha?.syncStatus).toBe("PENDING_SYNC");
    expect(ficha?.storedObjectId).toBeUndefined();
  });

  it("ignora ficha já amarrada, removida ou sem binário", async () => {
    const database = await getCortexDb();
    await database.put("rdos", rdoNoServidor());
    await database.put(
      "rdo_attachments",
      fichaComFoto({ id: "a".repeat(36), storedObjectId: OBJECT_ID }),
    );
    await database.put(
      "rdo_attachments",
      fichaComFoto({ id: "b".repeat(36), removedAt: "2026-08-14T08:00:00.000Z" }),
    );
    await database.put(
      "rdo_attachments",
      fichaComFoto({ id: "c".repeat(36), arquivo: null }),
    );

    await expect(processRdoPhotoUploads()).resolves.toEqual({
      enviadas: 0,
      falhas: 0,
    });
    expect(mocks.apiFetch).not.toHaveBeenCalled();
  });

  it("uma recusa 4xx tira a foto da fila e acende o erro na ficha", async () => {
    const database = await getCortexDb();
    await database.put("rdos", rdoNoServidor());
    await database.put("rdo_attachments", fichaComFoto());

    mocks.apiFetch.mockResolvedValueOnce(new Response("{}", { status: 422 }));
    mocks.body.mockResolvedValueOnce({ code: "UPLOAD_INVALID" });

    await expect(processRdoPhotoUploads()).resolves.toEqual({
      enviadas: 0,
      falhas: 1,
    });
    expect(await database.get("rdo_attachments", ATTACHMENT_ID)).toMatchObject({
      syncStatus: "SYNC_FAILED",
      ultimoErro: "Não foi possível concluir a solicitação ao Córtex.",
    });

    // Fora da fila: o próximo ciclo nem tenta de novo.
    mocks.apiFetch.mockClear();
    await expect(processRdoPhotoUploads()).resolves.toEqual({
      enviadas: 0,
      falhas: 0,
    });
    expect(mocks.apiFetch).not.toHaveBeenCalled();
  });

  it("um 404 no vínculo é o RDO ainda a caminho: a foto espera o próximo ciclo", async () => {
    const database = await getCortexDb();
    await database.put("rdos", rdoNoServidor());
    await database.put("rdo_attachments", fichaComFoto());

    mocks.apiFetch
      .mockResolvedValueOnce(new Response("{}", { status: 201 }))
      .mockResolvedValueOnce(new Response("{}", { status: 404 }));
    mocks.body
      .mockResolvedValueOnce({ id: OBJECT_ID, sha256: fotoSha256 })
      .mockResolvedValueOnce({ message: "Anexo não encontrado." });

    await expect(processRdoPhotoUploads()).resolves.toEqual({
      enviadas: 0,
      falhas: 1,
    });
    const ficha = await database.get("rdo_attachments", ATTACHMENT_ID);
    expect(ficha?.syncStatus).toBe("PENDING_SYNC");
    expect(ficha?.storedObjectId).toBeUndefined();
    expect(ficha?.ultimoErro).toBeNull();
  });

  it("falha de rede fica como está e o próximo ciclo tenta de novo", async () => {
    const { ApiTransportError } = await import("../../lib/api/apiClient");
    const database = await getCortexDb();
    await database.put("rdos", rdoNoServidor());
    await database.put("rdo_attachments", fichaComFoto());

    mocks.apiFetch.mockRejectedValueOnce(
      new ApiTransportError("rede indisponível", "CONNECTION"),
    );

    await expect(processRdoPhotoUploads()).resolves.toEqual({
      enviadas: 0,
      falhas: 1,
    });
    expect(await database.get("rdo_attachments", ATTACHMENT_ID)).toMatchObject({
      syncStatus: "PENDING_SYNC",
      ultimoErro: null,
    });
  });

  it("rejeita um objeto que não corresponde ao hash da foto enviada", async () => {
    const database = await getCortexDb();
    await database.put("rdos", rdoNoServidor());
    await database.put("rdo_attachments", fichaComFoto());

    mocks.apiFetch.mockResolvedValueOnce(new Response("{}", { status: 201 }));
    mocks.body.mockResolvedValueOnce({ id: OBJECT_ID, sha256: "f".repeat(64) });

    await expect(processRdoPhotoUploads()).resolves.toEqual({
      enviadas: 0,
      falhas: 1,
    });
    // Sem vínculo: o PUT nunca aconteceu.
    expect(mocks.apiFetch).toHaveBeenCalledTimes(1);
    expect(
      (await database.get("rdo_attachments", ATTACHMENT_ID))?.storedObjectId,
    ).toBeUndefined();
  });
});

describe("semearFichasDeAnexoDoServidor", () => {
  it("cria a ficha sem binário para a foto que outro aparelho subiu", async () => {
    const database = await getCortexDb();

    await expect(
      semearFichasDeAnexoDoServidor(RDO_ID, [
        {
          id: ATTACHMENT_ID,
          rdoId: RDO_ID,
          obraId: OBRA_ID,
          tipo: "FOTO",
          nome: "frente-de-servico.jpg",
          nomeOriginal: "frente-de-servico.jpg",
          mimeType: "image/jpeg",
          tamanhoBytes: 12,
          tamanhoOriginalBytes: 12,
          tamanhoComprimidoBytes: 12,
          storedObjectId: OBJECT_ID,
          createdAt: "2026-08-13T12:00:00.000Z",
          updatedAt: "2026-08-13T12:00:00.000Z",
          removedAt: null,
          metadata: { largura: 1600 },
        },
      ]),
    ).resolves.toBe(1);

    expect(await database.get("rdo_attachments", ATTACHMENT_ID)).toMatchObject({
      rdoId: RDO_ID,
      arquivo: null,
      storedObjectId: OBJECT_ID,
      syncStatus: "SYNCED",
      nome: "frente-de-servico.jpg",
      metadata: { largura: 1600 },
    });
  });

  it("adota o vínculo confirmado sem destruir o binário local", async () => {
    const database = await getCortexDb();
    await database.put("rdo_attachments", fichaComFoto());

    await expect(
      semearFichasDeAnexoDoServidor(RDO_ID, [
        { id: ATTACHMENT_ID, storedObjectId: OBJECT_ID },
      ]),
    ).resolves.toBe(1);

    const ficha = await database.get("rdo_attachments", ATTACHMENT_ID);
    expect(ficha?.arquivo).toBeInstanceOf(Blob);
    expect(ficha).toMatchObject({
      storedObjectId: OBJECT_ID,
      syncStatus: "SYNCED",
    });
  });

  it("não reescreve a ficha que já diz o que o servidor diz", async () => {
    const database = await getCortexDb();
    const ficha = fichaComFoto({
      storedObjectId: OBJECT_ID,
      syncStatus: "SYNCED",
    });
    await database.put("rdo_attachments", ficha);

    await expect(
      semearFichasDeAnexoDoServidor(RDO_ID, [
        { id: ATTACHMENT_ID, storedObjectId: OBJECT_ID },
      ]),
    ).resolves.toBe(0);

    expect(await database.get("rdo_attachments", ATTACHMENT_ID)).toMatchObject({
      updatedAt: ficha.updatedAt,
    });
  });

  it("ignora payload que não é lista ou item sem id", async () => {
    await expect(semearFichasDeAnexoDoServidor(RDO_ID, null)).resolves.toBe(0);
    await expect(semearFichasDeAnexoDoServidor(RDO_ID, "x")).resolves.toBe(0);
    await expect(
      semearFichasDeAnexoDoServidor(RDO_ID, [{ nome: "sem-id.jpg" }]),
    ).resolves.toBe(0);
  });
});

describe("garantirArquivoDaFoto", () => {
  it("devolve o binário local sem tocar a rede", async () => {
    await expect(garantirArquivoDaFoto(fichaComFoto())).resolves.toBe(FOTO);
    expect(mocks.apiFetch).not.toHaveBeenCalled();
  });

  it("baixa do servidor e grava de volta na ficha: a segunda abertura é offline", async () => {
    const database = await getCortexDb();
    const ficha = fichaComFoto({ arquivo: null, storedObjectId: OBJECT_ID });
    await database.put("rdo_attachments", ficha);

    mocks.apiFetch.mockResolvedValueOnce(
      new Response(new Uint8Array([1, 2, 3]), {
        status: 200,
        headers: { "Content-Type": "image/jpeg" },
      }),
    );

    const blob = await garantirArquivoDaFoto(ficha);
    expect(blob).toBeInstanceOf(Blob);
    expect(blob?.size).toBe(3);
    expect(mocks.apiFetch.mock.calls[0][0]).toBe(
      `/rdos/${RDO_ID}/anexos/${ATTACHMENT_ID}/conteudo`,
    );

    const gravada = await database.get("rdo_attachments", ATTACHMENT_ID);
    expect(gravada?.arquivo).toBeInstanceOf(Blob);
    expect(gravada?.arquivo?.size).toBe(3);
  });

  it("sem vínculo não há o que baixar", async () => {
    await expect(
      garantirArquivoDaFoto(fichaComFoto({ arquivo: null })),
    ).resolves.toBeNull();
    expect(mocks.apiFetch).not.toHaveBeenCalled();
  });

  it("offline devolve null sem tentar a rede", async () => {
    vi.stubGlobal("navigator", { onLine: false });
    await expect(
      garantirArquivoDaFoto(
        fichaComFoto({ arquivo: null, storedObjectId: OBJECT_ID }),
      ),
    ).resolves.toBeNull();
    expect(mocks.apiFetch).not.toHaveBeenCalled();
  });

  it("falha do servidor devolve null e não toca a ficha", async () => {
    const database = await getCortexDb();
    const ficha = fichaComFoto({ arquivo: null, storedObjectId: OBJECT_ID });
    await database.put("rdo_attachments", ficha);

    mocks.apiFetch.mockResolvedValueOnce(new Response("{}", { status: 500 }));

    await expect(garantirArquivoDaFoto(ficha)).resolves.toBeNull();
    expect(
      (await database.get("rdo_attachments", ATTACHMENT_ID))?.arquivo,
    ).toBeNull();
  });
});
