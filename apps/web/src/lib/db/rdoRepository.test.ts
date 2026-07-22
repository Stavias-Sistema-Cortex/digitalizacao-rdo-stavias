import "fake-indexeddb/auto";

import { deleteDB } from "idb";
import { afterEach, beforeEach, describe, expect, it } from "vitest";

import {
  clearSession,
  setSession,
} from "../../features/auth/authSession";
import {
  createEmptyRdo,
} from "../../features/rdos/createEmptyRdo";
import {
  closeCortexDb,
  getCortexDb,
} from "./cortexDb";
import { currentDataDatabaseName } from "./localDataNamespace";
import { saveNewRdoDraftAtomically } from "./localRdoService";
import { discardRejectedLocalRdo } from "./rdoRepository";

const OBRA_ID = "00000000-0000-4000-8000-000000000701";

let databaseName = "";

function rejectedDraft() {
  const draft = createEmptyRdo();

  draft.id = "00000000-0000-4000-8000-000000000702";
  draft.obraId = OBRA_ID;
  draft.numeroRdo = "RDO-REJEITADO-001";
  draft.dataRdo = "2026-07-21";
  draft.cliente = "Intervias";
  draft.contrato = "CW473489823";
  draft.cidade = "Mococa";
  draft.servicosExecutados[0] = {
    ...draft.servicosExecutados[0],
    servicoNome: "Aplicação de CBUQ",
    quantidadeExecutada: 0,
  };

  return draft;
}

describe("discardRejectedLocalRdo", () => {
  beforeEach(async () => {
    const actorId = crypto.randomUUID();

    setSession({
      colaboradorId: actorId,
      nome: "Operador de campo",
      papelAcesso: "BETA",
      escopoGlobal: false,
      obraIds: [OBRA_ID],
      expiraEm: new Date(Date.now() + 60_000).toISOString(),
    });
    databaseName = await currentDataDatabaseName();
  });

  afterEach(async () => {
    await closeCortexDb();
    if (databaseName) {
      await deleteDB(databaseName);
    }
    clearSession();
  });

  it("descarta integralmente um RDO apenas local que foi rejeitado de forma terminal", async () => {
    const draft = rejectedDraft();
    const saved = await saveNewRdoDraftAtomically(draft);
    const database = await getCortexDb();
    const rdo = await database.get("rdos", draft.id);

    await Promise.all([
      database.put("rdos", {
        ...rdo!,
        syncStatus: "ERROR",
      }),
      database.put("outbox_mutations", {
        ...saved.mutation,
        status: "REJECTED",
        ultimoErro: "authorizationScope deve conter identificadores de obra canônicos.",
      }),
      database.put("rdo_attachments", {
        id: "00000000-0000-4000-8000-000000000703",
        rdoId: draft.id,
        obraId: draft.obraId,
        tipo: "FOTO",
        nome: "frente-de-servico.jpg",
        nomeOriginal: "frente-de-servico.jpg",
        mimeType: "image/jpeg",
        tamanhoOriginalBytes: 1200,
        tamanhoComprimidoBytes: 900,
        tamanhoBytes: 900,
        syncStatus: "SYNC_FAILED",
        createdAt: "2026-07-21T10:00:00.000Z",
        updatedAt: "2026-07-21T10:00:00.000Z",
        removedAt: null,
        metadata: {},
      }),
    ]);

    await discardRejectedLocalRdo(draft.id);

    expect(await database.get("rdos", draft.id)).toBeUndefined();
    expect(await database.getAllFromIndex(
      "outbox_mutations",
      "by-entity-id",
      draft.id,
    )).toEqual([]);
    expect(await database.getAllFromIndex(
      "operational_events",
      "by-rdo-id",
      draft.id,
    )).toEqual([]);
    expect(await database.getAllFromIndex(
      "rdo_attachments",
      "by-rdo-id",
      draft.id,
    )).toEqual([]);
    expect(await database.getAllFromIndex(
      "rdoMaoObra",
      "by-rdo-id",
      draft.id,
    )).toEqual([]);
    expect(await database.getAllFromIndex(
      "rdoEquipamentos",
      "by-rdo-id",
      draft.id,
    )).toEqual([]);
    expect(await database.getAllFromIndex(
      "rdoMateriais",
      "by-rdo-id",
      draft.id,
    )).toEqual([]);
    expect(await database.getAllFromIndex(
      "rdoControlesGeometricos",
      "by-rdo-id",
      draft.id,
    )).toEqual([]);
  });

  it("recusa descartar um RDO que ainda não recebeu uma rejeição terminal", async () => {
    const draft = rejectedDraft();
    await saveNewRdoDraftAtomically(draft);
    const database = await getCortexDb();

    await expect(discardRejectedLocalRdo(draft.id)).rejects.toThrow(
      "Somente RDOs rejeitados definitivamente podem ser descartados localmente.",
    );

    expect(await database.get("rdos", draft.id)).toBeDefined();
    expect(await database.getAll("outbox_mutations")).toHaveLength(1);
  });

  it("preserva a integridade da fila quando outra mutação depende do RDO rejeitado", async () => {
    const draft = rejectedDraft();
    const saved = await saveNewRdoDraftAtomically(draft);
    const database = await getCortexDb();
    const rdo = await database.get("rdos", draft.id);
    const dependentMutationId = "00000000-0000-4000-8000-000000000704";

    await Promise.all([
      database.put("rdos", {
        ...rdo!,
        syncStatus: "ERROR",
      }),
      database.put("outbox_mutations", {
        ...saved.mutation,
        status: "REJECTED",
        ultimoErro: "Rejeição terminal.",
      }),
      database.put("outbox_mutations", {
        ...saved.mutation,
        clientMutationId: dependentMutationId,
        entidadeTipo: "MENSAGEM",
        entidadeId: "mensagem-dependente",
        operacao: "CRIAR_MENSAGEM",
        correlationId: dependentMutationId,
        trace: {
          ...saved.mutation.trace,
          correlationId: dependentMutationId,
          ontologyEventId: "00000000-0000-4000-8000-000000000705",
        },
        status: "PENDING",
        dependsOnMutationIds: [saved.mutation.clientMutationId],
      }),
    ]);

    await expect(discardRejectedLocalRdo(draft.id)).rejects.toThrow(
      "Somente RDOs rejeitados definitivamente podem ser descartados localmente.",
    );

    expect(await database.get("rdos", draft.id)).toBeDefined();
    expect(await database.get("outbox_mutations", dependentMutationId)).toBeDefined();
  });
});
