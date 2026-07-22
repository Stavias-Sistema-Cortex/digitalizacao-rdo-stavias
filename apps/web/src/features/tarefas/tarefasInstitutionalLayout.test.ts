import { readFileSync } from "node:fs";
import { resolve } from "node:path";

import { describe, expect, it } from "vitest";

const css = readFileSync(
  resolve(process.cwd(), "./src/features/tarefas/TarefasPage.css"),
  "utf8",
);

function lastRule(selector: string): string {
  const escapedSelector = selector.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
  const rules = [
    ...css.matchAll(
      new RegExp(`${escapedSelector}\\s*\\{(?<body>[^}]*)\\}`, "gs"),
    ),
  ];

  return rules.at(-1)?.groups?.body ?? "";
}

describe("Tarefas institutional workspace", () => {
  it("uses complete rectangular frames for its work surfaces", () => {
    const card = lastRule(".tarefas-card");
    const activeTeam = lastRule(".tarefas-equipe-tab--active");
    const obraTab = lastRule(".tarefas-obra-tabs .chip");

    expect(card).toContain("border: 1px solid var(--color-ink);");
    expect(card).toContain("border-radius: var(--radius-control);");
    expect(activeTeam).toContain(
      "border: 1px solid var(--color-ink);",
    );
    expect(activeTeam).toContain("box-shadow: none;");
    expect(activeTeam).not.toContain("inset");
    expect(obraTab).toContain("border: 1px solid var(--color-ink);");
    expect(obraTab).toContain("border-radius: var(--radius-control);");
  });

  it("keeps priority and creation controls sober instead of pill-like", () => {
    const priority = lastRule(".tarefa-prio");
    const submit = lastRule(".tarefa-form-enviar");
    const suggestions = lastRule(".tarefa-sugestoes");

    expect(priority).toContain("border-radius: var(--radius-control);");
    expect(priority).not.toContain("999px");
    expect(submit).toContain("background: var(--color-ink);");
    expect(submit).toContain("color: var(--color-surface);");
    expect(submit).toContain("border: 1px solid var(--color-ink);");
    expect(suggestions).toContain("border: 1px solid var(--color-ink);");
    expect(suggestions).toContain("box-shadow: none;");
  });
});
