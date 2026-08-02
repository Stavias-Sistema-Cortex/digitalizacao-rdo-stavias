import { getCortexDb } from "../../lib/db/cortexDb";
import type {
  CanonicalOutboxMutationRecord,
  LocalRdoRecord,
  OutboxMutationRecord,
} from "../../lib/db/db.types";
import { getSyncState, updateSyncState } from "../../lib/db/syncStateRepository";
import { commitLocalMutation } from "../../lib/sync/localMutationCoordinator";
import { isCanonicalOutboxMutation } from "../../lib/sync/mutationEnvelope";
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
  const timestamp = new Date().toISOString();
  return queueRdoLifecycleMutation(
    existing,
    {
      ...existing,
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
