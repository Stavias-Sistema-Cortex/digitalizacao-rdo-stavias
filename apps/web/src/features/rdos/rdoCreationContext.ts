import type { MaoObraDraft, RdoDraft } from "./rdo.types";
import type {
  RdoContextCollaborator,
  RdoCreationContextLookup,
} from "./rdoLookupApi";
import {
  addAuthorizedWorkforceMember,
  carryForwardWorkforce,
} from "./rdoWorkforceCarryForward";

export const RDO_CONTEXT_OFFLINE_MISSING =
  "Contexto desta obra ainda não está disponível offline.";
export const RDO_WORKFORCE_CATALOG_OFFLINE_UNAVAILABLE =
  "Colaboradores autorizados desta obra não estão disponíveis offline.";

export type RdoContextTruthStatus =
  | "FRESH"
  | "STALE"
  | "PARTIAL"
  | "LOCAL_PENDING";

function validPreviousRdo(
  context: RdoCreationContextLookup,
): RdoCreationContextLookup["previousRdo"] {
  const previous = context.previousRdo;
  if (!previous || previous.dataRdo >= context.data) return null;
  if (context.provenance.previousRdoId !== previous.id) return null;
  return previous;
}

function complete(section: { status: string; complete: boolean; total: number; returned: number }) {
  return section.status === "COMPLETE" &&
    section.complete &&
    section.total >= 0 &&
    section.returned === section.total;
}

function explicitOptional(section: { status: string; complete: boolean; total: number; returned: number }) {
  return complete(section) ||
    (section.status === "NOT_CONFIGURED" &&
      section.complete === false &&
      section.total === 0 &&
      section.returned === 0);
}

export function isRdoCreationContextComplete(
  context: RdoCreationContextLookup,
): boolean {
  const { coverage } = context;
  return complete(coverage.previousWorkforce) &&
    complete(coverage.programacoes) &&
    complete(coverage.colaboradores) &&
    complete(coverage.equipamentos) &&
    explicitOptional(coverage.serviceCatalog) &&
    explicitOptional(coverage.priceCatalog);
}

export function applyRdoCreationContext(
  draft: RdoDraft,
  context: RdoCreationContextLookup,
  createId: () => string = () => crypto.randomUUID(),
): RdoDraft {
  if (
    context.obra.id !== context.provenance.worksiteId ||
    context.data !== context.provenance.selectedDate
  ) {
    throw new Error("Proveniência do contexto de criação inválida.");
  }
  const previous = validPreviousRdo(context);
  const previousWorkforce = previous
    ? context.previousWorkforce.filter(
        (item) => item.sourceRdoId === previous.id,
      )
    : [];

  return {
    ...draft,
    obraId: context.obra.id,
    dataRdo: context.data,
    numeroRdo: context.nextNumberSuggestion?.trim() ?? "",
    previousRdoId: previous?.id ?? "",
    creationContextVersion: context.provenance.receiptVersion,
    programacaoId: "",
    cliente: context.obra.cliente?.trim() ?? "",
    contrato: context.obra.codigoContrato?.trim() ?? "",
    rodovia: context.obra.rodovia?.trim() ?? "",
    cidade: context.obra.cidade?.trim() ?? "",
    uf: context.obra.uf?.trim() ?? "",
    maoObra: carryForwardWorkforce(
      previousWorkforce,
      context.colaboradores,
      createId,
    ),
    apontadorColaboradorId: "",
    apontadorRdo: "",
    syncStatus: "LOCAL_ONLY",
  };
}

export function addAuthorizedWorker(
  draft: RdoDraft,
  collaboratorId: string,
  catalog: readonly RdoContextCollaborator[],
  createId: () => string = () => crypto.randomUUID(),
): RdoDraft {
  return {
    ...draft,
    maoObra: addAuthorizedWorkforceMember(
      draft.maoObra,
      collaboratorId,
      catalog,
      createId,
    ),
  };
}

export function setRosterSelected(
  draft: RdoDraft,
  localId: string,
  selected: boolean,
): RdoDraft {
  const target = draft.maoObra.find((item) => item.localId === localId);
  if (!target) return draft;
  if (selected && target.availability === "UNAVAILABLE") {
    throw new Error("Colaborador indisponível não pode ser selecionado.");
  }
  const clearsApontador = !selected &&
    target.colaboradorId === draft.apontadorColaboradorId;
  return {
    ...draft,
    maoObra: draft.maoObra.map((item) =>
      item.localId === localId ? { ...item, selected } : item,
    ),
    apontadorColaboradorId: clearsApontador
      ? ""
      : draft.apontadorColaboradorId,
    apontadorRdo: clearsApontador ? "" : draft.apontadorRdo,
  };
}

export function setRosterApontador(
  draft: RdoDraft,
  collaboratorId: string,
): RdoDraft {
  if (!collaboratorId) {
    return { ...draft, apontadorColaboradorId: "", apontadorRdo: "" };
  }
  const row = draft.maoObra.find(
    (item) => item.colaboradorId === collaboratorId && item.selected,
  );
  if (!row) {
    throw new Error("O apontador deve estar selecionado na equipe do RDO.");
  }
  return {
    ...draft,
    apontadorColaboradorId: collaboratorId,
    apontadorRdo: row.nomeColaborador,
  };
}

export function contextPresentation(
  context: RdoCreationContextLookup,
  now = new Date(),
): {
  status: RdoContextTruthStatus;
  receiptVersion: number;
  sourceVersion: number;
  label: string;
} {
  let status: RdoContextTruthStatus;
  if (context.freshness.status === "LOCAL_PENDING") {
    status = "LOCAL_PENDING";
  } else if (!isRdoCreationContextComplete(context)) {
    status = "PARTIAL";
  } else if (
    !Number.isFinite(Date.parse(context.freshness.staleAfter)) ||
    now.getTime() > Date.parse(context.freshness.staleAfter)
  ) {
    status = "STALE";
  } else {
    status = "FRESH";
  }
  return {
    status,
    receiptVersion: context.provenance.receiptVersion,
    sourceVersion: context.provenance.sourceVersion,
    label:
      status === "FRESH"
        ? "Atualizado"
        : status === "LOCAL_PENDING"
          ? "Local pendente"
          : status === "STALE"
            ? "Desatualizado"
            : "Parcial",
  };
}

export function shouldApplyRemoteContext(input: {
  isExisting: boolean;
  requestedKey: string;
  currentKey: string;
  revisionAtRequest: number;
  currentRevision: number;
}): boolean {
  return !input.isExisting &&
    input.requestedKey === input.currentKey &&
    input.revisionAtRequest === input.currentRevision;
}

export function nextRosterFocusIndex(
  key: string,
  current: number,
  length: number,
): number {
  if (length <= 0) return -1;
  if (key === "Home") return 0;
  if (key === "End") return length - 1;
  if (key === "ArrowDown") return (current + 1) % length;
  if (key === "ArrowUp") return (current - 1 + length) % length;
  return Math.min(Math.max(current, 0), length - 1);
}

export function selectedWorkforce(
  rows: readonly MaoObraDraft[],
): MaoObraDraft[] {
  return rows.filter((row) => row.selected);
}
