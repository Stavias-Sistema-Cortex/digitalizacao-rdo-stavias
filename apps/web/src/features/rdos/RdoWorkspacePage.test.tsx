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
  reconciliar,
} = vi.hoisted(() => ({
  listLocalRdos: vi.fn(),
  listOperationalEvents: vi.fn(),
  listAllRdoAttachments: vi.fn(),
  importarRdoArquivo: vi.fn(),
  reconciliar: vi.fn(),
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

vi.mock("./rdosDoServidor", () => ({
  reconciliarRdosDoServidor: reconciliar,
}));

vi.mock("./RdoLocalList", () => ({
  RdoLocalList: ({
    onCreate,
    onImportRdoFile,
    records,
    error,
    isLoading,
  }: {
    onCreate: () => void;
    onImportRdoFile: (file: File) => void;
    records: Array<{ id: string; numeroRdo: string }>;
    error: string;
    isLoading: boolean;
  }) => (
    <>
      <output aria-label="RDOs carregados">
        {records.map((record) => record.numeroRdo).join(",")}
      </output>
      {error ? <div role="alert">{error}</div> : null}
      {isLoading ? <div>Carregando RDOs locais...</div> : null}
      {!isLoading && records.length === 0 && !error
        ? <div>Nenhum RDO encontrado</div>
        : null}
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

function reconciliationResult() {
  return {
    descobertos: 0,
    detalhados: 0,
    pendentes: 0,
    removidos: 0,
    falhas: 0,
  };
}

describe("RdoWorkspacePage: entrada do novo RDO", () => {
  beforeEach(() => {
    clearSession();
    setSession(session());
    listLocalRdos.mockReset();
    listOperationalEvents.mockReset();
    listAllRdoAttachments.mockReset();
    reconciliar.mockReset();
    importarRdoArquivo.mockReset();
    listLocalRdos.mockResolvedValue([]);
    listOperationalEvents.mockResolvedValue([]);
    listAllRdoAttachments.mockResolvedValue([]);
    reconciliar.mockResolvedValue(reconciliationResult());
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

  it("keeps the loaded RDO list intact when an import is rejected", async () => {
    listLocalRdos.mockResolvedValue([
      { id: "rdo-preservado", numeroRdo: "RDO-PRESERVADO" },
    ]);
    importarRdoArquivo.mockRejectedValue(
      new Error("O arquivo selecionado não é um RDO válido."),
    );
    const user = userEvent.setup();
    render(<RdoWorkspacePage />);

    expect(
      await screen.findByText("RDO-PRESERVADO"),
    ).toBeInTheDocument();
    await user.click(screen.getByRole("button", {
      name: "Importar teste",
    }));

    expect(
      await screen.findByRole("alert"),
    ).toHaveTextContent(
      "O arquivo selecionado não é um RDO válido.",
    );
    expect(screen.getByText("RDO-PRESERVADO")).toBeInTheDocument();
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
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
    await waitFor(() => expect(listLocalRdos).toHaveBeenCalledTimes(3));
  });

  /*
   * A API do Córtex hiberna: no primeiro acesso do dia a reconciliação leva
   * segundos. Enquanto ela vinha antes da leitura local, a tela ficava em
   * "Carregando RDOs locais…" sobre dados que já estavam no aparelho — o
   * oposto do que um produto offline-first promete.
   */
  it("mostra o que o aparelho já tem sem esperar o servidor", async () => {
    const rede = deferred<ReturnType<typeof reconciliationResult>>();
    reconciliar.mockReturnValue(rede.promise);
    listLocalRdos.mockResolvedValue([
      { id: "rdo-local", numeroRdo: "RDO-DO-APARELHO" },
    ]);
    listOperationalEvents.mockResolvedValue([]);
    listAllRdoAttachments.mockResolvedValue([]);

    render(<RdoWorkspacePage />);

    // A rede ainda não respondeu, e o RDO do aparelho já está na tela.
    expect(await screen.findByText("RDO-DO-APARELHO")).toBeInTheDocument();

    rede.resolve(reconciliationResult());
    await waitFor(() => expect(reconciliar).toHaveBeenCalled());
    // E o servidor continua entrando: a leitura se repete para incorporar o
    // que ele trouxe.
    await waitFor(() => expect(listLocalRdos).toHaveBeenCalledTimes(2));
  });

  it("não publica uma lista vazia enquanto a hidratação inicial ainda busca os RDOs", async () => {
    const rede = deferred<ReturnType<typeof reconciliationResult>>();
    reconciliar.mockReturnValue(rede.promise);
    listLocalRdos
      .mockResolvedValueOnce([])
      .mockResolvedValueOnce([
        { id: "rdo-completo", numeroRdo: "RDO-COMPLETO" },
      ]);

    render(<RdoWorkspacePage />);
    await waitFor(() => expect(reconciliar).toHaveBeenCalledOnce());

    expect(screen.getByText("Carregando RDOs locais...")).toBeVisible();
    expect(screen.queryByText("Nenhum RDO encontrado"))
      .not.toBeInTheDocument();

    rede.resolve(reconciliationResult());
    expect(await screen.findByText("RDO-COMPLETO")).toBeVisible();
    await waitFor(() => expect(
      screen.queryByText("Carregando RDOs locais..."),
    ).not.toBeInTheDocument());
  });

  it("não chama uma consulta incompleta de lista vazia", async () => {
    reconciliar.mockResolvedValue({
      ...reconciliationResult(),
      falhas: 1,
    });
    listLocalRdos.mockResolvedValue([]);

    render(<RdoWorkspacePage />);

    expect(await screen.findByRole("alert")).toHaveTextContent(
      /não foi possível concluir a atualização dos RDOs/i,
    );
    expect(screen.queryByText("Nenhum RDO encontrado"))
      .not.toBeInTheDocument();
  });
});
