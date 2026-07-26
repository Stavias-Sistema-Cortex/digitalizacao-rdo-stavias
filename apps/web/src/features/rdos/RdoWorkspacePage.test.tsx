// @vitest-environment jsdom
import type { ReactNode } from "react";
import { act, cleanup, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { clearSession, setSession } from "../auth/authSession";

const {
  listLocalRdos,
  listOperationalEvents,
  listAllRdoAttachments,
  importarRdoArquivo,
} = vi.hoisted(() => ({
  listLocalRdos: vi.fn(),
  listOperationalEvents: vi.fn(),
  listAllRdoAttachments: vi.fn(),
  importarRdoArquivo: vi.fn(),
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

vi.mock("./importRdoExcel", () => ({
  importarRdoArquivo,
}));

vi.mock("./RdoLocalList", () => ({
  RdoLocalList: ({
    onCreate,
    onImportRdoFile,
    records,
  }: {
    onCreate: () => void;
    onImportRdoFile: (file: File) => void;
    records: Array<{ id: string; numeroRdo: string }>;
  }) => (
    <>
      <output aria-label="RDOs carregados">
        {records.map((record) => record.numeroRdo).join(",")}
      </output>
      <button type="button" onClick={onCreate}>
        Novo RDO
      </button>
      <button
        type="button"
        onClick={() => onImportRdoFile(new File(["rdo"], "RDO.xlsx"))}
      >
        Importar teste
      </button>
    </>
  ),
}));

afterEach(() => {
  cleanup();
  clearSession();
});

vi.mock("./RdoCreationDialog", () => ({
  RdoCreationDialog: ({
    initialDraft,
  }: {
    initialDraft?: { contrato: string; observacoes: string };
  }) => (
    <div role="dialog" aria-label="Criar RDO a partir de uma obra">
      Seleção de obra
      {initialDraft ? (
        <span>
          Importado: {initialDraft.contrato} · {initialDraft.observacoes}
        </span>
      ) : null}
    </div>
  ),
}));

vi.mock("./RdoCreatePage", () => ({
  RdoCreatePage: () => <div data-testid="rdo-editor">Editor do RDO</div>,
}));

import { RdoWorkspacePage } from "./RdoWorkspacePage";
import { createEmptyRdo } from "./createEmptyRdo";

const OWNER_A = "00000000-0000-4000-8000-000000000030";
const OWNER_B = "00000000-0000-4000-8000-000000000031";

function session(ownerId = OWNER_A) {
  return {
    colaboradorId: ownerId,
    nome: "Encarregado de teste",
    papelAcesso: "ALFA" as const,
    escopoGlobal: true,
    obraIds: [],
    expiraEm: "2099-07-14T12:00:00Z",
  };
}

function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((done) => {
    resolve = done;
  });
  return { promise, resolve };
}

describe("RdoWorkspacePage: entrada do novo RDO", () => {
  beforeEach(() => {
    clearSession();
    setSession(session());
    listLocalRdos.mockReset();
    listOperationalEvents.mockReset();
    listAllRdoAttachments.mockReset();
    importarRdoArquivo.mockReset();
    listLocalRdos.mockResolvedValue([]);
    listOperationalEvents.mockResolvedValue([]);
    listAllRdoAttachments.mockResolvedValue([]);
    const imported = createEmptyRdo();
    imported.dataRdo = "2026-07-22";
    imported.contrato = "CTR-IMPORTADO-SEM-UUID";
    imported.observacoes = "Célula operacional preservada";
    importarRdoArquivo.mockResolvedValue({
      draft: imported,
      summary: "Planilha importada",
      warnings: ["Obra ainda não vinculada"],
    });
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

  it("roteia importação sem obra pelo diálogo e preserva a evidência importada", async () => {
    const user = userEvent.setup();
    render(<RdoWorkspacePage />);

    await user.click(screen.getByRole("button", { name: "Importar teste" }));

    expect(
      await screen.findByText(
        /Importado: CTR-IMPORTADO-SEM-UUID · Célula operacional preservada/,
      ),
    ).toBeVisible();
    expect(screen.getByRole("dialog")).toBeVisible();
    expect(screen.queryByTestId("rdo-editor")).not.toBeInTheDocument();
  });

  it("does not let an old session load repopulate the workspace after rotation", async () => {
    const oldRecords = deferred<Array<{
      id: string;
      numeroRdo: string;
    }>>();
    const oldEvents = deferred<[]>();
    const oldAttachments = deferred<[]>();
    listLocalRdos
      .mockImplementationOnce(() => oldRecords.promise)
      .mockResolvedValueOnce([]);
    listOperationalEvents
      .mockImplementationOnce(() => oldEvents.promise)
      .mockResolvedValueOnce([]);
    listAllRdoAttachments
      .mockImplementationOnce(() => oldAttachments.promise)
      .mockResolvedValueOnce([]);

    render(<RdoWorkspacePage />);
    await waitFor(() => expect(listLocalRdos).toHaveBeenCalledOnce());

    await act(async () => {
      setSession(session(OWNER_B));
    });

    oldRecords.resolve([
      { id: "rdo-antigo", numeroRdo: "RDO-ANTIGO" },
    ]);
    oldEvents.resolve([]);
    oldAttachments.resolve([]);

    await act(async () => {
      await Promise.resolve();
    });

    expect(screen.queryByText("RDO-ANTIGO")).not.toBeInTheDocument();
    await waitFor(() => expect(listLocalRdos).toHaveBeenCalledTimes(2));
  });
});
