import {
  rdoDraftFromLocalRecord,
  saveLocalPendingRdoDraftAtomically,
  saveNewRdoDraftAtomically,
} from "../../lib/db/localRdoService";
import type { CanonicalOutboxMutationRecord } from "../../lib/db/db.types";
import { createEmptyRdo } from "./createEmptyRdo";
import {
  applyLocalPendingRdoCreationContext,
  applyRdoCreationContext,
  isRdoCreationContextComplete,
} from "./rdoCreationContext";
import type { RdoDraft } from "./rdo.types";
import type {
  RdoCreationContextLookup,
  RdoLocalPendingCreationContextLookup,
} from "./rdoLookupApi";

export interface CreateRdoDraftOptions {
  draftId?: string;
  occurredAt?: string;
  workforceIdFactory?: () => string;
  baseDraft?: RdoDraft;
}

export interface CreatedRdoDraft {
  draft: RdoDraft;
  mutation: CanonicalOutboxMutationRecord;
}

export async function createAndPersistRdoDraft(
  context: RdoCreationContextLookup,
  options: CreateRdoDraftOptions = {},
): Promise<CreatedRdoDraft> {
  if (!isRdoCreationContextComplete(context)) {
    throw new Error(
      "O contexto da obra está parcial e não permite criar o rascunho.",
    );
  }
  const base = options.baseDraft
    ? structuredClone(options.baseDraft)
    : createEmptyRdo();
  base.id = options.draftId ?? base.id ?? crypto.randomUUID();
  const contextualDraft = applyRdoCreationContext(
    base,
    context,
    options.workforceIdFactory,
  );
  const draft = options.baseDraft
    ? mergeImportedEvidence(contextualDraft, options.baseDraft)
    : contextualDraft;
  const persisted = await saveNewRdoDraftAtomically(draft, {
    occurredAt: options.occurredAt,
  });
  if (persisted.mutation.schemaVersion !== 13) {
    throw new Error("A criação do RDO não gerou uma mutação canônica.");
  }
  return {
    draft: rdoDraftFromLocalRecord(persisted.rdo),
    mutation: persisted.mutation,
  };
}

export async function createAndPersistLocalPendingRdoDraft(
  context: RdoLocalPendingCreationContextLookup,
  options: CreateRdoDraftOptions = {},
): Promise<CreatedRdoDraft> {
  const base = options.baseDraft
    ? structuredClone(options.baseDraft)
    : createEmptyRdo();
  base.id = options.draftId ?? base.id ?? crypto.randomUUID();
  const contextualDraft = applyLocalPendingRdoCreationContext(
    base,
    context,
    options.workforceIdFactory,
  );
  const draft = options.baseDraft
    ? mergeLocalPendingImportedEvidence(contextualDraft, options.baseDraft)
    : contextualDraft;
  const persisted = await saveLocalPendingRdoDraftAtomically(draft, {
    occurredAt: options.occurredAt,
  });
  if (
    persisted.mutation.schemaVersion !== 13 ||
    persisted.mutation.blockedReason !== "RDO_CREATION_CONTEXT_REQUIRED"
  ) {
    throw new Error(
      "A criação local do RDO não gerou uma mutação canônica bloqueada.",
    );
  }
  return {
    draft: rdoDraftFromLocalRecord(persisted.rdo),
    mutation: persisted.mutation,
  };
}

function mergeLocalPendingImportedEvidence(
  contextual: RdoDraft,
  imported: RdoDraft,
): RdoDraft {
  const importedCollaboratorIds = new Set(
    imported.maoObra
      .map((row) => row.colaboradorId.trim())
      .filter(Boolean),
  );
  return {
    ...contextual,
    ...structuredClone(imported),
    id: contextual.id,
    obraId: contextual.obraId,
    dataRdo: contextual.dataRdo,
    programacaoId: contextual.programacaoId,
    previousRdoId: contextual.previousRdoId,
    creationContextVersion: null,
    apontadorColaboradorId: "",
    cliente: contextual.cliente,
    contrato: contextual.contrato,
    rodovia: contextual.rodovia,
    cidade: contextual.cidade,
    uf: contextual.uf,
    numeroRdo: contextual.numeroRdo,
    maoObra: [
      ...structuredClone(imported.maoObra),
      ...contextual.maoObra.filter(
        (row) => !importedCollaboratorIds.has(row.colaboradorId),
      ),
    ],
    importEvidence: imported.importEvidence,
    syncStatus: "LOCAL_PENDING",
  };
}

function mergeImportedEvidence(
  contextual: RdoDraft,
  imported: RdoDraft,
): RdoDraft {
  const importedCollaboratorIds = new Set(
    imported.maoObra
      .map((row) => row.colaboradorId.trim())
      .filter(Boolean),
  );
  return {
    ...contextual,
    ...structuredClone(imported),
    id: contextual.id,
    obraId: contextual.obraId,
    dataRdo: contextual.dataRdo,
    programacaoId: contextual.programacaoId,
    previousRdoId: contextual.previousRdoId,
    creationContextVersion: contextual.creationContextVersion,
    apontadorColaboradorId: "",
    cliente: contextual.cliente,
    contrato: contextual.contrato,
    rodovia: contextual.rodovia,
    cidade: contextual.cidade,
    uf: contextual.uf,
    numeroRdo: contextual.numeroRdo,
    maoObra: [
      ...structuredClone(imported.maoObra),
      ...contextual.maoObra.filter(
        (row) => !importedCollaboratorIds.has(row.colaboradorId),
      ),
    ],
    importEvidence: {
      source: "IMPORTED_DOCUMENT",
      rawWorksiteIdentity: imported.importEvidence?.rawWorksiteIdentity ?? {
        numeroRdo: imported.numeroRdo,
        obraId: imported.obraId,
        dataRdo: imported.dataRdo,
        cliente: imported.cliente,
        contrato: imported.contrato,
        rodovia: imported.rodovia,
        cidade: imported.cidade,
        uf: imported.uf,
      },
      boundContext: {
        obraId: contextual.obraId,
        dataRdo: contextual.dataRdo,
        receiptVersion: contextual.creationContextVersion!,
      },
    },
    syncStatus: "LOCAL_ONLY",
  };
}
