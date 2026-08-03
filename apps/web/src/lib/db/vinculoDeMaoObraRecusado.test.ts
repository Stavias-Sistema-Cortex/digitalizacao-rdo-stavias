import "fake-indexeddb/auto";

import { deleteDB } from "idb";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import {
  clearSession,
  setSession,
} from "../../features/auth/authSession";
import { closeCortexDb, getCortexDb } from "./cortexDb";
import type {
  LocalRdoRecord,
  OutboxMutationRecord,
} from "./db.types";
import { databaseNameForScope } from "./localDataNamespace";
import {
  isErroDeVinculoDeMaoObraNaFilaLegada,
  recoverErroredWorkforceRdoMutationsForSync,
} from "./localRdoService";
import {
  applyPushResultAtomically,
  MAO_OBRA_SEM_VINCULO_EXIGE_DECISAO,
  markMutationAsSyncing,
  mutationAfterErroredRetry,
  queueErroredMutationsForRetry,
} from "../sync/syncStorage";
import { selectReadyOutboxMutations } from "../sync/outboxDependencies";

/**
 * O RDO que o servidor recusa por vínculo, no envelope legado.
 *
 * A mesma recusa chega como `REJEITADA` para o envelope canônico e como `ERRO`
 * para o legado. Só a primeira tinha dono: a fila de saída considera apenas
 * `PENDING`, o botão de revisão só enxerga `REJECTED`, e a recuperação de mão
 * de obra exige o envelope v13. O registro legado parava em `ERROR` e ninguém
 * voltava a ele — nem a retentativa automática, nem qualquer ação de tela.
 */

const OBRA_ID = "00000000-0000-4000-8000-0000000009a1";
const RDO_ID = "00000000-0000-4000-8000-0000000009a2";
const MUTATION_ID = "00000000-0000-4000-8000-0000000009a3";
const VINCULADO_ID = "00000000-0000-4000-8000-0000000009a4";
const SEM_VINCULO_ID = "00000000-0000-4000-8000-0000000009a5";
const OCCURRED_AT = "2026-08-02T11:00:00.000Z";

const RECUSA_DO_SERVIDOR =
  "Colaborador não está ativo e vinculado à obra do RDO.";

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

function maoObraItem(overrides: Record<string, unknown>) {
  return {
    localId: "mo-1",
    origemItemId: "",
    sourceRdoId: "",
    origin: "MANUAL",
    availability: "AVAILABLE",
    selected: true,
    colaboradorId: "",
    nomeColaborador: "",
    cargo: "Servente",
    tipoVinculo: "PROPRIO",
    quantidade: "1",
    horaInicio: "07:00",
    horaFim: "17:00",
    observacoes: "",
    ...overrides,
  };
}

function rdoRecord(
  payloadOverrides: Record<string, unknown> = {},
): LocalRdoRecord {
  return {
    id: RDO_ID,
    obraId: OBRA_ID,
    programacaoId: null,
    numeroRdo: "RDO-0901",
    dataRdo: "2026-08-02",
    statusRdo: "RASCUNHO",
    syncStatus: "ERROR",
    versaoEntidade: null,
    payload: {
      id: RDO_ID,
      obraId: OBRA_ID,
      numeroRdo: "RDO-0901",
      dataRdo: "2026-08-02",
      creationContextVersion: 42,
      observacoes: "Frente de serviço no km 12.",
      maoObra: [
        maoObraItem({
          localId: "mo-1",
          colaboradorId: VINCULADO_ID,
          nomeColaborador: "Ana Ribeiro",
        }),
        maoObraItem({
          localId: "mo-2",
          colaboradorId: SEM_VINCULO_ID,
          nomeColaborador: "Bruno Salles",
          origemItemId: "item-de-ontem",
        }),
      ],
      ...payloadOverrides,
    },
    createdAt: OCCURRED_AT,
    updatedAt: OCCURRED_AT,
  };
}

function legacyMutationRecusada(): OutboxMutationRecord {
  return {
    clientMutationId: MUTATION_ID,
    entidadeTipo: "RDO",
    entidadeId: RDO_ID,
    operacao: "CRIAR_RDO",
    baseVersao: null,
    payload: { id: RDO_ID, obraId: OBRA_ID },
    status: "ERROR",
    tentativas: 1,
    ultimaTentativaEm: OCCURRED_AT,
    ultimoErro: RECUSA_DO_SERVIDOR,
    lastSafeCode: "VALIDATION_OR_AUTHORIZATION",
    conflito: null,
    criadaNoClienteEm: OCCURRED_AT,
    updatedAt: OCCURRED_AT,
  };
}

/** O servidor autoriza quem tem vínculo ativo; o outro deixou de ter. */
function contextoAutoritativo() {
  return async () => new Set([VINCULADO_ID]);
}

beforeEach(async () => {
  vi.stubGlobal("window", new EventTarget());
  userId = crypto.randomUUID();
  setSession(session());
  databaseName = await databaseNameForScope(userId, `BETA:${OBRA_ID}`);
});

afterEach(async () => {
  await closeCortexDb();
  if (databaseName) await deleteDB(databaseName);
  clearSession();
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

describe("vínculo de mão de obra recusado na fila legada", () => {
  it("não sai da fila sozinho: ERROR não é reenviado por nenhum ciclo", async () => {
    const database = await getCortexDb();
    await database.put("rdos", rdoRecord());
    await database.put("outbox_mutations", legacyMutationRecusada());

    // Este é o estado que a tela mostrava indefinidamente: a alteração existe,
    // está salva, e nenhuma seleção de envio a considera.
    expect(
      selectReadyOutboxMutations(
        await database.getAll("outbox_mutations"),
        100,
      ),
    ).toEqual([]);
  });

  it("mantém a pessoa pelo nome e devolve a alteração à fila", async () => {
    const database = await getCortexDb();
    await database.put("rdos", rdoRecord());
    await database.put("outbox_mutations", legacyMutationRecusada());

    expect(
      await recoverErroredWorkforceRdoMutationsForSync(undefined, {
        loadAuthorizedCollaboratorIds: contextoAutoritativo(),
        now: () => "2026-08-03T09:00:00.000Z",
      }),
    ).toBe(1);

    const mutation = await database.get("outbox_mutations", MUTATION_ID);
    expect(mutation).toMatchObject({
      status: "PENDING",
      blockedReason: null,
      lastSafeCode: "MAO_OBRA_RECUPERADA_COMO_NOMINAL",
    });

    const maoObra = (
      mutation!.payload as { maoObra: Record<string, unknown>[] }
    ).maoObra;
    expect(maoObra).toHaveLength(2);
    // Quem mantém o vínculo continua ligado ao cadastro.
    expect(maoObra[0]).toMatchObject({
      colaboradorId: VINCULADO_ID,
      nomeColaborador: "Ana Ribeiro",
    });
    // Quem o perdeu continua no RDO, pelo nome, sem o identificador recusado —
    // e sem a origem, que o servidor valida contra o mesmo cadastro.
    expect(maoObra[1]).toMatchObject({
      colaboradorId: null,
      nomeColaborador: "Bruno Salles",
      origemItemId: null,
      cargo: "Servente",
      horaInicio: "07:00",
      horaFim: "17:00",
    });
  });

  it("desbloqueia o envio, que é o ponto de tudo isto", async () => {
    const database = await getCortexDb();
    await database.put("rdos", rdoRecord());
    await database.put("outbox_mutations", legacyMutationRecusada());

    await recoverErroredWorkforceRdoMutationsForSync(undefined, {
      loadAuthorizedCollaboratorIds: contextoAutoritativo(),
    });

    expect(
      selectReadyOutboxMutations(
        await database.getAll("outbox_mutations"),
        100,
      ).map((mutation) => mutation.clientMutationId),
    ).toEqual([MUTATION_ID]);
  });

  it("reflete o reparo no RDO local, para a tela não contradizer a fila", async () => {
    const database = await getCortexDb();
    await database.put("rdos", rdoRecord());
    await database.put("outbox_mutations", legacyMutationRecusada());

    await recoverErroredWorkforceRdoMutationsForSync(undefined, {
      loadAuthorizedCollaboratorIds: contextoAutoritativo(),
    });

    const rdo = await database.get("rdos", RDO_ID);
    expect(rdo).toMatchObject({ syncStatus: "PENDING_SYNC" });
    expect(
      (rdo!.payload as { maoObra: Record<string, unknown>[] }).maoObra[1],
    ).toMatchObject({
      colaboradorId: "",
      nomeColaborador: "Bruno Salles",
    });
  });

  it("preserva o recibo de contexto de criação ao reescrever o RDO local", async () => {
    const database = await getCortexDb();
    await database.put(
      "rdos",
      rdoRecord({ creationContextCache: { sourceVersion: 7 } }),
    );
    await database.put("outbox_mutations", legacyMutationRecusada());

    await recoverErroredWorkforceRdoMutationsForSync(undefined, {
      loadAuthorizedCollaboratorIds: contextoAutoritativo(),
    });

    expect(
      (await database.get("rdos", RDO_ID))!.payload.creationContextCache,
    ).toEqual({ sourceVersion: 7 });
  });

  it("não toca no registro quando o contexto autoritativo não responde", async () => {
    const database = await getCortexDb();
    await database.put("rdos", rdoRecord());
    await database.put("outbox_mutations", legacyMutationRecusada());

    expect(
      await recoverErroredWorkforceRdoMutationsForSync(undefined, {
        loadAuthorizedCollaboratorIds: async () => {
          throw new Error("rede indisponível");
        },
      }),
    ).toBe(0);

    expect(
      await database.get("outbox_mutations", MUTATION_ID),
    ).toMatchObject({
      status: "ERROR",
      ultimoErro: RECUSA_DO_SERVIDOR,
    });
  });

  it("explica o que fazer quando o nome não substitui o cadastro", async () => {
    const database = await getCortexDb();
    await database.put(
      "rdos",
      rdoRecord({
        alocacoesColaboradores: [
          {
            localId: "al-1",
            colaboradorId: SEM_VINCULO_ID,
            equipe: "Equipe A",
            servicoNome: "Fresagem",
          },
        ],
      }),
    );
    await database.put("outbox_mutations", legacyMutationRecusada());

    expect(
      await recoverErroredWorkforceRdoMutationsForSync(undefined, {
        loadAuthorizedCollaboratorIds: contextoAutoritativo(),
        now: () => "2026-08-03T09:00:00.000Z",
      }),
    ).toBe(0);

    const mutation = await database.get("outbox_mutations", MUTATION_ID);
    expect(mutation).toMatchObject({
      status: "ERROR",
      blockedReason: MAO_OBRA_SEM_VINCULO_EXIGE_DECISAO,
    });
    expect(mutation!.ultimoErro).toContain("Reatribua ou remova a alocação");

    // E a partir daí o reenvio automático não insiste contra uma recusa que
    // só uma decisão de quem opera resolve.
    expect(
      mutationAfterErroredRetry(mutation!, "2026-08-03T09:01:00.000Z"),
    ).toBeNull();
    expect(await queueErroredMutationsForRetry()).toBe(0);
  });

  it("converge: repara, envia e o RDO fecha como sincronizado", async () => {
    const database = await getCortexDb();
    await database.put("rdos", rdoRecord());
    await database.put("outbox_mutations", legacyMutationRecusada());

    await recoverErroredWorkforceRdoMutationsForSync(undefined, {
      loadAuthorizedCollaboratorIds: contextoAutoritativo(),
    });

    const [pronta] = selectReadyOutboxMutations(
      await database.getAll("outbox_mutations"),
      100,
    );
    await markMutationAsSyncing(pronta);
    await applyPushResultAtomically({
      clientMutationId: MUTATION_ID,
      status: "APLICADA",
      resultado: { numeroRdo: "RDO-0901", versaoEntidade: 1 },
    });

    expect(
      await database.get("outbox_mutations", MUTATION_ID),
    ).toMatchObject({ status: "SYNCED" });
    expect(await database.get("rdos", RDO_ID)).toMatchObject({
      syncStatus: "SYNCED",
      versaoEntidade: 1,
    });
  });

  it("reconhece apenas a recusa de vínculo, e só no envelope legado", () => {
    const legada = legacyMutationRecusada();
    expect(isErroDeVinculoDeMaoObraNaFilaLegada(legada)).toBe(true);
    expect(
      isErroDeVinculoDeMaoObraNaFilaLegada({
        ...legada,
        ultimoErro: "maoObra contém colaborador duplicado.",
      }),
    ).toBe(false);
    expect(
      isErroDeVinculoDeMaoObraNaFilaLegada({
        ...legada,
        status: "PENDING",
      }),
    ).toBe(false);
    expect(
      isErroDeVinculoDeMaoObraNaFilaLegada({
        ...legada,
        schemaVersion: 13,
      } as unknown as OutboxMutationRecord),
    ).toBe(false);
  });
});

describe("reenvio automático do que parou em ERROR", () => {
  it("devolve à fila com espera escalonada, em vez de bater a cada ciclo", async () => {
    const database = await getCortexDb();
    await database.put("rdos", rdoRecord());
    await database.put("outbox_mutations", {
      ...legacyMutationRecusada(),
      ultimoErro: "Falha temporária no servidor.",
      lastSafeCode: "INTERNAL",
    });

    expect(await queueErroredMutationsForRetry()).toBe(1);

    const mutation = await database.get("outbox_mutations", MUTATION_ID);
    expect(mutation).toMatchObject({
      status: "PENDING",
      retryAttempt: 1,
      lastSafeCode: "LEGACY_ERROR_REQUEUED",
    });
    expect(Date.parse(mutation!.nextAttemptAt!)).toBeGreaterThan(
      Date.parse(mutation!.updatedAt),
    );
  });

  it("cresce a espera a cada recusa repetida", () => {
    const primeira = mutationAfterErroredRetry(
      { ...legacyMutationRecusada(), retryAttempt: 1 },
      "2026-08-03T09:00:00.000Z",
      () => 0,
    );
    const oitava = mutationAfterErroredRetry(
      { ...legacyMutationRecusada(), retryAttempt: 8 },
      "2026-08-03T09:00:00.000Z",
      () => 0,
    );

    const esperaDa = (mutation: OutboxMutationRecord | null) =>
      Date.parse(mutation!.nextAttemptAt!) -
      Date.parse("2026-08-03T09:00:00.000Z");

    expect(esperaDa(primeira)).toBe(2_000);
    expect(esperaDa(oitava)).toBe(256_000);
  });
});
