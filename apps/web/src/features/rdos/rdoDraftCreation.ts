import {
  rdoDraftFromLocalRecord,
  saveNewRdoDraftAtomically,
} from "../../lib/db/localRdoService";
import type { CanonicalOutboxMutationRecord } from "../../lib/db/db.types";
import { createEmptyRdo } from "./createEmptyRdo";
import {
  applyRdoCreationContext,
  isRdoCreationContextComplete,
} from "./rdoCreationContext";
import type { RdoDraft } from "./rdo.types";
import type { RdoCreationContextLookup } from "./rdoLookupApi";

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
    numeroRdo: imported.numeroRdo.trim()
      ? imported.numeroRdo
      : contextual.numeroRdo,
    maoObra: [
      ...structuredClone(imported.maoObra),
      ...contextual.maoObra.filter(
        (row) => !importedCollaboratorIds.has(row.colaboradorId),
      ),
    ],
    syncStatus: "LOCAL_ONLY",
  };
}
