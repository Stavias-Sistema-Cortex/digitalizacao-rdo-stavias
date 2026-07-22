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
  const base = createEmptyRdo();
  base.id = options.draftId ?? crypto.randomUUID();
  const draft = applyRdoCreationContext(
    base,
    context,
    options.workforceIdFactory,
  );
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
