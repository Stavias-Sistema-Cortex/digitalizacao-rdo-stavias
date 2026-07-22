// @vitest-environment jsdom
import type { ReactNode } from "react";
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";

const {
  listLocalRdos,
  listOperationalEvents,
  listAllRdoAttachments,
} = vi.hoisted(() => ({
  listLocalRdos: vi.fn(),
  listOperationalEvents: vi.fn(),
  listAllRdoAttachments: vi.fn(),
}));

vi.mock("../../components/shell/CortexShell", () => ({
  CortexShell: ({ children }: { children: ReactNode }) => (
    <div data-testid="cortex-shell">{children}</div>
  ),
}));

vi.mock("../../lib/db/rdoRepository", () => ({
  listLocalRdos,
}));

vi.mock("../../lib/db/operationalEventRepository", () => ({
  listOperationalEvents,
}));

vi.mock("../../lib/db/rdoAttachmentRepository", () => ({
  listAllRdoAttachments,
}));

vi.mock("./RdoLocalList", () => ({
  RdoLocalList: ({ onCreate }: { onCreate: () => void }) => (
    <button type="button" onClick={onCreate}>
      Novo RDO
    </button>
  ),
}));

vi.mock("./RdoCreationDialog", () => ({
  RdoCreationDialog: () => (
    <div role="dialog" aria-label="Criar RDO a partir de uma obra">
      Seleção de obra
    </div>
  ),
}));

vi.mock("./RdoCreatePage", () => ({
  RdoCreatePage: () => <div data-testid="rdo-editor">Editor do RDO</div>,
}));

import { RdoWorkspacePage } from "./RdoWorkspacePage";

describe("RdoWorkspacePage: entrada do novo RDO", () => {
  beforeEach(() => {
    listLocalRdos.mockResolvedValue([]);
    listOperationalEvents.mockResolvedValue([]);
    listAllRdoAttachments.mockResolvedValue([]);
  });

  it("abre obrigatoriamente o diálogo obra-data sem montar diretamente o editor", async () => {
    const user = userEvent.setup();
    render(<RdoWorkspacePage />);

    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
    expect(screen.queryByTestId("rdo-editor")).not.toBeInTheDocument();

    await user.click(screen.getByRole("button", { name: "Novo RDO" }));

    expect(
      screen.getByRole("dialog", {
        name: "Criar RDO a partir de uma obra",
      }),
    ).toBeInTheDocument();
    expect(screen.queryByTestId("rdo-editor")).not.toBeInTheDocument();
  });
});
