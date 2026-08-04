import "fake-indexeddb/auto";

import { deleteDB } from "idb";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import type { ObraMapFeature } from "./mapGeometry";

const OWNER = "10000000-0000-4000-8000-000000000001";
const OBRA = "obra-1";

vi.mock("../../auth/authSession", () => ({
  getSession: () => ({
    colaboradorId: OWNER,
    papelAcesso: "ALFA",
    escopoGlobal: true,
    obraIds: [] as string[],
    expiraEm: "2099-01-01T00:00:00.000Z",
  }),
  requireDataScope: () => ({ ownerId: OWNER, scopeMaterial: "ALFA:GLOBAL" }),
}));

const {
  listarGeometriasLocais,
  reconciliarGeometriasDoServidor,
} = await import("./obraGeoCacheRepository");
const { getCortexDb } = await import("../../../lib/db/cortexDb");

function pontoDoServidor(id: string): ObraMapFeature {
  return {
    id,
    categoria: "PONTO_OPERACIONAL",
    objetoTipo: "OBRA",
    objetoId: OBRA,
    geometry: { type: "Point", coordinates: [-47.56, -22.43] },
    properties: { observadoEm: "2026-08-04T12:00:00.000Z" },
    fonte: "CAPTURA_CAMPO",
    versao: 3,
    validoDesde: "2026-08-04T12:00:00.000Z",
    validoAte: null,
  };
}

/** O estado que `encerrarGeometria` deixa no dispositivo antes de subir. */
async function encerrarLocalmente(id: string) {
  const database = await getCortexDb();
  const atual = await database.get("obra_geometrias", id);
  await database.put("obra_geometrias", {
    ...atual!,
    status: "ENCERRADA",
    validoAte: "2026-08-04T13:00:00.000Z",
    syncStatus: "PENDING_SYNC",
    updatedAt: "2026-08-04T13:00:00.000Z",
  });
}

beforeEach(async () => {
  await reconciliarGeometriasDoServidor(
    OBRA,
    [pontoDoServidor("ponto-1"), pontoDoServidor("ponto-2")],
    "2026-08-04T12:30:00.000Z",
  );
});

afterEach(async () => {
  await deleteDB("cortex");
  vi.resetModules();
});

/**
 * Um ponto encerrado não pode voltar ao mapa.
 *
 * O encerramento é escrito no dispositivo e sobe na próxima janela de
 * sincronização. Enquanto ele não sobe, o servidor continua respondendo o
 * ponto como ativo — é a mesma verdade de sempre, só que velha. Se a
 * reconciliação deixar essa resposta sobrescrever o encerramento local, o
 * ponto reaparece assim que a tela recarrega, e quem apagou vê a marcação
 * errada de volta sem nenhum aviso de que algo falhou.
 */
describe("ponto encerrado no mapa", () => {
  it("some da leitura local assim que é encerrado", async () => {
    await encerrarLocalmente("ponto-1");

    const restantes = await listarGeometriasLocais(OBRA);

    expect(restantes.map((registro) => registro.id)).toEqual(["ponto-2"]);
  });

  it("não volta quando o servidor ainda responde o ponto como ativo", async () => {
    await encerrarLocalmente("ponto-1");

    // A tela recarrega antes de a fila subir: é o caso comum, porque
    // recarregar é a primeira coisa que acontece depois de encerrar.
    await reconciliarGeometriasDoServidor(
      OBRA,
      [pontoDoServidor("ponto-1"), pontoDoServidor("ponto-2")],
      "2026-08-04T13:05:00.000Z",
    );

    const restantes = await listarGeometriasLocais(OBRA);

    expect(restantes.map((registro) => registro.id)).toEqual(["ponto-2"]);
  });

  /**
   * Depois que o encerramento sobe, o servidor para de responder o ponto e o
   * registro local sai de vez — não fica um encerrado acumulando no aparelho.
   */
  it("sai do dispositivo quando o servidor confirma o encerramento", async () => {
    const database = await getCortexDb();
    await database.put("obra_geometrias", {
      ...(await database.get("obra_geometrias", "ponto-1"))!,
      status: "ENCERRADA",
      syncStatus: "SYNCED",
      updatedAt: "2026-08-04T13:10:00.000Z",
    });

    await reconciliarGeometriasDoServidor(
      OBRA,
      [pontoDoServidor("ponto-2")],
      "2026-08-04T13:15:00.000Z",
    );

    expect(await database.get("obra_geometrias", "ponto-1")).toBeUndefined();
  });
});
