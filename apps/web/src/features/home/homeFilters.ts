import type { ObraLocalRecord } from "../../lib/db/db.types";

export type ObraStatusChip =
  | "TODAS"
  | "EM_EXECUCAO"
  | "CONCLUIDAS"
  | "A_COMECAR"
  | "DESATIVADAS";

export const OBRA_STATUS_CHIPS: {
  value: ObraStatusChip;
  label: string;
}[] = [
  { value: "TODAS", label: "Todas" },
  { value: "EM_EXECUCAO", label: "Em Execução" },
  { value: "CONCLUIDAS", label: "Concluídas" },
  { value: "A_COMECAR", label: "A Começar" },
  { value: "DESATIVADAS", label: "Desativadas" },
];

const CHIP_STATUSES: Record<
  Exclude<ObraStatusChip, "TODAS">,
  string[]
> = {
  EM_EXECUCAO: ["ATIVA", "EM_EXECUCAO", "EM EXECUCAO"],
  CONCLUIDAS: ["CONCLUIDA", "ENCERRADA", "FINALIZADA"],
  A_COMECAR: ["A_COMECAR", "A COMECAR", "PLANEJADA", "NAO_INICIADA"],
  DESATIVADAS: ["DESATIVADA", "ARQUIVADA", "SUSPENSA", "INATIVA"],
};

function normalizeStatus(status: string): string {
  return status
    .normalize("NFD")
    .replace(/\p{Diacritic}/gu, "")
    .toUpperCase()
    .trim();
}

export function filterObrasByChip(
  obras: ObraLocalRecord[],
  chip: ObraStatusChip,
): ObraLocalRecord[] {
  if (chip === "TODAS") {
    return obras;
  }

  const allowed = CHIP_STATUSES[chip];

  return obras.filter((obra) =>
    allowed.includes(normalizeStatus(obra.status)),
  );
}

export function filterObrasByUf(
  obras: ObraLocalRecord[],
  uf: string,
): ObraLocalRecord[] {
  const needle = uf.trim().toUpperCase();

  if (!needle) {
    return obras;
  }

  return obras.filter(
    (obra) => (obra.uf ?? "").toUpperCase() === needle,
  );
}

export function filterObrasByRodovia(
  obras: ObraLocalRecord[],
  rodovia: string,
): ObraLocalRecord[] {
  const needle = rodovia.trim().toUpperCase();

  if (!needle) {
    return obras;
  }

  return obras.filter(
    (obra) =>
      (obra.rodovia ?? "").toUpperCase() === needle,
  );
}
