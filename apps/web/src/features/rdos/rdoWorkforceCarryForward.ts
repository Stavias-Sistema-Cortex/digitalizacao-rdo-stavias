import { createEmptyMaoObra } from "./createEmptyRdo";
import type { MaoObraDraft } from "./rdo.types";
import type {
  RdoContextCollaborator,
  RdoPreviousWorkforceItem,
} from "./rdoLookupApi";

type IdFactory = () => string;

function compactTime(value: string | null): string {
  return value?.match(/^\d{2}:\d{2}/)?.[0] ?? "";
}

function collaboratorMap(
  catalog: readonly RdoContextCollaborator[],
): Map<string, RdoContextCollaborator> {
  return new Map(
    catalog
      .filter((item) => item.id.trim())
      .map((item) => [item.id, item]),
  );
}

export function carryForwardWorkforce(
  previousWorkers: readonly RdoPreviousWorkforceItem[],
  currentCatalog: readonly RdoContextCollaborator[],
  createId: IdFactory = () => crypto.randomUUID(),
): MaoObraDraft[] {
  const authorized = collaboratorMap(currentCatalog);
  const seen = new Set<string>();
  const rows: MaoObraDraft[] = [];

  for (const item of previousWorkers) {
    const collaboratorId = item.collaboratorId?.trim() ?? "";
    const identityKey = collaboratorId
      ? `collaborator:${collaboratorId}`
      : `evidence:${item.sourceRdoId}:${item.sourceItemId}`;
    if (seen.has(identityKey)) continue;
    seen.add(identityKey);

    const current = collaboratorId
      ? authorized.get(collaboratorId)
      : undefined;
    const availability = item.availability?.toUpperCase();
    const available = collaboratorId
      ? Boolean(current) && availability !== "UNAVAILABLE"
      : Boolean(item.nameSnapshot?.trim()) && availability !== "UNAVAILABLE";
    rows.push({
      ...createEmptyMaoObra(),
      localId: createId(),
      origemItemId: item.sourceItemId,
      sourceRdoId: item.sourceRdoId,
      origin: "PREVIOUS_RDO",
      availability: available ? "AVAILABLE" : "UNAVAILABLE",
      selected: available,
      colaboradorId: collaboratorId,
      nomeColaborador:
        item.nameSnapshot?.trim() || current?.nome?.trim() || "",
      cargo:
        item.roleSnapshot?.trim() || current?.nomePerfil?.trim() || "",
      tipoVinculo: item.linkType?.trim() ?? "",
      quantidade: item.quantity ?? "",
      horaInicio: compactTime(item.startTime),
      horaFim: compactTime(item.endTime),
      observacoes: item.observations?.trim() ?? "",
    });
  }

  return rows;
}

export function addAuthorizedWorkforceMember(
  rows: readonly MaoObraDraft[],
  collaboratorId: string,
  currentCatalog: readonly RdoContextCollaborator[],
  createId: IdFactory = () => crypto.randomUUID(),
): MaoObraDraft[] {
  const collaborator = collaboratorMap(currentCatalog).get(collaboratorId);
  if (!collaborator) {
    throw new Error("Colaborador não autorizado para esta obra.");
  }
  if (rows.some((row) => row.colaboradorId === collaboratorId)) {
    throw new Error("Este colaborador já está na equipe do RDO.");
  }

  return [
    ...rows,
    {
      ...createEmptyMaoObra(),
      localId: createId(),
      origin: "AUTHORIZED_CONTEXT",
      availability: "AVAILABLE",
      selected: true,
      colaboradorId: collaboratorId,
      nomeColaborador:
        collaborator.nome?.trim() ||
        collaborator.codigoColaborador?.trim() ||
        "",
      cargo:
        collaborator.nomePerfil?.trim() ||
        collaborator.papelNaObra?.trim() ||
        "",
    },
  ];
}
