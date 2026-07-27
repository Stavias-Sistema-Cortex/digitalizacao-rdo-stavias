// @vitest-environment jsdom

import { cleanup, render, screen, within } from "@testing-library/react";
import { afterEach, describe, expect, it } from "vitest";

import { CortexShellChromeProvider } from "../shell/CortexShellChromeContext";
import { InstitutionalPageHeader } from "../institutional/InstitutionalPageHeader";
import { WorkspaceHeader } from "../workspace/WorkspaceHeader";
import { CortexPageHeader } from "./CortexPageHeader";

afterEach(cleanup);

describe("CortexPageHeader", () => {
  it("mantém identidade, ação real e controles globais no mesmo cabeçalho", () => {
    const { container } = render(
      <CortexShellChromeProvider headerControls={<button type="button">Perfil</button>}>
        <CortexPageHeader
          eyebrow="Operação · evidência financeira"
          title="Financeiro"
          description="Receita comprovada pelos RDOs da operação."
          actions={<button type="button">Nova unidade administrativa</button>}
        />
      </CortexShellChromeProvider>,
    );

    const header = screen.getByRole("banner");
    expect(within(header).getByText("Operação · evidência financeira")).toBeVisible();
    expect(within(header).getByRole("heading", { name: "Financeiro" })).toBeVisible();
    expect(within(header).getByRole("button", {
      name: "Nova unidade administrativa",
    })).toBeVisible();
    expect(within(header).getByRole("button", { name: "Perfil" })).toBeVisible();
    expect(container.querySelectorAll(".cortex-page-header__global-controls"))
      .toHaveLength(1);
    expect(container.querySelector(".floating-controls")).not.toBeInTheDocument();
  });

  it("mantém os contratos das wrappers de workspace e institucional", () => {
    const { container, rerender } = render(
      <CortexShellChromeProvider headerControls={<span>Sincronização ao vivo</span>}>
        <WorkspaceHeader
          eyebrow="Operação"
          title="RDO"
          actions={<button type="button">Novo RDO</button>}
        />
      </CortexShellChromeProvider>,
    );

    expect(screen.getByRole("banner")).toHaveClass(
      "cortex-page-header",
      "workspace-header",
    );
    expect(screen.getByRole("button", { name: "Novo RDO" })).toBeVisible();
    expect(container.querySelectorAll(".cortex-page-header__global-controls"))
      .toHaveLength(1);
    expect(container.querySelector(".floating-controls")).not.toBeInTheDocument();

    rerender(
      <CortexShellChromeProvider headerControls={<span>Sincronização ao vivo</span>}>
        <InstitutionalPageHeader
          eyebrow="Registro"
          title="Editar RDO"
          className="rdo-create-page__header"
        />
      </CortexShellChromeProvider>,
    );

    expect(screen.getByRole("banner")).toHaveClass(
      "cortex-page-header",
      "institutional-page-header",
      "rdo-create-page__header",
    );
    expect(container.querySelectorAll(".cortex-page-header__global-controls"))
      .toHaveLength(1);
    expect(container.querySelector(".floating-controls")).not.toBeInTheDocument();
  });
});
