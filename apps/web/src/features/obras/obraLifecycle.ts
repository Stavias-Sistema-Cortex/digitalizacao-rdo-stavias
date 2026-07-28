import { getCortexDb } from "../../lib/db/cortexDb";
import type {
  CanonicalOutboxMutationRecord,
  ObraLocalRecord,
} from "../../lib/db/db.types";
import { getSyncState, updateSyncState } from "../../lib/db/syncStateRepository";
import { commitLocalMutation } from "../../lib/sync/localMutationCoordinator";
import { isCanonicalOutboxMutation } from "../../lib/sync/mutationEnvelope";
import { effectiveObraMutations } from "../../lib/sync/syncStorage";
import { getSession } from "../auth/authSession";

const UUID_PATTERN =
  /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;

interface ObraMutationIdentity {
  userId: string;
  deviceId: string;
}

export interface UpdateObraInput {
  codigoContrato: string;
  codigoInterno: string | null;
  nome: string;
  cliente: string | null;
  descricao: string | null;
  cidade: string | null;
  uf: string | null;
  rodovia: string | null;
  fonteArquivo: string | null;
  observacoes: string | null;
}

async function obraMutationIdentity(): Promise<ObraMutationIdentity> {
  const session = getSession();
  if (
    !session ||
    session.papelAcesso !== "ALFA" ||
    !session.escopoGlobal
  ) {
    throw new Error(
      "Alterações de obra exigem uma sessão Alfa com escopo global.",
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

async function activeObraMutations(
  obraId: string,
  authoritativeVersion: number,
): Promise<CanonicalOutboxMutationRecord[]> {
  const mutations = await (await getCortexDb()).getAllFromIndex(
    "outbox_mutations",
    "by-entity-id",
    obraId,
  );
  const entityMutations = effectiveObraMutations(
    mutations.filter(
      (mutation) => mutation.entidadeTipo === "OBRA",
    ),
  );
  const conflict = entityMutations.find(
    (mutation) =>
      mutation.status === "CONFLICT",
  );
  if (conflict) {
    throw new Error(
      "A obra possui um conflito pendente de reconciliação.",
    );
  }
  const rejected = entityMutations.find(
    (mutation) => mutation.status === "REJECTED",
  );
  if (rejected) {
    throw new Error(
      "A obra possui uma mutação rejeitada não aplicada. Faça a recuperação explícita antes de editar novamente.",
    );
  }
  const active = entityMutations.filter((mutation) =>
    ["PENDING", "ERROR", "SYNCING"].includes(mutation.status)
  );
  const canonical = active.map((mutation) => {
    if (!isCanonicalOutboxMutation(mutation)) {
      throw new Error(
        "A obra possui uma mutação legada que exige revisão.",
      );
    }
    return mutation;
  });
  if (canonical.some((mutation) => mutation.status === "ERROR")) {
    throw new Error(
      "A obra possui uma mutação com erro não aplicado. Faça a recuperação explícita antes de editar novamente.",
    );
  }
  if (canonical.some((mutation) => mutation.status === "SYNCING")) {
    throw new Error(
      "A obra está sendo sincronizada. Aguarde a confirmação antes de editar novamente.",
    );
  }
  const pending = canonical.filter(
    (mutation) => mutation.status === "PENDING",
  );
  if (pending.length === 0) return [];

  const ordered = [...pending].sort(
    (left, right) =>
      (left.baseVersion ?? -1) - (right.baseVersion ?? -1),
  );
  const ids = new Set(ordered.map((mutation) => mutation.clientMutationId));
  const referencedPredecessors = new Set<string>();
  for (const mutation of ordered) {
    for (const dependencyId of mutation.dependsOnMutationIds ?? []) {
      if (ids.has(dependencyId)) referencedPredecessors.add(dependencyId);
    }
    if (mutation.causationId && ids.has(mutation.causationId)) {
      referencedPredecessors.add(mutation.causationId);
    }
  }
  const tails = ordered.filter(
    (mutation) => !referencedPredecessors.has(mutation.clientMutationId),
  );
  const hasValidLinearChain = ordered.every((mutation, index) => {
    if (mutation.baseVersion !== authoritativeVersion + index) return false;
    if (index === 0) return true;
    const predecessor = ordered[index - 1];
    return mutation.causationId === predecessor.clientMutationId &&
      (mutation.dependsOnMutationIds ?? []).includes(
        predecessor.clientMutationId,
      );
  });
  if (
    tails.length !== 1 ||
    tails[0] !== ordered.at(-1) ||
    !hasValidLinearChain
  ) {
    throw new Error(
      "A cadeia local da obra está ambígua e precisa de revisão antes de receber outra edição.",
    );
  }
  return ordered;
}

function requiredBaseVersion(obra: ObraLocalRecord): number {
  if (
    !Number.isSafeInteger(obra.versaoEntidade) ||
    (obra.versaoEntidade ?? -1) < 0
  ) {
    throw new Error(
      "A obra precisa de uma versão autoritativa antes de ser alterada.",
    );
  }
  return obra.versaoEntidade as number;
}

function trimmedOrNull(value: string | null | undefined): string | null {
  return value?.trim() || null;
}

function obraTransportSnapshot(
  obra: ObraLocalRecord,
): Record<string, unknown> {
  return {
    id: obra.id,
    obraId: obra.id,
    codigoContrato: obra.codigoContrato,
    codigoInterno: obra.codigoInterno ?? null,
    nome: obra.nome,
    cliente: obra.cliente,
    descricao: obra.descricao ?? null,
    cidade: obra.cidade,
    uf: obra.uf,
    rodovia: obra.rodovia,
    fonteArquivo: obra.fonteArquivo ?? null,
    status: obra.status,
    observacoes: obra.observacoes,
    latitude: obra.latitude,
    longitude: obra.longitude,
    valorContratual: obra.valorContratual,
    arquivadoEm: obra.arquivadoEm,
  };
}

async function queueObraMutation(
  existing: ObraLocalRecord,
  next: ObraLocalRecord,
  contract: {
    operation: "UPDATE" | "DELETE" | "TRANSITION";
    transportOperation:
      | "ATUALIZAR_OBRA"
      | "DESATIVAR_OBRA"
      | "ARQUIVAR_OBRA"
      | "RESTAURAR_OBRA";
    eventType:
      | "OBRA_ATUALIZADA"
      | "OBRA_DESATIVADA"
      | "OBRA_ARQUIVADA"
      | "OBRA_RESTAURADA";
  },
): Promise<ObraLocalRecord> {
  const identity = await obraMutationIdentity();
  const authoritativeVersion = requiredBaseVersion(existing);
  const pending = await activeObraMutations(
    existing.id,
    authoritativeVersion,
  );
  const tail = pending.at(-1) ?? null;
  const clientMutationId = crypto.randomUUID();

  await commitLocalMutation({
    ...identity,
    clientMutationId,
    obraId: existing.id,
    entityType: "OBRA",
    entityId: existing.id,
    entityName: next.nome,
    operation: contract.operation,
    transportOperation: contract.transportOperation,
    baseVersion: tail === null
      ? authoritativeVersion
      : (tail.baseVersion as number) + 1,
    occurredAt: next.updatedAt,
    previousSnapshot: obraTransportSnapshot(existing),
    nextSnapshot: obraTransportSnapshot(next),
    principalSnapshot: { ...next },
    expectedPrincipalSnapshot: { ...existing },
    expectedActiveMutationIds: pending.map(
      (mutation) => mutation.clientMutationId,
    ),
    eventType: contract.eventType,
    colaboradorId: identity.userId,
    causationId: tail?.clientMutationId ?? null,
    dependsOnMutationIds: tail ? [tail.clientMutationId] : [],
    write: () => [{
      store: "obras",
      value: next,
      principal: true,
    }],
  });
  return next;
}

function assertNotArchived(obra: ObraLocalRecord): void {
  if (obra.arquivadoEm) {
    throw new Error(
      "Obra arquivada só pode ser restaurada.",
    );
  }
}

export async function queueUpdateObra(
  existing: ObraLocalRecord,
  input: UpdateObraInput,
): Promise<ObraLocalRecord> {
  assertNotArchived(existing);
  const timestamp = new Date().toISOString();
  const codigoContrato = input.codigoContrato.trim();
  const nome = input.nome.trim();
  if (!codigoContrato || !nome) {
    throw new Error("Código do contrato e nome da obra são obrigatórios.");
  }
  const next: ObraLocalRecord = {
    ...existing,
    codigoContrato,
    codigoInterno: trimmedOrNull(input.codigoInterno),
    nome,
    cliente: trimmedOrNull(input.cliente),
    descricao: trimmedOrNull(input.descricao),
    cidade: trimmedOrNull(input.cidade),
    uf: trimmedOrNull(input.uf)?.toUpperCase() ?? null,
    rodovia: trimmedOrNull(input.rodovia),
    fonteArquivo: trimmedOrNull(input.fonteArquivo),
    observacoes: trimmedOrNull(input.observacoes),
    syncStatus: "PENDING_SYNC",
    ultimoErro: null,
    updatedAt: timestamp,
  };
  return queueObraMutation(existing, next, {
    operation: "UPDATE",
    transportOperation: "ATUALIZAR_OBRA",
    eventType: "OBRA_ATUALIZADA",
  });
}

export async function queueDeactivateObra(
  existing: ObraLocalRecord,
): Promise<ObraLocalRecord> {
  assertNotArchived(existing);
  const next: ObraLocalRecord = {
    ...existing,
    status: "INATIVA",
    syncStatus: "PENDING_SYNC",
    ultimoErro: null,
    updatedAt: new Date().toISOString(),
  };
  return queueObraMutation(existing, next, {
    operation: "TRANSITION",
    transportOperation: "DESATIVAR_OBRA",
    eventType: "OBRA_DESATIVADA",
  });
}

export async function queueArchiveObra(
  existing: ObraLocalRecord,
): Promise<ObraLocalRecord> {
  assertNotArchived(existing);
  const timestamp = new Date().toISOString();
  const next: ObraLocalRecord = {
    ...existing,
    arquivadoEm: timestamp,
    syncStatus: "PENDING_SYNC",
    ultimoErro: null,
    updatedAt: timestamp,
  };
  return queueObraMutation(existing, next, {
    operation: "DELETE",
    transportOperation: "ARQUIVAR_OBRA",
    eventType: "OBRA_ARQUIVADA",
  });
}

export async function queueRestoreObra(
  existing: ObraLocalRecord,
): Promise<ObraLocalRecord> {
  if (!existing.arquivadoEm) {
    throw new Error("A obra não está arquivada.");
  }
  const next: ObraLocalRecord = {
    ...existing,
    arquivadoEm: null,
    syncStatus: "PENDING_SYNC",
    ultimoErro: null,
    updatedAt: new Date().toISOString(),
  };
  return queueObraMutation(existing, next, {
    operation: "TRANSITION",
    transportOperation: "RESTAURAR_OBRA",
    eventType: "OBRA_RESTAURADA",
  });
}
