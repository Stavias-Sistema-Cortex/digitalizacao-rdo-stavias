import "fake-indexeddb/auto";

import { deleteDB } from "idb";
import { afterEach, beforeEach, describe, expect, it } from "vitest";

import {
  clearSession,
  setSession,
} from "../../features/auth/authSession";
import { closeCortexDb, getCortexDb } from "../db/cortexDb";
import type {
  LocalRdoRecord,
  OperationalEventRecord,
  OutboxMutationRecord,
} from "../db/db.types";
import { databaseNameForScope } from "../db/localDataNamespace";
import {
  localEventToSearchDocument,
  memoryCoverage,
  memoryStatusLabel,
} from "../../features/home/memory/memorySearchDocument";
import { applyPushResultAtomically } from "./syncStorage";

const OBRA_ID = "00000000-0000-4000-8000-000000000901";
const RDO_ID = "00000000-0000-4000-8000-000000000902";
const MUTATION_ID = "00000000-0000-4000-8000-000000000903";
const OUTRA_MUTACAO_ID = "00000000-0000-4000-8000-000000000904";
const EVENTO_ID = "00000000-0000-4000-8000-000000000905";
const EVENTO_DA_OUTRA_ID = "00000000-0000-4000-8000-000000000906";
const OCORRIDO_EM = "2026-08-13T14:14:00.000Z";
const ESCOPO = "escopo-memoria";

let databaseName = "";
let userId = "";

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

function rdoLocal(): LocalRdoRecord {
  return {
    id: RDO_ID,
    obraId: OBRA_ID,
    programacaoId: null,
    numeroRdo: "RDO-0010",
    dataRdo: "2026-08-13",
    statusRdo: "RASCUNHO",
    syncStatus: "PENDING_SYNC",
    versaoEntidade: null,
    payload: { observacoes: "chuva forte no trecho" },
    createdAt: OCORRIDO_EM,
    updatedAt: OCORRIDO_EM,
  };
}

/**
 * O evento que o RDO escreve ao salvar: `schemaVersion: 1`, sem `result` e sem
 * `errorCategory`. É o que existe hoje nos aparelhos em campo.
 */
function eventoLegado(id: string): OperationalEventRecord {
  return {
    id,
    type: "RDO_SALVO_OFFLINE",
    principalEntity: { tipo: "RDO", id: RDO_ID, nome: "RDO-0010" },
    principalEntityKey: `RDO:${RDO_ID}`,
    relatedEntities: [],
    obraId: OBRA_ID,
    rdoId: RDO_ID,
    colaboradorId: null,
    occurredAt: OCORRIDO_EM,
    syncedAt: null,
    origin: "OFFLINE",
    responsibleUserId: null,
    responsibleUserName: null,
    payload: {},
    syncStatus: "PENDING_SYNC",
    schemaVersion: 1,
  };
}

function mutacaoLegada(
  clientMutationId: string,
  eventoIds: readonly string[],
): OutboxMutationRecord {
  return {
    clientMutationId,
    entidadeTipo: "RDO",
    entidadeId: RDO_ID,
    operacao: "ATUALIZAR_RDO_RASCUNHO",
    baseVersao: null,
    payload: {
      operationalEvents: eventoIds.map((id) => ({ id })),
    },
    status: "SYNCING",
    tentativas: 1,
    ultimaTentativaEm: OCORRIDO_EM,
    ultimoErro: null,
    conflito: null,
    blockedReason: null,
    criadaNoClienteEm: OCORRIDO_EM,
    updatedAt: OCORRIDO_EM,
  };
}

async function statusNaMemoria(eventoId: string) {
  const database = await getCortexDb();
  const evento = await database.get("operational_events", eventoId);
  return localEventToSearchDocument(userId, ESCOPO, evento!);
}

beforeEach(async () => {
  userId = crypto.randomUUID();
  setSession(session());
  databaseName = await databaseNameForScope(userId, `BETA:${OBRA_ID}`);
  const database = await getCortexDb();
  await database.put("rdos", rdoLocal());
  await database.put("operational_events", eventoLegado(EVENTO_ID));
});

afterEach(async () => {
  await closeCortexDb();
  if (databaseName) await deleteDB(databaseName);
  clearSession();
});

/**
 * Um envio que não chegou ao fim não é uma recusa.
 *
 * <p>Os eventos legados do RDO recebiam só `SYNC_FAILED` quando o envio
 * terminava mal, sem `result` e sem `errorCategory`. A Memória, sem veredito
 * para ler, caía no `REJECTED` implícito do fim da escada: quatro cartões
 * "Rejeitado" por salvamento, sem código seguro que explicasse a recusa —
 * porque recusa não houve. Quem tinha acabado de apontar em campo lia ali que
 * o servidor tinha recusado o trabalho do dia.
 */
describe("falha de envio não é recusa", () => {
  it("chama de falha no envio o evento legado sem veredito próprio", async () => {
    const database = await getCortexDb();
    await database.put("operational_events", {
      ...eventoLegado(EVENTO_ID),
      syncStatus: "SYNC_FAILED",
    });

    const documento = await statusNaMemoria(EVENTO_ID);

    expect(documento.syncStatus).toBe("SYNC_FAILED");
    expect(memoryStatusLabel(documento.syncStatus)).toBe("Falha no envio");
  });

  /*
   * A tarja de sincronização abre a Memória já filtrada nas pendências, e esse
   * recorte é montado sobre `review`. Sem evidência aqui, a tarja acusava
   * "Falha no envio" e o recorte abria com zero eventos e a frase de cache
   * vazio — sobre um cache íntegro. Aparecer é obrigatório; prometer uma
   * decisão que ninguém tem a tomar é que não pode.
   */
  it("aparece no recorte de pendências, mas sem decisão a tomar", async () => {
    const database = await getCortexDb();
    await database.put("operational_events", {
      ...eventoLegado(EVENTO_ID),
      syncStatus: "SYNC_FAILED",
    });

    const review = (await statusNaMemoria(EVENTO_ID)).review;
    expect(review).not.toBeNull();
    expect(review?.status).toBe("SYNC_FAILED");
    expect(review?.unavailableReason).toBe("SEND_INCOMPLETE");
    expect(review?.canReconcile).toBe(false);
  });

  /*
   * A tarja e o recorte precisam concordar. Se a cobertura acusa pendência, o
   * recorte de pendências tem de ter o que mostrar — do contrário a tela se
   * contradiz: aviso amarelo em cima, "0 eventos" embaixo, e nada a fazer.
   */
  it("mantém a cobertura e o recorte de pendências de acordo", async () => {
    const database = await getCortexDb();
    await database.put("operational_events", {
      ...eventoLegado(EVENTO_ID),
      syncStatus: "SYNC_FAILED",
    });

    const documento = await statusNaMemoria(EVENTO_ID);
    const cobertura = memoryCoverage({
      online: true,
      metadata: null,
      localStatuses: [documento.syncStatus],
    });

    expect(cobertura.code).toBe("SYNC_FAILED");
    expect([documento].filter((item) => item.review !== null)).toHaveLength(1);
  });

  it("devolve o evento à fila quando o erro do servidor é retentável", async () => {
    const database = await getCortexDb();
    await database.put(
      "outbox_mutations",
      mutacaoLegada(MUTATION_ID, [EVENTO_ID]),
    );

    await applyPushResultAtomically({
      clientMutationId: MUTATION_ID,
      status: "ERRO",
      erro: "Indisponível no momento.",
    });

    const documento = await statusNaMemoria(EVENTO_ID);
    expect(documento.syncStatus).toBe("LOCAL_PENDING");
    expect(memoryStatusLabel(documento.syncStatus)).toBe("Local pendente");
  });

  it("dá código seguro à recusa de verdade, em vez do vermelho mudo", async () => {
    const database = await getCortexDb();
    await database.put(
      "outbox_mutations",
      mutacaoLegada(MUTATION_ID, [EVENTO_ID]),
    );

    await applyPushResultAtomically({
      clientMutationId: MUTATION_ID,
      status: "REJEITADA",
      erro: "Obra não encontrada ou arquivada.",
      resultado: {
        rejeicao: { categoria: "WORKSITE_SCOPE", mensagem: "fora do escopo" },
      },
    });

    const documento = await statusNaMemoria(EVENTO_ID);
    expect(documento.syncStatus).toBe("REJECTED");
    expect(documento.errorCategory).toBe("WORKSITE_SCOPE");
  });

  /*
   * O caso comum em campo: o RDO grava a cada campo preenchido, e cada
   * gravação depende da anterior. Quando a anterior não é aplicada e a
   * sucessora — que carrega o rascunho inteiro — assume o lugar dela, nada
   * falhou nem foi recusado.
   */
  it("chama de substituição a cadeia liberada pela gravação seguinte", async () => {
    const database = await getCortexDb();
    await database.put(
      "operational_events",
      eventoLegado(EVENTO_DA_OUTRA_ID),
    );
    await database.put(
      "outbox_mutations",
      mutacaoLegada(MUTATION_ID, [EVENTO_ID]),
    );
    await database.put("outbox_mutations", {
      ...mutacaoLegada(OUTRA_MUTACAO_ID, [EVENTO_DA_OUTRA_ID]),
      status: "PENDING",
      dependsOnMutationIds: [MUTATION_ID],
    });

    await applyPushResultAtomically({
      clientMutationId: MUTATION_ID,
      status: "ERRO",
      erro: "Dependência causal não aplicada.",
    });

    const documento = await statusNaMemoria(EVENTO_ID);
    expect(documento.syncStatus).toBe("SUPERSEDED");
    expect(memoryStatusLabel(documento.syncStatus)).toBe("Substituído");
  });

  it("não chama de recusa o conflito, e não o espalha pela fila do RDO", async () => {
    const database = await getCortexDb();
    await database.put(
      "operational_events",
      eventoLegado(EVENTO_DA_OUTRA_ID),
    );
    await database.put(
      "outbox_mutations",
      mutacaoLegada(MUTATION_ID, [EVENTO_ID]),
    );
    await database.put("outbox_mutations", {
      ...mutacaoLegada(OUTRA_MUTACAO_ID, [EVENTO_DA_OUTRA_ID]),
      status: "PENDING",
    });

    await applyPushResultAtomically({
      clientMutationId: MUTATION_ID,
      status: "CONFLITO",
      erro: "Versão mais recente no servidor.",
      conflito: { versaoAtual: 4 },
    });

    expect((await statusNaMemoria(EVENTO_ID)).syncStatus).toBe("CONFLICT");
    // A outra mutação segue na fila e nunca foi enviada: pintá-la de vermelho
    // era acusar de falha o que ainda nem tinha saído do aparelho.
    expect((await statusNaMemoria(EVENTO_DA_OUTRA_ID)).syncStatus)
      .toBe("LOCAL_PENDING");
  });
});
