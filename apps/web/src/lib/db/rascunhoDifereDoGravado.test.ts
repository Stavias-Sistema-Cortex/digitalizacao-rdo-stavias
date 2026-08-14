import "fake-indexeddb/auto";

import { deleteDB } from "idb";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import {
  clearSession,
  setSession,
} from "../../features/auth/authSession";
import {
  createEmptyMaoObra,
  createEmptyRdo,
} from "../../features/rdos/createEmptyRdo";
import { closeCortexDb } from "./cortexDb";
import { databaseNameForScope } from "./localDataNamespace";
import {
  rascunhoDifereDoQueEstaGravado,
  saveExistingRdoDraftAtomically,
} from "./localRdoService";
import { getCortexDb } from "./cortexDb";
import type { LocalRdoRecord } from "./db.types";

const OBRA_ID = "00000000-0000-4000-8000-000000000b01";
const RDO_ID = "00000000-0000-4000-8000-000000000b02";
const OCORRIDO_EM = "2026-07-26T09:00:00.000Z";

let userId = "";
let databaseName = "";

function session() {
  return {
    colaboradorId: userId,
    nome: "Encarregado",
    papelAcesso: "BETA" as const,
    escopoGlobal: false,
    obraIds: [OBRA_ID],
    expiraEm: new Date(Date.now() + 60_000).toISOString(),
  };
}

function rdoSincronizado(): LocalRdoRecord {
  return {
    id: RDO_ID,
    obraId: OBRA_ID,
    programacaoId: null,
    numeroRdo: "RDO-0B02",
    dataRdo: "2026-07-26",
    statusRdo: "RASCUNHO",
    syncStatus: "SYNCED",
    versaoEntidade: 3,
    payload: {},
    createdAt: OCORRIDO_EM,
    updatedAt: OCORRIDO_EM,
  };
}

function rascunho() {
  const draft = createEmptyRdo();
  draft.id = RDO_ID;
  draft.obraId = OBRA_ID;
  draft.numeroRdo = "RDO-0B02";
  draft.dataRdo = "2026-07-26";
  draft.observacoes = "estado gravado";
  return draft;
}

/** Grava o rascunho e devolve o mesmo objeto, para partir do que está no banco. */
async function gravar(draft: ReturnType<typeof rascunho>) {
  await saveExistingRdoDraftAtomically(draft);
  return draft;
}

beforeEach(async () => {
  vi.stubGlobal("window", new EventTarget());
  userId = crypto.randomUUID();
  setSession(session());
  databaseName = await databaseNameForScope(userId, `BETA:${OBRA_ID}`);
  const database = await getCortexDb();
  await database.put("rdos", rdoSincronizado());
});

afterEach(async () => {
  await closeCortexDb();
  if (databaseName) await deleteDB(databaseName);
  clearSession();
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

/**
 * A pergunta que o botão de sincronizar faz antes de gravar.
 *
 * <p>Ela existe para evitar as duas metades ruins: enviar sem gravar deixa a
 * correção na tela para trás, e gravar sempre enfileira uma edição vazia que o
 * servidor aplica, que consome versão da entidade e que aparece na Memória como
 * se alguém tivesse mexido no RDO.
 */
describe("o rascunho difere do que está gravado?", () => {
  it("diz que não quando nada foi tocado desde a gravação", async () => {
    const draft = await gravar(rascunho());

    expect(await rascunhoDifereDoQueEstaGravado(draft)).toBe(false);
  });

  it("enxerga uma mudança de campo simples", async () => {
    const draft = await gravar(rascunho());
    draft.observacoes = "medição refeita depois de gravar";

    expect(await rascunhoDifereDoQueEstaGravado(draft)).toBe(true);
  });

  /*
   * O apontamento mexe muito mais nas coleções do que nos campos soltos. Uma
   * verificação que só olhasse a cabeça do RDO diria "nada mudou" para um dia
   * inteiro de mão de obra lançada.
   */
  it("enxerga alguém somado à mão de obra", async () => {
    const draft = await gravar(rascunho());
    draft.maoObra = [
      ...draft.maoObra,
      { ...createEmptyMaoObra(), nome: "quem chegou depois" },
    ];

    expect(await rascunhoDifereDoQueEstaGravado(draft)).toBe(true);
  });

  it("trata um RDO que ainda não está no banco como diferente", async () => {
    const database = await getCortexDb();
    await database.delete("rdos", RDO_ID);

    expect(await rascunhoDifereDoQueEstaGravado(rascunho())).toBe(true);
  });
});
