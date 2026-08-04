import "fake-indexeddb/auto";

import { deleteDB } from "idb";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { clearSession, setSession } from "../auth/authSession";
import { closeCortexDb, getCortexDb } from "../../lib/db/cortexDb";
import type { LocalRdoRecord } from "../../lib/db/db.types";
import { databaseNameForScope } from "../../lib/db/localDataNamespace";
import { updateSyncState } from "../../lib/db/syncStateRepository";
import {
  applyPulledEventsAtomically,
  markMutationAsSyncing,
  rejectMutationLocally,
} from "../../lib/sync/syncStorage";
import {
  descartarRdoLocalNaoSincronizado,
  queueCancelRdo,
  queueRestoreRdo,
} from "./rdoLifecycle";

const RDO_ID = "00000000-0000-4000-8000-0000000000a1";
const OBRA_ID = "00000000-0000-4000-8000-0000000000a2";
const USER_ID = "00000000-0000-4000-8000-0000000000a3";
const DEVICE_ID = "00000000-0000-4000-8000-0000000000a4";
const BASE_TIME = "2026-08-01T12:00:00.000Z";

let databaseName = "";

function rdo(
  overrides: Partial<LocalRdoRecord> = {},
): LocalRdoRecord {
  return {
    id: RDO_ID,
    obraId: OBRA_ID,
    programacaoId: null,
    numeroRdo: "RDO-014",
    dataRdo: "2026-08-01",
    statusRdo: "ENVIADO",
    syncStatus: "SYNCED",
    versaoEntidade: 3,
    payload: { obraId: OBRA_ID, numeroRdo: "RDO-014" },
    createdAt: BASE_TIME,
    updatedAt: BASE_TIME,
    canceladoEm: null,
    ...overrides,
  };
}

beforeEach(async () => {
  vi.stubGlobal("window", new EventTarget());
  setSession({
    colaboradorId: USER_ID,
    nome: "Apontador",
    papelAcesso: "BETA",
    escopoGlobal: false,
    obraIds: [OBRA_ID],
    expiraEm: new Date(Date.now() + 60_000).toISOString(),
  });
  databaseName = await databaseNameForScope(USER_ID, `BETA:${OBRA_ID}`);
  await updateSyncState({
    deviceId: DEVICE_ID,
    usuarioId: USER_ID,
    lastPulledCommitSeq: 0,
    lastAckedCommitSeq: 0,
  });
});

afterEach(async () => {
  await closeCortexDb();
  if (databaseName) await deleteDB(databaseName);
  clearSession();
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

describe("apagar e recuperar RDO", () => {
  it("enfileira o apagamento e marca o registro sem destruí-lo", async () => {
    const database = await getCortexDb();
    await database.put("rdos", rdo());

    const apagado = await queueCancelRdo(rdo());

    expect(apagado.canceladoEm).not.toBeNull();
    expect(apagado.statusRdo).toBe("CANCELADA");
    // O conteúdo do lançamento continua inteiro: apagar é marcar, não remover.
    expect(apagado.payload).toEqual(rdo().payload);
    expect(await database.get("rdos", RDO_ID)).toEqual(apagado);

    const [mutacao] = await database.getAll("outbox_mutations");
    expect(mutacao).toMatchObject({
      entidadeTipo: "RDO",
      entidadeId: RDO_ID,
      operacao: "CANCELAR_RDO",
      operation: "DELETE",
      baseVersao: 3,
      status: "PENDING",
    });
  });

  it("encadeia a recuperação depois do apagamento ainda na fila", async () => {
    const database = await getCortexDb();
    await database.put("rdos", rdo());

    const apagado = await queueCancelRdo(rdo());
    const recuperado = await queueRestoreRdo(apagado);

    expect(recuperado.canceladoEm).toBeNull();

    const fila = (await database.getAll("outbox_mutations"))
      .sort((left, right) => left.baseVersao! - right.baseVersao!);
    expect(fila.map((mutacao) => mutacao.operacao)).toEqual([
      "CANCELAR_RDO",
      "RESTAURAR_RDO",
    ]);
    expect(fila.map((mutacao) => mutacao.baseVersao)).toEqual([3, 4]);
    expect(fila[1].causationId).toBe(fila[0].clientMutationId);
  });

  it("recusa marcar como apagado um RDO que o servidor ainda não conhece", async () => {
    const database = await getCortexDb();
    const local = rdo({ versaoEntidade: null, syncStatus: "PENDING_SYNC" });
    await database.put("rdos", local);

    await expect(queueCancelRdo(local)).rejects.toThrow(
      /versão autoritativa/,
    );
    expect(await database.getAll("outbox_mutations")).toHaveLength(0);
  });

  it("descarta de vez o RDO que nunca foi sincronizado, com a criação na fila", async () => {
    // O caso mais comum de querer apagar: o lançamento errado, percebido em
    // campo, antes de qualquer sincronização. Sem tirar a criação da fila, a
    // rodada seguinte ressuscitaria no servidor o RDO recém-descartado.
    const database = await getCortexDb();
    const local = rdo({ versaoEntidade: null, syncStatus: "PENDING_SYNC" });
    await database.put("rdos", local);
    await database.put("outbox_mutations", {
      clientMutationId: crypto.randomUUID(),
      entidadeTipo: "RDO",
      entidadeId: RDO_ID,
      operacao: "CRIAR_RDO",
      status: "PENDING",
      tentativas: 0,
      ultimaTentativaEm: null,
      ultimoErro: null,
      baseVersao: null,
      payload: {},
      criadaNoClienteEm: BASE_TIME,
      updatedAt: BASE_TIME,
      conflito: null,
    } as never);
    await database.put("rdoMaoObra", {
      id: crypto.randomUUID(),
      rdoId: RDO_ID,
      syncStatus: "PENDING_SYNC",
      payload: {},
      createdAt: BASE_TIME,
      updatedAt: BASE_TIME,
    } as never);

    await descartarRdoLocalNaoSincronizado(local);

    expect(await database.get("rdos", RDO_ID)).toBeUndefined();
    expect(await database.getAll("outbox_mutations")).toHaveLength(0);
    expect(await database.getAll("rdoMaoObra")).toHaveLength(0);
  });

  it("não descarta um RDO que o servidor já aceitou", async () => {
    const database = await getCortexDb();
    await database.put("rdos", rdo());

    await expect(
      descartarRdoLocalNaoSincronizado(rdo()),
    ).rejects.toThrow(/já foi aceito/);
    expect(await database.get("rdos", RDO_ID)).toBeDefined();
  });

  it("não descarta enquanto o envio está em curso", async () => {
    // De um envio em voo não se sabe se o servidor aceitou; descartar às cegas
    // deixaria um registro órfão do outro lado.
    const database = await getCortexDb();
    const local = rdo({ versaoEntidade: null, syncStatus: "SYNCING" });
    await database.put("rdos", local);
    await database.put("outbox_mutations", {
      clientMutationId: crypto.randomUUID(),
      entidadeTipo: "RDO",
      entidadeId: RDO_ID,
      operacao: "CRIAR_RDO",
      status: "SYNCING",
      tentativas: 1,
      ultimaTentativaEm: BASE_TIME,
      ultimoErro: null,
      baseVersao: null,
      payload: {},
      criadaNoClienteEm: BASE_TIME,
      updatedAt: BASE_TIME,
      conflito: null,
    } as never);

    await expect(
      descartarRdoLocalNaoSincronizado(local),
    ).rejects.toThrow(/sendo enviado/);
    expect(await database.get("rdos", RDO_ID)).toBeDefined();
    expect(await database.getAll("outbox_mutations")).toHaveLength(1);
  });

  it("não empilha alteração sobre uma recusa não resolvida", async () => {
    const database = await getCortexDb();
    await database.put("rdos", rdo());
    await queueCancelRdo(rdo());
    const [mutacao] = await database.getAll("outbox_mutations");
    await markMutationAsSyncing(mutacao);
    await rejectMutationLocally(
      mutacao.clientMutationId,
      "PAYLOAD_INVALIDO",
      "payload inválido.",
    );

    await expect(
      queueRestoreRdo((await database.get("rdos", RDO_ID))!),
    ).rejects.toThrow(/recusada/);
  });

  it("converge com o estado que o servidor devolve na recuperação", async () => {
    // O palpite local do apagamento era CANCELADA; o servidor deriva o estado
    // de volta de `enviado_em`, e é o dele que vale.
    const database = await getCortexDb();
    await database.put("rdos", rdo({ statusRdo: "CANCELADA" }));

    await applyPulledEventsAtomically([
      {
        commitSeq: 1,
        eventoId: crypto.randomUUID(),
        tipoEvento: "RDO_RESTAURADO",
        entidadeTipo: "RDO",
        entidadeId: RDO_ID,
        versaoEntidade: 5,
        payload: { rdoId: RDO_ID, status: "ENVIADO" },
      },
    ], 1);

    expect(await database.get("rdos", RDO_ID)).toMatchObject({
      statusRdo: "ENVIADO",
      canceladoEm: null,
      versaoEntidade: 5,
    });
  });

  it("marca como apagado o que outro dispositivo apagou", async () => {
    const database = await getCortexDb();
    await database.put("rdos", rdo());

    await applyPulledEventsAtomically([
      {
        commitSeq: 1,
        eventoId: crypto.randomUUID(),
        tipoEvento: "RDO_CANCELADO",
        entidadeTipo: "RDO",
        entidadeId: RDO_ID,
        ocorridoEmUtc: "2026-08-01T18:30:00.000Z",
        versaoEntidade: 4,
        payload: { rdoId: RDO_ID, status: "CANCELADA" },
      },
    ], 1);

    expect(await database.get("rdos", RDO_ID)).toMatchObject({
      statusRdo: "CANCELADA",
      canceladoEm: "2026-08-01T18:30:00.000Z",
    });
  });
  /**
   * Apagar é a saída do conflito insolúvel, não mais uma porta trancada.
   *
   * A recusa era circular: "resolva o conflito antes de apagar", quando o
   * conflito é justamente o motivo de querer apagar. Com os dois lados no mesmo
   * campo não há fusão a propor, então o registro ficava para sempre com a
   * tarja vermelha e nenhuma ação capaz de tirá-la.
   */
  it("apaga o RDO mesmo com uma edição local presa em conflito", async () => {
    const database = await getCortexDb();
    await database.put("rdos", rdo({ syncStatus: "CONFLICT" }));
    const presaId = crypto.randomUUID();
    await database.put("outbox_mutations", {
      clientMutationId: presaId,
      entidadeTipo: "RDO",
      entidadeId: RDO_ID,
      operacao: "ATUALIZAR_RDO_RASCUNHO",
      status: "CONFLICT",
      tentativas: 1,
      ultimaTentativaEm: BASE_TIME,
      ultimoErro: "Conflito de versão.",
      baseVersao: 3,
      payload: {},
      criadaNoClienteEm: BASE_TIME,
      updatedAt: BASE_TIME,
      conflito: { versaoAtual: 7 },
    } as never);
    await database.put("operational_events", {
      id: crypto.randomUUID(),
      type: "RDO_EDITADO",
      principalEntity: { tipo: "RDO", id: RDO_ID },
      principalEntityKey: `RDO:${RDO_ID}`,
      relatedEntities: [],
      obraId: OBRA_ID,
      rdoId: RDO_ID,
      colaboradorId: null,
      occurredAt: BASE_TIME,
      syncedAt: null,
      origin: "OFFLINE",
      responsibleUserId: USER_ID,
      responsibleUserName: "Apontador",
      payload: {},
      syncStatus: "SYNC_FAILED",
      schemaVersion: 13,
      clientMutationId: presaId,
      deviceId: DEVICE_ID,
      correlationId: crypto.randomUUID(),
      causationId: null,
      entityVersion: 3,
      result: "CONFLICT",
      errorCategory: "VERSION_CONFLICT",
    } as never);

    const apagado = await queueCancelRdo(rdo({ syncStatus: "CONFLICT" }));

    expect(apagado.canceladoEm).not.toBeNull();
    const fila = await database.getAll("outbox_mutations");
    // A edição presa saiu da fila; ficou só o apagamento.
    expect(fila).toHaveLength(1);
    expect(fila[0]).toMatchObject({
      operacao: "CANCELAR_RDO",
      status: "PENDING",
      // Contra a versão que o servidor informou no conflito, não contra a que
      // este aparelho conhecia: senão o apagamento nasceria em conflito também.
      baseVersao: 7,
    });
  });

  /** O que se perde é a intenção de escrita, nunca o rastro dela. */
  it("preserva na Memória a evidência da edição abandonada", async () => {
    const database = await getCortexDb();
    await database.put("rdos", rdo({ syncStatus: "CONFLICT" }));
    const presaId = crypto.randomUUID();
    await database.put("outbox_mutations", {
      clientMutationId: presaId,
      entidadeTipo: "RDO",
      entidadeId: RDO_ID,
      operacao: "ATUALIZAR_RDO_RASCUNHO",
      status: "CONFLICT",
      tentativas: 1,
      ultimaTentativaEm: BASE_TIME,
      ultimoErro: "Conflito de versão.",
      baseVersao: 3,
      payload: {},
      criadaNoClienteEm: BASE_TIME,
      updatedAt: BASE_TIME,
      conflito: { versaoAtual: 4 },
    } as never);
    const eventoId = crypto.randomUUID();
    await database.put("operational_events", {
      id: eventoId,
      type: "RDO_EDITADO",
      principalEntity: { tipo: "RDO", id: RDO_ID },
      principalEntityKey: `RDO:${RDO_ID}`,
      relatedEntities: [],
      obraId: OBRA_ID,
      rdoId: RDO_ID,
      colaboradorId: null,
      occurredAt: BASE_TIME,
      syncedAt: null,
      origin: "OFFLINE",
      responsibleUserId: USER_ID,
      responsibleUserName: "Apontador",
      payload: {},
      syncStatus: "SYNC_FAILED",
      schemaVersion: 13,
      clientMutationId: presaId,
      deviceId: DEVICE_ID,
      correlationId: crypto.randomUUID(),
      causationId: null,
      entityVersion: 3,
      result: "CONFLICT",
      errorCategory: "VERSION_CONFLICT",
    } as never);

    await queueCancelRdo(rdo({ syncStatus: "CONFLICT" }));

    const evento = await database.get("operational_events", eventoId);
    expect(evento).toMatchObject({
      result: "DISCARDED",
      errorCategory: "SUPERSEDED_BY_RDO_DELETION",
    });
  });

  /** Recusa é do servidor, e some pela mesma porta quando o registro sai. */
  it("apaga o RDO com uma alteração recusada parada na fila", async () => {
    const database = await getCortexDb();
    await database.put("rdos", rdo({ syncStatus: "ERROR" }));
    await database.put("outbox_mutations", {
      clientMutationId: crypto.randomUUID(),
      entidadeTipo: "RDO",
      entidadeId: RDO_ID,
      operacao: "ATUALIZAR_RDO_RASCUNHO",
      status: "REJECTED",
      tentativas: 2,
      ultimaTentativaEm: BASE_TIME,
      ultimoErro: "Recusado pelo servidor.",
      baseVersao: 3,
      payload: {},
      criadaNoClienteEm: BASE_TIME,
      updatedAt: BASE_TIME,
      conflito: null,
    } as never);

    const apagado = await queueCancelRdo(rdo({ syncStatus: "ERROR" }));

    expect(apagado.canceladoEm).not.toBeNull();
    const fila = await database.getAll("outbox_mutations");
    expect(fila).toHaveLength(1);
    expect(fila[0]).toMatchObject({
      operacao: "CANCELAR_RDO",
      baseVersao: 3,
    });
  });

  /**
   * A corrida que trazia o beco de volta.
   *
   * Uma reconciliação em outra aba move a original de CONFLICT para REJECTED
   * entre a leitura e a transação. Exigir o mesmo estado de antes preservava a
   * linha, e a checagem seguinte voltava a recusar o apagamento por "alteração
   * recusada" — o mesmo impasse, agora por acidente de temporização. O que
   * decide é se a edição continua presa, não em qual dos dois estados.
   */
  it("apaga mesmo quando a edição presa muda de conflito para recusa no meio do caminho", async () => {
    const database = await getCortexDb();
    await database.put("rdos", rdo({ syncStatus: "CONFLICT" }));
    const presaId = crypto.randomUUID();
    const linha = {
      clientMutationId: presaId,
      entidadeTipo: "RDO",
      entidadeId: RDO_ID,
      operacao: "ATUALIZAR_RDO_RASCUNHO",
      status: "CONFLICT",
      tentativas: 1,
      ultimaTentativaEm: BASE_TIME,
      ultimoErro: "Conflito de versão.",
      baseVersao: 3,
      payload: {},
      criadaNoClienteEm: BASE_TIME,
      updatedAt: BASE_TIME,
      conflito: { versaoAtual: 3 },
    };
    await database.put("outbox_mutations", linha as never);

    // A outra aba reconcilia: a original passa a REJECTED com a marca de
    // superação, exatamente como o motor a deixa.
    const original = database.getAllFromIndex;
    let primeiraLeitura = true;
    database.getAllFromIndex = (async (...args: unknown[]) => {
      const resultado = await original.apply(database, args as never);
      if (primeiraLeitura && args[0] === "outbox_mutations") {
        primeiraLeitura = false;
        await database.put("outbox_mutations", {
          ...linha,
          status: "REJECTED",
          blockedReason: `SUPERSEDED_BY:${crypto.randomUUID()}`,
        } as never);
      }
      return resultado;
    }) as never;

    try {
      const apagado = await queueCancelRdo(rdo({ syncStatus: "CONFLICT" }));
      expect(apagado.canceladoEm).not.toBeNull();
    } finally {
      database.getAllFromIndex = original;
    }

    const fila = await database.getAll("outbox_mutations");
    expect(fila).toHaveLength(1);
    expect(fila[0]).toMatchObject({ operacao: "CANCELAR_RDO" });
  });
});
