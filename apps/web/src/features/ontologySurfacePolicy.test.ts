import { readFileSync } from "node:fs";
import { resolve } from "node:path";

import { describe, expect, it } from "vitest";

function source(relativePath: string): string {
  return readFileSync(
    resolve(process.cwd(), `./src/features/${relativePath}`),
    "utf8",
  );
}

describe("Home > Memória surface policy", () => {
  it("mantém detalhes de mutação fora das tarefas", () => {
    const tarefas = source("tarefas/TarefasPage.tsx");

    expect(tarefas).not.toContain("Criada por");
    expect(tarefas).not.toContain("Concluída em");
    expect(tarefas).toContain("memoryHref");
  });

  it("resume participações e revogações fora da Memória", () => {
    const equipes = source("equipes/EquipesPage.tsx");
    const gestaoObras = source("obras/gestao/GestaoObrasPage.tsx");

    expect(equipes).not.toContain("Histórico de membros");
    expect(equipes).not.toContain("formerMembers.map");
    expect(equipes).toContain("Participações encerradas");
    expect(gestaoObras).not.toContain("Histórico de revogações");
    expect(gestaoObras).not.toContain("vinculosRevogados.map");
    expect(gestaoObras).toContain("Abrir Memória");
  });
});
