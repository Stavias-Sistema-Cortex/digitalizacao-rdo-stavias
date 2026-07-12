import type {
  LocalRdoRecord,
  TarefaRecord,
} from "../../lib/db/db.types";

function normalizedKey(value: string): string {
  return value.trim().toLocaleLowerCase("pt-BR");
}

function collectUnique(values: string[]): string[] {
  const byKey = new Map<string, string>();

  for (const value of values) {
    const trimmed = value.trim();
    if (!trimmed) {
      continue;
    }

    const key = normalizedKey(trimmed);
    if (!byKey.has(key)) {
      byKey.set(key, trimmed);
    }
  }

  return [...byKey.values()].sort((a, b) =>
    a.localeCompare(b, "pt-BR"),
  );
}

function stringField(
  entry: Record<string, unknown>,
  field: string,
): string {
  const value = entry[field];
  return typeof value === "string" ? value : "";
}

function entriesOf(
  payload: Record<string, unknown>,
  field: string,
): Record<string, unknown>[] {
  const value = payload[field];

  if (!Array.isArray(value)) {
    return [];
  }

  return value.filter(
    (item): item is Record<string, unknown> =>
      typeof item === "object" && item !== null,
  );
}

/**
 * As equipes não são cadastro fixo: cada obra revela as suas
 * pelas alocações dos RDOs e pelas tarefas já registradas.
 */
export function equipesDaObra(
  rdos: LocalRdoRecord[],
  tarefas: TarefaRecord[],
): string[] {
  const names: string[] = [];

  for (const rdo of rdos) {
    for (const alocacao of entriesOf(
      rdo.payload,
      "alocacoesColaboradores",
    )) {
      names.push(stringField(alocacao, "equipe"));
    }
  }

  for (const tarefa of tarefas) {
    names.push(tarefa.equipe);
  }

  return collectUnique(names);
}

export function responsaveisSugeridos(
  rdos: LocalRdoRecord[],
  tarefas: TarefaRecord[],
): string[] {
  const names: string[] = [];

  for (const rdo of rdos) {
    names.push(
      stringField(rdo.payload, "encarregadoObra"),
      stringField(rdo.payload, "apontadorRdo"),
    );

    for (const pessoa of entriesOf(
      rdo.payload,
      "maoObra",
    )) {
      names.push(stringField(pessoa, "nomeColaborador"));
    }
  }

  for (const tarefa of tarefas) {
    names.push(tarefa.responsavelEquipe);
  }

  return collectUnique(names);
}
