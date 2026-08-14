import "fake-indexeddb/auto";

import { deleteDB } from "idb";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import {
  clearSession,
  setSession,
} from "../../features/auth/authSession";
import { createEmptyRdo } from "../../features/rdos/createEmptyRdo";
import { closeCortexDb, getCortexDb } from "./cortexDb";
import type {
  LocalRdoRecord,
  OutboxMutationRecord,
} from "./db.types";
import { databaseNameForScope } from "./localDataNamespace";
import {
  releaseBlockedRdoUpdatesForSync,
  saveExistingRdoDraftAtomically,
} from "./localRdoService";
import { LOCAL_MUTATION_QUEUED_EVENT } from "../sync/localMutationCoordinator";

const OBRA_ID = "00000000-0000-4000-8000-000000000801";
const RDO_ID = "00000000-0000-4000-8000-000000000802";
const MUTATION_ID = "00000000-0000-4000-8000-000000000803";
const OCCURRED_AT = "2026-07-24T13:00:00.000Z";

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
    numeroRdo: "RDO-0802",
    dataRdo: "2026-07-24",
    statusRdo: "RASCUNHO",
    syncStatus: "SYNCED",
    versaoEntidade: 4,
    payload: { observacoes: "o que o servidor já tem" },
    createdAt: OCCURRED_AT,
    updatedAt: OCCURRED_AT,
  };
}

function mutacaoPendente(): OutboxMutationRecord {
  return {
    clientMutationId: MUTATION_ID,
    entidadeTipo: "RDO",
    entidadeId: RDO_ID,
    operacao: "ATUALIZAR_RDO_RASCUNHO",
    baseVersao: 4,
    payload: { observacoes: "correção que ainda não subiu" },
    status: "PENDING",
    tentativas: 0,
    ultimaTentativaEm: null,
    ultimoErro: null,
    conflito: null,
    criadaNoClienteEm: OCCURRED_AT,
    updatedAt: OCCURRED_AT,
  };
}

function rascunhoCorrigido(observacoes: string) {
  const draft = createEmptyRdo();
  draft.id = RDO_ID;
  draft.obraId = OBRA_ID;
  draft.numeroRdo = "RDO-0802";
  draft.dataRdo = "2026-07-24";
  draft.observacoes = observacoes;
  return draft;
}

/** Conta os despertares que chegam ao agendador durante um trecho de código. */
function contarDespertares(): {
  total: () => number;
  parar: () => void;
} {
  let total = 0;
  const ouvinte = () => {
    total += 1;
  };
  window.addEventListener(LOCAL_MUTATION_QUEUED_EVENT, ouvinte);
  return {
    total: () => total,
    parar: () =>
      window.removeEventListener(LOCAL_MUTATION_QUEUED_EVENT, ouvinte),
  };
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

/**
 * O motor não vigia o banco: ele acorda por aviso, por intervalo, pela volta à
 * aba ou pela abertura do aplicativo. Uma gravação calada não se perde, mas
 * fica parada até meio minuto — e quem salva e fecha o navegador em seguida sai
 * sem ter subido nada.
 *
 * <p>A criação de RDO sempre avisou, porque passa pelo coordenador de mutações
 * locais. A edição de um RDO já sincronizado monta o envelope na mão e era a
 * única escrita de usuário do Córtex que ficava calada.
 */
describe("a edição de RDO acorda a sincronização", () => {
  it("avisa o agendador ao corrigir um RDO já sincronizado", async () => {
    const database = await getCortexDb();
    await database.put("rdos", rdoSincronizado());

    const despertares = contarDespertares();
    try {
      await saveExistingRdoDraftAtomically(
        rascunhoCorrigido("medição refeita no fim do dia"),
      );

      expect(despertares.total()).toBe(1);
    } finally {
      despertares.parar();
    }
  });

  /*
   * A segunda correção do mesmo RDO não cria linha nova: ela reaproveita a
   * pendente que ainda não foi tentada. É outro ramo do mesmo salvamento, e
   * quem corrige duas vezes seguidas merece o mesmo despertar da primeira.
   */
  it("avisa também quando a correção reaproveita a linha pendente", async () => {
    const database = await getCortexDb();
    await database.put("rdos", {
      ...rdoSincronizado(),
      syncStatus: "PENDING_SYNC",
    });
    await database.put("outbox_mutations", mutacaoPendente());

    const despertares = contarDespertares();
    try {
      await saveExistingRdoDraftAtomically(
        rascunhoCorrigido("segunda correção, mesma linha"),
      );

      expect(despertares.total()).toBe(1);
    } finally {
      despertares.parar();
    }

    const naFila = await database.getAllFromIndex(
      "outbox_mutations",
      "by-entity-id",
      RDO_ID,
    );
    expect(naFila).toHaveLength(1);
  });

  /*
   * O aviso depois do commit, e não antes: o agendador pode começar o ciclo no
   * mesmo instante em que é avisado, e precisa encontrar a fila no estado que a
   * transação acabou de deixar. Avisar antes do commit faria o envio ler uma
   * fila sem a linha que motivou o aviso — despertar à toa, correção parada.
   */
  it("chega com a fila já gravada, não antes", async () => {
    const database = await getCortexDb();
    await database.put("rdos", rdoSincronizado());

    let filaNoMomentoDoAviso: OutboxMutationRecord[] = [];
    const leitura = new Promise<void>((resolve) => {
      window.addEventListener(
        LOCAL_MUTATION_QUEUED_EVENT,
        () => {
          void database
            .getAllFromIndex("outbox_mutations", "by-entity-id", RDO_ID)
            .then((linhas) => {
              filaNoMomentoDoAviso = linhas;
              resolve();
            });
        },
        { once: true },
      );
    });

    await saveExistingRdoDraftAtomically(
      rascunhoCorrigido("o que precisa estar visível ao motor"),
    );
    await leitura;

    expect(filaNoMomentoDoAviso).toHaveLength(1);
    expect(filaNoMomentoDoAviso[0]).toMatchObject({
      entidadeTipo: "RDO",
      entidadeId: RDO_ID,
      operacao: "ATUALIZAR_RDO_RASCUNHO",
      status: "PENDING",
      baseVersao: 4,
    });
  });

  /*
   * A guarda do laço. Os reparos rodam DENTRO do ciclo de sincronização e
   * reescrevem linhas da fila para desatolá-la. Se eles avisassem, cada ciclo
   * pediria outro ciclo, para sempre — e o aparelho de campo, que é o que menos
   * pode gastar bateria e dados, seria o mais castigado.
   */
  it("não desperta o motor a partir do reparo do próprio ciclo", async () => {
    const database = await getCortexDb();
    await database.put("rdos", {
      ...rdoSincronizado(),
      syncStatus: "PENDING_SYNC",
    });
    await database.put("outbox_mutations", {
      ...mutacaoPendente(),
      blockedReason: "RDO_CREATION_CONTEXT_REQUIRED",
    });

    const despertares = contarDespertares();
    try {
      const soltas = await releaseBlockedRdoUpdatesForSync();

      expect(soltas).toBe(1);
      expect(despertares.total()).toBe(0);
    } finally {
      despertares.parar();
    }
  });
});
