// @vitest-environment jsdom

import { fireEvent, render, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";

import { OperationalWorkspace } from "./OperationalWorkspace";

describe("OperationalWorkspace", () => {
  it("expõe landmarks compactos e um status literal", () => {
    render(
      <OperationalWorkspace
        eyebrow="Operação"
        title="RDO"
        description="Registros persistidos desta obra."
        status={{ code: "PENDING", label: "1 alteração pendente" }}
      >
        <p>Conteúdo real</p>
      </OperationalWorkspace>,
    );

    expect(screen.getByRole("main")).toHaveAttribute(
      "data-workspace",
      "operational",
    );
    expect(screen.getByRole("status")).toHaveTextContent(
      "1 alteração pendente",
    );
    expect(screen.queryByText(/exemplo|mock|demo/i)).not.toBeInTheDocument();
  });

  it("mantém as tabs acessíveis e delega a seleção", () => {
    const onTabChange = vi.fn();
    render(
      <OperationalWorkspace
        eyebrow="Financeiro"
        title="Receita"
        tabs={[
          { id: "receita", label: "Receita", active: true },
          { id: "precos", label: "Serviços e preços" },
        ]}
        onTabChange={onTabChange}
      >
        conteúdo
      </OperationalWorkspace>,
    );

    const active = screen.getByRole("tab", { name: "Receita" });
    expect(active).toHaveAttribute("aria-selected", "true");
    fireEvent.click(screen.getByRole("tab", { name: "Serviços e preços" }));
    expect(onTabChange).toHaveBeenCalledWith("precos");
  });
});
