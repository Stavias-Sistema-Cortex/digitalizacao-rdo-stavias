import { apiFetch } from "../../lib/api/apiClient";
import { getCortexDb } from "../../lib/db/cortexDb";
import type {
  CanonicalOutboxMutationRecord,
  LocalRdoRecord,
  OutboxMutationRecord,
} from "../../lib/db/db.types";
import { getSyncState, updateSyncState } from "../../lib/db/syncStateRepository";
import { commitLocalMutation } from "../../lib/sync/localMutationCoordinator";
import { isCanonicalOutboxMutation } from "../../lib/sync/mutationEnvelope";
import { conflictServerVersion } from "../../lib/sync/syncStorage";
import { getSession } from "../auth/authSession";

const UUID_PATTERN =
  /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;

interface RdoMutationIdentity {
  userId: string;
  deviceId: string;
}

async function rdoMutationIdentity(): Promise<RdoMutationIdentity> {
  const session = getSession();
  if (!session) {
    throw new Error(
      "Apagar ou recuperar um RDO exige uma sessão ativa.",
    );
  }
  const state = await getSyncState();
  const deviceId =
    state.usuarioId === session.colaboradorId &&
      state.deviceId &&
      UUID_PATTERN.test(state.deviceId)
      ? state.deviceId
      : crypto.randomUUID();
  if (
    state.deviceId !== deviceId ||
    state.usuarioId !== session.colaboradorId
  ) {
    await updateSyncState({
      deviceId,
      usuarioId: session.colaboradorId,
      lastPulledCommitSeq: 0,
      lastAckedCommitSeq: 0,
    });
  }
  return { userId: session.colaboradorId, deviceId };
}

function isRdoMutation(
  mutation: OutboxMutationRecord,
): boolean {
  return mutation.entidadeTipo === "RDO";
}

/** Estados em que a edição parou de andar sozinha e espera uma decisão. */
const PRESA: ReadonlySet<OutboxMutationRecord["status"]> = new Set([
  "CONFLICT",
  "REJECTED",
]);

/**
 * A fila do RDO precisa estar em estado conhecido antes de receber a marcação.
 *
 * Enfileirar um apagamento por cima de uma edição ainda não confirmada faria as
 * duas subirem com a mesma baseVersion e uma delas seria recusada por conflito.
 * A cauda pendente é encadeada, exatamente como no ciclo de vida da obra.
 */
async function activeRdoMutations(
  rdoId: string,
  authoritativeVersion: number,
): Promise<CanonicalOutboxMutationRecord[]> {
  const mutations = (await (await getCortexDb()).getAllFromIndex(
    "outbox_mutations",
    "by-entity-id",
    rdoId,
  )).filter(isRdoMutation);

  if (mutations.some((mutation) => mutation.status === "CONFLICT")) {
    throw new Error(
      "O RDO possui um conflito pendente de reconciliação.",
    );
  }
  if (mutations.some((mutation) => mutation.status === "REJECTED")) {
    throw new Error(
      "O RDO possui uma alteração recusada. Resolva a revisão antes de apagar ou recuperar.",
    );
  }

  const active = mutations.filter((mutation) =>
    ["PENDING", "ERROR", "SYNCING"].includes(mutation.status)
  );
  if (active.some((mutation) => mutation.status === "SYNCING")) {
    throw new Error(
      "O RDO está sendo sincronizado. Aguarde a confirmação e tente de novo.",
    );
  }
  if (active.some((mutation) => mutation.status === "ERROR")) {
    throw new Error(
      "O RDO possui uma alteração com erro não aplicado. Sincronize antes de apagar ou recuperar.",
    );
  }

  const canonical = active.map((mutation) => {
    if (!isCanonicalOutboxMutation(mutation)) {
      throw new Error(
        "O RDO possui uma alteração legada que exige revisão.",
      );
    }
    return mutation;
  });
  if (canonical.length === 0) return [];

  const ordered = [...canonical].sort(
    (left, right) => (left.baseVersion ?? -1) - (right.baseVersion ?? -1),
  );
  // Uma criação ainda na fila não tem baseVersion; o RDO nem existe do outro
  // lado, e marcá-lo como apagado não teria alvo.
  if (ordered.some((mutation) => mutation.baseVersion === null)) {
    throw new Error(
      "O RDO ainda não foi aceito pelo servidor. Sincronize antes de apagar.",
    );
  }
  const encadeamentoIntacto = ordered.every((mutation, index) =>
    mutation.baseVersion === authoritativeVersion + index
  );
  if (!encadeamentoIntacto) {
    throw new Error(
      "A fila local do RDO está ambígua e precisa de revisão antes de receber outra alteração.",
    );
  }
  return ordered;
}

function requiredBaseVersion(rdo: LocalRdoRecord): number {
  if (
    !Number.isSafeInteger(rdo.versaoEntidade) ||
    (rdo.versaoEntidade ?? -1) < 0
  ) {
    throw new Error(
      "O RDO precisa de uma versão autoritativa antes de ser apagado ou recuperado.",
    );
  }
  return rdo.versaoEntidade as number;
}

function rdoTransportSnapshot(
  rdo: LocalRdoRecord,
): Record<string, unknown> {
  return {
    id: rdo.id,
    rdoId: rdo.id,
    obraId: rdo.obraId,
    programacaoId: rdo.programacaoId,
    numeroRdo: rdo.numeroRdo,
    dataRdo: rdo.dataRdo,
    status: rdo.statusRdo,
    canceladoEm: rdo.canceladoEm ?? null,
  };
}

async function queueRdoLifecycleMutation(
  existing: LocalRdoRecord,
  next: LocalRdoRecord,
  contract: {
    operation: "DELETE" | "TRANSITION";
    transportOperation: "CANCELAR_RDO" | "RESTAURAR_RDO";
    eventType: "RDO_CANCELADO" | "RDO_RESTAURADO";
  },
): Promise<LocalRdoRecord> {
  const identity = await rdoMutationIdentity();
  const authoritativeVersion = requiredBaseVersion(existing);
  const pending = await activeRdoMutations(
    existing.id,
    authoritativeVersion,
  );
  const tail = pending.at(-1) ?? null;

  await commitLocalMutation({
    ...identity,
    clientMutationId: crypto.randomUUID(),
    obraId: existing.obraId,
    entityType: "RDO",
    entityId: existing.id,
    entityName: existing.numeroRdo.trim() || null,
    operation: contract.operation,
    transportOperation: contract.transportOperation,
    baseVersion: tail === null
      ? authoritativeVersion
      : (tail.baseVersion as number) + 1,
    occurredAt: next.updatedAt,
    previousSnapshot: rdoTransportSnapshot(existing),
    nextSnapshot: rdoTransportSnapshot(next),
    principalSnapshot: { ...next },
    expectedPrincipalSnapshot: { ...existing },
    expectedActiveMutationIds: pending.map(
      (mutation) => mutation.clientMutationId,
    ),
    eventType: contract.eventType,
    colaboradorId: identity.userId,
    relatedEntities: [{ tipo: "OBRA", id: existing.obraId }],
    causationId: tail?.clientMutationId ?? null,
    dependsOnMutationIds: tail ? [tail.clientMutationId] : [],
    write: () => [{ store: "rdos", value: next, principal: true }],
  });
  return next;
}

const LOJAS_FILHAS_DO_RDO = [
  "rdoMaoObra",
  "rdoEquipamentos",
  "rdoMateriais",
  "rdoControlesGeometricos",
] as const;

/**
 * Um RDO só existe do outro lado depois que o servidor lhe atribui versão.
 *
 * Enquanto isso não acontece não há o que marcar como apagado: a marcação vive
 * numa linha que ainda não foi criada. É o caso mais comum de querer apagar —
 * o lançamento errado, percebido em campo, antes de qualquer sincronização.
 */
export function rdoConhecidoPeloServidor(rdo: LocalRdoRecord): boolean {
  return Number.isSafeInteger(rdo.versaoEntidade) &&
    (rdo.versaoEntidade ?? -1) >= 0;
}

/**
 * Descarta um RDO que o servidor nunca aceitou.
 *
 * Aqui apagar é apagar mesmo: nada foi publicado, nada precisa ser preservado
 * para auditoria de ninguém além de quem digitou. Some o lançamento, seus
 * filhos, seus anexos, seus eventos e — decisivo — a criação que ainda
 * esperava na fila. Sem tirar a criação da fila, a sincronização seguinte
 * ressuscitaria no servidor exatamente o RDO que acabou de ser descartado.
 *
 * Um envio em curso interrompe: dele não se sabe se o servidor aceitou, e
 * descartar às cegas criaria um registro órfão do outro lado.
 */
export async function descartarRdoLocalNaoSincronizado(
  existing: LocalRdoRecord,
): Promise<void> {
  if (rdoConhecidoPeloServidor(existing)) {
    throw new Error(
      "Este RDO já foi aceito pelo servidor. Use apagar, que o mantém recuperável.",
    );
  }
  await limparRastroLocalDoRdo(existing.id);
}

/**
 * Tira do aparelho tudo que pertence a um RDO: a fila, os eventos, os anexos,
 * as lojas filhas e o próprio registro.
 *
 * <p>É a metade que o console do banco não faz. Apagar só no servidor deixa a
 * fila daqui apontando para linhas que não existem mais, e no ciclo seguinte
 * cada mutação órfã vira um conflito novo — trocar um problema por outro.
 *
 * <p>Recusa enquanto houver mutação `SYNCING`: apagar o registro debaixo de um
 * envio em curso deixaria a resposta do servidor chegando para um RDO que já
 * não existe aqui.
 */
export async function limparRastroLocalDoRdo(rdoId: string): Promise<void> {
  const database = await getCortexDb();
  const transaction = database.transaction(
    [
      "rdos",
      "outbox_mutations",
      "operational_events",
      "rdo_attachments",
      ...LOJAS_FILHAS_DO_RDO,
    ],
    "readwrite",
  );

  const outbox = transaction.objectStore("outbox_mutations");
  const mutacoes = (
    await outbox.index("by-entity-id").getAll(rdoId)
  ).filter(isRdoMutation);
  if (mutacoes.some((mutacao) => mutacao.status === "SYNCING")) {
    // Abortar rejeita `done`; sem este consumo a rejeição vaza como erro não
    // tratado e some no console de quem estiver depurando outra coisa.
    void transaction.done.catch(() => undefined);
    transaction.abort();
    throw new Error(
      "Este RDO está sendo enviado agora. Aguarde a confirmação para decidir o que fazer com ele.",
    );
  }
  for (const mutacao of mutacoes) {
    await outbox.delete(mutacao.clientMutationId);
  }

  const eventos = transaction.objectStore("operational_events");
  for (const evento of await eventos.index("by-rdo-id").getAll(rdoId)) {
    await eventos.delete(evento.id);
  }

  const anexos = transaction.objectStore("rdo_attachments");
  for (const anexo of await anexos.index("by-rdo-id").getAll(rdoId)) {
    await anexos.delete(anexo.id);
  }

  for (const nome of LOJAS_FILHAS_DO_RDO) {
    const loja = transaction.objectStore(nome);
    for (const filho of await loja.index("by-rdo-id").getAll(rdoId)) {
      await loja.delete(filho.id);
    }
  }

  await transaction.objectStore("rdos").delete(rdoId);
  await transaction.done;
}

/**
 * Apagar o RDO supera as edições locais que ficaram presas nele.
 *
 * A recusa era circular: o botão dizia "resolva o conflito antes de apagar",
 * mas o conflito é justamente o motivo de querer apagar, e quando os dois lados
 * mexem no mesmo campo não existe fusão a propor. O registro ficava para
 * sempre, com a tarja vermelha e nenhuma saída — o beco que este produto não
 * pode ter.
 *
 * Quem decide que o registro inteiro sai já decidiu que a edição parada não
 * tem mais objeto. Então ela deixa a fila e o evento fica na Memória marcado
 * como descartado: some a intenção de escrita, nunca o rastro de que ela
 * existiu.
 *
 * A versão importa tanto quanto a fila. Um conflito significa que o servidor
 * andou; enfileirar o apagamento contra a versão que este aparelho conhecia
 * criaria um segundo conflito na hora — trocar um impasse por outro. Por isso
 * a versão do servidor, que o próprio conflito informa, é adotada antes.
 */
async function liberarEdicoesPresasParaApagar(
  existing: LocalRdoRecord,
): Promise<LocalRdoRecord> {
  const database = await getCortexDb();
  const presas = (await database.getAllFromIndex(
    "outbox_mutations",
    "by-entity-id",
    existing.id,
  ))
    .filter(isRdoMutation)
    .filter((mutacao) => PRESA.has(mutacao.status));

  if (presas.length === 0) {
    return existing;
  }

  const versaoDoServidor = presas
    .map(conflictServerVersion)
    .filter((versao): versao is number => versao !== null)
    .reduce<number | null>(
      (maior, versao) => (maior === null || versao > maior ? versao : maior),
      null,
    );

  const timestamp = new Date().toISOString();
  const transaction = database.transaction(
    ["rdos", "outbox_mutations", "operational_events"],
    "readwrite",
  );
  const outbox = transaction.objectStore("outbox_mutations");
  const eventos = transaction.objectStore("operational_events");

  for (const mutacao of presas) {
    const atual = await outbox.get(mutacao.clientMutationId);
    /*
     * Outra aba pode ter mexido na linha entre a leitura e esta transação. O
     * que decide é se ela continua presa, não em qual dos dois estados: uma
     * reconciliação concorrente move a original de CONFLICT para REJECTED, e
     * exigir o mesmo estado de antes a preservaria — trazendo de volta o beco
     * sem saída por uma corrida. Se voltou a andar (PENDING ou SYNCING), aí
     * sim ela não é nossa para apagar, e a checagem seguinte decide o que
     * fazer com ela.
     */
    if (!atual || !PRESA.has(atual.status)) {
      continue;
    }
    await outbox.delete(mutacao.clientMutationId);
    for (
      const evento of await eventos
        .index("by-client-mutation-id")
        .getAll(mutacao.clientMutationId)
    ) {
      await eventos.put({
        ...evento,
        result: "DISCARDED",
        syncStatus: "LOCAL_ONLY",
        errorCategory: "SUPERSEDED_BY_RDO_DELETION",
      });
    }
  }

  const rdos = transaction.objectStore("rdos");
  const gravado = await rdos.get(existing.id);
  if (!gravado) {
    await transaction.done;
    throw new Error("O RDO não está mais neste dispositivo.");
  }
  const atualizado: LocalRdoRecord = {
    ...gravado,
    versaoEntidade:
      versaoDoServidor !== null &&
        versaoDoServidor > (gravado.versaoEntidade ?? -1)
        ? versaoDoServidor
        : gravado.versaoEntidade,
    syncStatus: "PENDING_SYNC",
    updatedAt: timestamp,
  };
  await rdos.put(atualizado);
  await transaction.done;
  return atualizado;
}

/**
 * Apaga o RDO sem apagar o que ele registrou.
 *
 * O registro sai da operação — receita, PDOR, encadeamento do RDO seguinte e
 * relatórios já ignoram o que está cancelado — mas continua na base, com toda
 * a mão de obra, equipamento e medição que carregava, e pode voltar.
 */
export async function queueCancelRdo(
  existing: LocalRdoRecord,
): Promise<LocalRdoRecord> {
  if (existing.canceladoEm) {
    throw new Error("O RDO já está apagado.");
  }
  const atual = await liberarEdicoesPresasParaApagar(existing);
  const timestamp = new Date().toISOString();
  return queueRdoLifecycleMutation(
    atual,
    {
      ...atual,
      statusRdo: "CANCELADA",
      canceladoEm: timestamp,
      syncStatus: "PENDING_SYNC",
      updatedAt: timestamp,
    },
    {
      operation: "DELETE",
      transportOperation: "CANCELAR_RDO",
      eventType: "RDO_CANCELADO",
    },
  );
}

/**
 * Devolve o RDO à operação.
 *
 * O estado de volta não é escolhido aqui: o servidor o deriva de `enviado_em`,
 * que o apagamento não toca. O palpite local vale só até a confirmação chegar.
 */
export async function queueRestoreRdo(
  existing: LocalRdoRecord,
): Promise<LocalRdoRecord> {
  if (!existing.canceladoEm) {
    throw new Error("O RDO não está apagado.");
  }
  const timestamp = new Date().toISOString();
  return queueRdoLifecycleMutation(
    existing,
    {
      ...existing,
      statusRdo: "RASCUNHO",
      canceladoEm: null,
      syncStatus: "PENDING_SYNC",
      updatedAt: timestamp,
    },
    {
      operation: "TRANSITION",
      transportOperation: "RESTAURAR_RDO",
      eventType: "RDO_RESTAURADO",
    },
  );
}

/**
 * A frase que o servidor mandou, quando ela existe.
 *
 * <p>O corpo vem como JSON de erro do Spring na maioria dos casos e como texto
 * cru em alguns; as duas formas carregam a explicação, e é a explicação que
 * diz à pessoa o que fazer diferente.
 */
function motivoDaRecusa(corpo: string): string {
  try {
    return String(JSON.parse(corpo)?.message ?? "").trim();
  } catch {
    return corpo.trim();
  }
}

/**
 * Apaga o RDO no servidor e limpa o rastro deste aparelho, nessa ordem.
 *
 * <p>A ordem é a única que não deixa lixo: primeiro o servidor, que pode
 * recusar — RDO que serve de base para outro, ou com medição já virada em
 * dinheiro —, e só depois o local. Ao contrário, uma recusa do servidor
 * deixaria o RDO apagado aqui e vivo lá, e a próxima hidratação o traria de
 * volta sem explicação.
 *
 * <p>Existe porque a alternativa que estava sendo cogitada era abrir o console
 * do banco, e apagar por fora quebra a sincronização: o servidor perde linhas
 * que a fila daqui ainda referencia.
 */
export async function apagarRdoDefinitivamente(
  existing: LocalRdoRecord,
): Promise<void> {
  if (rdoConhecidoPeloServidor(existing)) {
    const response = await apiFetch(
      `/rdos/${encodeURIComponent(existing.id)}`,
      { method: "DELETE" },
    );
    if (!response.ok) {
      throw new Error(
        // A mensagem do servidor é a que explica o motivo — base de outro RDO,
        // serviço já medido. Trocá-la por uma genérica apagaria a razão.
        motivoDaRecusa(await response.text().catch(() => "")) ||
          "Não foi possível apagar este RDO no servidor.",
      );
    }
  }

  await limparRastroLocalDoRdo(existing.id);
}
