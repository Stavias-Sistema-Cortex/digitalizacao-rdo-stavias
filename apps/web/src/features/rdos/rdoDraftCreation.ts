import {
  motivoDoReciboRecusado,
  rdoDraftFromLocalRecord,
  saveLocalPendingRdoDraftAtomically,
  saveNewRdoDraftAtomically,
} from "../../lib/db/localRdoService";
import { getCachedRdoCreationContext } from "./rdoCreationContextRepository";
import { getSession } from "../auth/authSession";
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
  /**
   * De onde veio o rascunho-base.
   *
   * <p>`IMPORTACAO` é o único que existia, e por isso é o padrão: o rascunho
   * nasceu de um documento lido, e a criação carimba a evidência dessa origem.
   * `CLONE` nasceu de outro RDO deste mesmo Córtex, e carimbá-lo como importado
   * mentiria sobre a procedência — o RDO passaria a alegar um documento que
   * nunca existiu, numa trilha que existe justamente para ser confiável.
   */
  baseDraftOrigin?: "IMPORTACAO" | "CLONE";
}

export interface CreatedRdoDraft {
  draft: RdoDraft;
  mutation: CanonicalOutboxMutationRecord;
}

/**
 * Quem está preenchendo é quem está com a sessão aberta.
 *
 * <p>O campo nascia vazio e cada apontador digitava o próprio nome todo dia,
 * a cada RDO. O dado já está no aparelho — a sessão é local, então isto
 * funciona sem rede — e continua editável: quem preenche pelo colega corrige
 * o nome, e quem quiser apagar apaga.
 */
function comPreenchidoPor(draft: RdoDraft): RdoDraft {
  if (draft.preenchidoPor.trim()) {
    return draft;
  }
  const nome = getSession()?.nome?.trim() ?? "";
  return nome ? { ...draft, preenchidoPor: nome } : draft;
}

/**
 * O que impediria a criação, apurado antes de oferecer o botão.
 *
 * <p>A tela habilitava "Criar rascunho" com `isRdoCreationContextComplete`,
 * que confere apenas a cobertura, enquanto a gravação exigia mais de vinte
 * condições sobre o recibo guardado. Eram critérios diferentes para a mesma
 * decisão, e a diferença aparecia como um botão que convidava a clicar para
 * então recusar.
 *
 * <p>Aqui o rascunho prospectivo é montado exatamente como na criação — a
 * mesma função, os mesmos argumentos — e conferido contra o mesmo recibo. O
 * que esta função aprova, a gravação aceita; o que ela recusa, ela sabe
 * explicar.
 */
export async function motivoParaNaoCriarRdo(
  context: RdoCreationContextLookup,
  options: CreateRdoDraftOptions = {},
): Promise<string | null> {
  if (!isRdoCreationContextComplete(context)) {
    return "O contexto da obra está parcial e não permite criar o rascunho.";
  }
  let prospectivo: RdoDraft;
  try {
    const base = options.baseDraft
      ? structuredClone(options.baseDraft)
      : createEmptyRdo();
    base.id = options.draftId ?? base.id ?? crypto.randomUUID();
    prospectivo = applyRdoCreationContext(
      base,
      context,
      options.workforceIdFactory,
    );
  } catch (falha: unknown) {
    return falha instanceof Error
      ? falha.message
      : "O contexto da obra não permite montar o rascunho.";
  }
  const guardado = await getCachedRdoCreationContext(
    prospectivo.obraId,
    prospectivo.dataRdo,
  );
  if (!guardado) {
    return "O contexto desta obra e data não está guardado neste dispositivo."
      + " Recarregue o contexto antes de criar.";
  }
  return motivoDoReciboRecusado(
    prospectivo,
    guardado as unknown as Parameters<typeof motivoDoReciboRecusado>[1],
  );
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
  // O clone não passa pela fusão de evidência: applyRdoCreationContext já
  // espalhou o rascunho-base sobre o contextual, então tudo o que o clone
  // trazia já está aqui — e a fusão, além de redundante, carimbaria uma
  // evidência de importação que não corresponde a documento nenhum.
  const draft = comPreenchidoPor(
    options.baseDraft && options.baseDraftOrigin !== "CLONE"
      ? mergeImportedEvidence(contextualDraft, options.baseDraft)
      : contextualDraft,
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
  // Mesma razão do caminho online: clone não é importação, e o contexto local
  // pendente já espalhou o rascunho-base.
  const draft = comPreenchidoPor(
    options.baseDraft && options.baseDraftOrigin !== "CLONE"
      ? mergeLocalPendingImportedEvidence(contextualDraft, options.baseDraft)
      : contextualDraft,
  );
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
    previousRdoNumber: contextual.previousRdoNumber,
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
    previousRdoNumber: contextual.previousRdoNumber,
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
