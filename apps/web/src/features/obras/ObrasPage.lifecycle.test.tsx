// @vitest-environment jsdom

import type { PropsWithChildren } from "react";
import { cleanup, render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import type { ObraLocalRecord } from "../../lib/db/db.types";

const state = vi.hoisted(() => ({
  alfa: true,
  obras: [] as ObraLocalRecord[],
  focusedObraId: "obra-1",
}));

const queues = vi.hoisted(() => ({
  update: vi.fn(),
  deactivate: vi.fn(),
  archive: vi.fn(),
  restore: vi.fn(),
}));

vi.mock("../../components/shell/CortexShell", () => ({
  CortexShell: ({ children }: PropsWithChildren) => <>{children}</>,
}));

vi.mock("../auth/authSession", () => ({
  getSession: () => state.alfa
    ? {
        colaboradorId: "00000000-0000-4000-8000-000000000001",
        papelAcesso: "ALFA",
        escopoGlobal: true,
        obraIds: [],
        expiraEm: "2099-01-01T00:00:00.000Z",
      }
    : {
        colaboradorId: "00000000-0000-4000-8000-000000000002",
        papelAcesso: "BETA",
        escopoGlobal: false,
        obraIds: ["obra-1"],
        expiraEm: "2099-01-01T00:00:00.000Z",
      },
  isAlfa: (session: { papelAcesso?: string } | null) =>
    session?.papelAcesso === "ALFA",
}));

vi.mock("../home/useHomeData", () => ({
  useHomeData: () => ({
    obras: state.obras,
    focusedObra: state.obras.find(
      (obra) => obra.id === state.focusedObraId,
    ) ?? null,
    focusedObraId: state.focusedObraId,
    setFocusedObraId: (id: string) => {
      state.focusedObraId = id;
    },
    events: [],
    isLoading: false,
    hasConfirmedRemoteHydration: true,
    reload: vi.fn(),
  }),
}));

vi.mock("./obrasApi", () => ({
  buscarTimelineObra: vi.fn().mockResolvedValue([]),
  buscarPdorAtual: vi.fn().mockResolvedValue(null),
}));

vi.mock("./obraLifecycle", () => ({
  queueUpdateObra: queues.update,
  queueDeactivateObra: queues.deactivate,
  queueArchiveObra: queues.archive,
  queueRestoreObra: queues.restore,
}));

import { ObrasPage } from "./ObrasPage";

function obra(
  values: Partial<ObraLocalRecord> = {},
): ObraLocalRecord {
  return {
    id: "obra-1",
    codigoContrato: "CTR-1",
    codigoInterno: "INT-1",
    nome: "Duplicação BR-262",
    cliente: "DNIT",
    descricao: "Duplicação e restauração",
    cidade: "Campo Grande",
    uf: "MS",
    rodovia: "BR-262",
    fonteArquivo: null,
    status: "ATIVA",
    observacoes: "Trecho norte",
    latitude: null,
    longitude: null,
    valorContratual: null,
    versaoEntidade: 4,
    arquivadoEm: null,
    syncStatus: "SYNCED",
    ultimoErro: null,
    updatedAt: "2026-07-28T12:00:00.000Z",
    ...values,
  };
}

beforeEach(() => {
  state.alfa = true;
  state.focusedObraId = "obra-1";
  state.obras = [obra()];
  queues.update.mockImplementation(
    async (existing: ObraLocalRecord, input: { nome: string }) => ({
      ...existing,
      nome: input.nome,
      syncStatus: "PENDING_SYNC",
      updatedAt: "2026-07-28T13:00:00.000Z",
    }),
  );
  queues.deactivate.mockImplementation(
    async (existing: ObraLocalRecord) => ({
      ...existing,
      status: "INATIVA",
      syncStatus: "PENDING_SYNC",
      updatedAt: "2026-07-28T13:00:00.000Z",
    }),
  );
  queues.archive.mockImplementation(
    async (existing: ObraLocalRecord) => ({
      ...existing,
      arquivadoEm: "2026-07-28T13:00:00.000Z",
      syncStatus: "PENDING_SYNC",
      updatedAt: "2026-07-28T13:00:00.000Z",
    }),
  );
  queues.restore.mockImplementation(
    async (existing: ObraLocalRecord) => ({
      ...existing,
      arquivadoEm: null,
      syncStatus: "PENDING_SYNC",
      updatedAt: "2026-07-28T14:00:00.000Z",
    }),
  );
});

afterEach(() => {
  cleanup();
  vi.clearAllMocks();
});

describe("ObrasPage lifecycle Alfa", () => {
  it("não monta ações administrativas nem Lixeira para Beta", () => {
    state.alfa = false;
    render(<ObrasPage />);

    expect(screen.queryByRole("button", { name: "Editar" }))
      .not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Desativar" }))
      .not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Excluir" }))
      .not.toBeInTheDocument();
    expect(screen.queryByRole("tab", { name: "Lixeira" }))
      .not.toBeInTheDocument();
  });

  it("edita os campos cadastrais e mostra o snapshot otimista pendente", async () => {
    const user = userEvent.setup();
    render(<ObrasPage />);

    await user.click(screen.getByRole("button", { name: "Editar" }));
    const dialog = screen.getByRole("dialog", { name: "Editar obra" });
    const nome = within(dialog).getByRole("textbox", { name: "Nome" });
    await user.clear(nome);
    await user.type(nome, "Duplicação BR-163");
    await user.click(within(dialog).getByRole("button", {
      name: "Salvar alterações",
    }));

    expect(screen.getAllByText("Duplicação BR-163").length).toBeGreaterThan(0);
    expect(screen.getByText("Pendente")).toBeInTheDocument();
  });

  it("abandona o override otimista quando o cache recebe a confirmação autoritativa", async () => {
    const user = userEvent.setup();
    const rendered = render(<ObrasPage />);

    await user.click(screen.getByRole("button", { name: "Editar" }));
    const dialog = screen.getByRole("dialog", { name: "Editar obra" });
    const nome = within(dialog).getByRole("textbox", { name: "Nome" });
    await user.clear(nome);
    await user.type(nome, "Duplicação confirmada");
    await user.click(within(dialog).getByRole("button", {
      name: "Salvar alterações",
    }));
    expect(screen.getByText("Pendente")).toBeInTheDocument();

    state.obras = [obra({
      nome: "Duplicação confirmada",
      versaoEntidade: 5,
      syncStatus: "SYNCED",
      updatedAt: "2026-07-28T14:00:00.000Z",
    })];
    rendered.rerender(<ObrasPage />);

    expect(screen.queryByText("Pendente")).not.toBeInTheDocument();
    expect(screen.getAllByText("Duplicação confirmada").length)
      .toBeGreaterThan(0);
  });

  it("arquiva após confirmação restaurável e restaura com o status preservado", async () => {
    const user = userEvent.setup();
    render(<ObrasPage />);

    await user.click(screen.getByRole("button", { name: "Excluir" }));
    const confirmation = screen.getByRole("dialog", {
      name: "Excluir obra",
    });
    expect(confirmation).toHaveTextContent(
      "A obra poderá ser restaurada na Lixeira.",
    );
    await user.click(within(confirmation).getByRole("button", {
      name: "Excluir obra",
    }));

    const trashTab = screen.getByRole("tab", { name: "Lixeira" });
    await user.click(trashTab);
    const trash = screen.getByRole("region", { name: "Lixeira de obras" });
    expect(trash).toHaveTextContent("Duplicação BR-262");
    expect(trash).toHaveTextContent("ATIVA");
    expect(trash).toHaveTextContent("28/07/2026");
    expect(trash).toHaveTextContent("Pendente");

    await user.click(within(trash).getByRole("button", {
      name: "Restaurar",
    }));
    expect(screen.queryByRole("region", { name: "Lixeira de obras" }))
      .toHaveTextContent("Nenhuma obra na Lixeira.");
  });

  it("desativa sem criar estado auxiliar e move a obra para Desativadas", async () => {
    const user = userEvent.setup();
    render(<ObrasPage />);

    await user.click(screen.getByRole("button", { name: "Desativar" }));
    await user.click(screen.getByRole("tab", { name: "Desativadas" }));

    expect(screen.getByText("INATIVA")).toBeInTheDocument();
    expect(screen.getByText("Pendente")).toBeInTheDocument();
  });
});
