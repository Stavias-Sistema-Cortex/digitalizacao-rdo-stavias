// @vitest-environment jsdom

import type { PropsWithChildren } from "react";
import {
  act,
  cleanup,
  render,
  screen,
  waitFor,
  within,
} from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import type {
  ObraLocalRecord,
  OperationalEventRecord,
} from "../../lib/db/db.types";

const state = vi.hoisted(() => ({
  alfa: true,
  obras: [] as ObraLocalRecord[],
  events: [] as OperationalEventRecord[],
  focusedObraId: "obra-1",
}));

const api = vi.hoisted(() => ({
  buscarTimelineObra: vi.fn(),
  buscarPdorAtual: vi.fn(),
  setFocusedObraId: vi.fn(),
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
    setFocusedObraId: api.setFocusedObraId,
    events: state.events,
    isLoading: false,
    hasConfirmedRemoteHydration: true,
    reload: vi.fn(),
  }),
}));

vi.mock("./obrasApi", () => ({
  buscarTimelineObra: api.buscarTimelineObra,
  buscarPdorAtual: api.buscarPdorAtual,
}));

vi.mock("./obraLifecycle", () => ({
  queueUpdateObra: queues.update,
  queueDeactivateObra: queues.deactivate,
  queueArchiveObra: queues.archive,
  queueRestoreObra: queues.restore,
}));

vi.mock("./gestao/NovaObraForm", () => ({
  NovaObraForm: ({
    onCreated,
  }: {
    onCreated: (created: { id: string }) => void;
  }) => (
    <button
      type="button"
      onClick={() => {
        state.obras = [
          obra({
            id: "obra-created",
            nome: "Nova ponte",
            codigoContrato: "CTR-NEW",
            uf: "SP",
            rodovia: "BR-101",
            updatedAt: "2026-07-28T15:00:00.000Z",
          }),
          ...state.obras,
        ];
        onCreated({ id: "obra-created" });
      }}
    >
      Confirmar criação teste
    </button>
  ),
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

function operationalEvent(
  obraId: string,
  values: Partial<OperationalEventRecord> = {},
): OperationalEventRecord {
  return {
    id: `event-${obraId}`,
    type: "OBRA_ATUALIZADA",
    principalEntity: {
      tipo: "OBRA",
      id: obraId,
      nome: obraId,
    },
    principalEntityKey: `OBRA:${obraId}`,
    relatedEntities: [],
    obraId,
    rdoId: null,
    colaboradorId: null,
    occurredAt: "2026-07-28T12:00:00.000Z",
    syncedAt: null,
    origin: "OFFLINE",
    responsibleUserId: null,
    responsibleUserName: null,
    payload: { nome: `Evento ${obraId}` },
    syncStatus: "PENDING_SYNC",
    schemaVersion: 13,
    ...values,
  };
}

function moveFocusToDocumentBody() {
  const previousTabIndex = document.body.getAttribute("tabindex");
  document.body.setAttribute("tabindex", "-1");
  document.body.focus();
  if (previousTabIndex === null) {
    document.body.removeAttribute("tabindex");
  } else {
    document.body.setAttribute("tabindex", previousTabIndex);
  }
}

beforeEach(() => {
  state.alfa = true;
  state.focusedObraId = "obra-1";
  state.obras = [obra()];
  state.events = [];
  api.buscarTimelineObra.mockResolvedValue([]);
  api.buscarPdorAtual.mockResolvedValue(null);
  api.setFocusedObraId.mockImplementation((id: string) => {
    state.focusedObraId = id;
  });
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
    expect(queues.update).toHaveBeenCalledWith(
      expect.objectContaining({ id: "obra-1" }),
      {
        codigoContrato: "CTR-1",
        codigoInterno: "INT-1",
        nome: "Duplicação BR-163",
        cliente: "DNIT",
        descricao: "Duplicação e restauração",
        cidade: "Campo Grande",
        uf: "MS",
        rodovia: "BR-262",
        fonteArquivo: null,
        observacoes: "Trecho norte",
      },
    );
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

  it.each([
    ["LOCAL_ONLY", "Pendente"],
    ["SYNCING", "Sincronizando"],
    ["CONFLICT", "Conflito"],
    ["ERROR", "Erro"],
  ] as const)(
    "renders the %s lifecycle state as %s",
    (syncStatus, label) => {
      state.obras = [obra({ syncStatus })];
      render(<ObrasPage />);

      expect(screen.getByText(label)).toBeInTheDocument();
    },
  );

  it("keeps edit and archive failures in their active modal surfaces", async () => {
    const user = userEvent.setup();
    queues.update.mockRejectedValueOnce(
      new Error("Falha ao salvar a obra."),
    );
    render(<ObrasPage />);

    await user.click(screen.getByRole("button", { name: "Editar" }));
    let dialog = screen.getByRole("dialog", { name: "Editar obra" });
    await user.click(within(dialog).getByRole("button", {
      name: "Salvar alterações",
    }));

    expect(await within(dialog).findByRole("alert")).toHaveTextContent(
      "Falha ao salvar a obra.",
    );
    expect(dialog).toBeInTheDocument();

    await user.click(within(dialog).getByRole("button", {
      name: "Cancelar",
    }));
    queues.archive.mockRejectedValueOnce(
      new Error("Falha ao excluir a obra."),
    );
    await user.click(screen.getByRole("button", { name: "Excluir" }));
    dialog = screen.getByRole("dialog", { name: "Excluir obra" });
    await user.click(within(dialog).getByRole("button", {
      name: "Excluir obra",
    }));

    expect(await within(dialog).findByRole("alert")).toHaveTextContent(
      "Falha ao excluir a obra.",
    );
    expect(dialog).toBeInTheDocument();
  });

  it("keeps restore errors and archived items visible inside Trash", async () => {
    const user = userEvent.setup();
    state.obras = [obra({
      arquivadoEm: "2026-07-28T13:00:00.000Z",
    })];
    queues.restore.mockRejectedValueOnce(
      new Error("Falha ao restaurar a obra."),
    );
    render(<ObrasPage />);

    await user.click(screen.getByRole("tab", { name: "Lixeira" }));
    const trash = screen.getByRole("region", {
      name: "Lixeira de obras",
    });
    await user.click(within(trash).getByRole("button", {
      name: "Restaurar",
    }));

    expect(await within(trash).findByRole("alert")).toHaveTextContent(
      "Falha ao restaurar a obra.",
    );
    expect(trash).toHaveTextContent("Duplicação BR-262");
  });

  it("keeps deactivate failures visible in the detail without moving the worksite", async () => {
    const user = userEvent.setup();
    queues.deactivate.mockRejectedValueOnce(
      new Error("Falha ao desativar a obra."),
    );
    render(<ObrasPage />);

    await user.click(screen.getByRole("button", { name: "Desativar" }));

    expect(await screen.findByRole("alert")).toHaveTextContent(
      "Falha ao desativar a obra.",
    );
    expect(screen.getByRole("tab", { name: "Ativas" }))
      .toHaveAttribute("aria-selected", "true");
    expect(screen.getByText("ATIVA")).toBeInTheDocument();
  });

  it("traps edit focus, closes on Escape, and returns focus to Edit", async () => {
    const user = userEvent.setup();
    render(<ObrasPage />);

    const trigger = screen.getByRole("button", { name: "Editar" });
    await user.click(trigger);
    const dialog = screen.getByRole("dialog", { name: "Editar obra" });
    const firstInput = within(dialog).getByRole("textbox", {
      name: "Código do contrato",
    });
    const close = within(dialog).getByRole("button", {
      name: "Fechar edição da obra",
    });
    const submit = within(dialog).getByRole("button", {
      name: "Salvar alterações",
    });

    await waitFor(() => expect(firstInput).toHaveFocus());
    submit.focus();
    await user.tab();
    expect(close).toHaveFocus();
    close.focus();
    await user.tab({ shift: true });
    expect(submit).toHaveFocus();

    await user.keyboard("{Escape}");
    expect(screen.queryByRole("dialog", { name: "Editar obra" }))
      .not.toBeInTheDocument();
    expect(trigger).toHaveFocus();
  });

  it("traps archive focus, closes on Escape, and returns focus to Excluir", async () => {
    const user = userEvent.setup();
    render(<ObrasPage />);

    const trigger = screen.getByRole("button", { name: "Excluir" });
    await user.click(trigger);
    const dialog = screen.getByRole("dialog", { name: "Excluir obra" });
    const cancel = within(dialog).getByRole("button", {
      name: "Cancelar",
    });
    const confirm = within(dialog).getByRole("button", {
      name: "Excluir obra",
    });

    await waitFor(() => expect(cancel).toHaveFocus());
    await user.tab({ shift: true });
    expect(confirm).toHaveFocus();
    await user.tab();
    expect(cancel).toHaveFocus();

    await user.keyboard("{Escape}");
    expect(screen.queryByRole("dialog", { name: "Excluir obra" }))
      .not.toBeInTheDocument();
    expect(trigger).toHaveFocus();
  });

  it("recaptures focus after a disabled edit submit and returns it after failure", async () => {
    const user = userEvent.setup();
    let failUpdate: ((reason: Error) => void) | undefined;
    queues.update.mockImplementationOnce(
      () => new Promise<ObraLocalRecord>((_resolve, reject) => {
        failUpdate = reject;
      }),
    );
    render(<ObrasPage />);

    const trigger = screen.getByRole("button", { name: "Editar" });
    await user.click(trigger);
    const dialog = screen.getByRole("dialog", { name: "Editar obra" });
    await user.click(within(dialog).getByRole("button", {
      name: "Salvar alterações",
    }));
    const pendingSubmit = await within(dialog).findByRole("button", {
      name: "Salvando…",
    });
    expect(pendingSubmit).toBeDisabled();

    moveFocusToDocumentBody();
    await user.keyboard("{Escape}");

    expect(dialog).toBeInTheDocument();
    expect(dialog).toContainElement(
      document.activeElement as HTMLElement,
    );

    await act(async () => {
      failUpdate?.(new Error("Falha ao salvar a obra."));
    });
    expect(await within(dialog).findByRole("alert")).toHaveTextContent(
      "Falha ao salvar a obra.",
    );
    expect(dialog).toContainElement(
      document.activeElement as HTMLElement,
    );

    moveFocusToDocumentBody();
    await user.keyboard("{Escape}");

    expect(screen.queryByRole("dialog", { name: "Editar obra" }))
      .not.toBeInTheDocument();
    expect(trigger).toHaveFocus();
  });

  it("keeps the archive dialog modal while pending and focuses Trash after success", async () => {
    const user = userEvent.setup();
    let finishArchive: ((value: ObraLocalRecord) => void) | undefined;
    queues.archive.mockImplementationOnce(
      () => new Promise<ObraLocalRecord>((resolve) => {
        finishArchive = resolve;
      }),
    );
    render(<ObrasPage />);

    await user.click(screen.getByRole("button", { name: "Excluir" }));
    const dialog = screen.getByRole("dialog", { name: "Excluir obra" });
    await user.click(within(dialog).getByRole("button", {
      name: "Excluir obra",
    }));
    await waitFor(() => expect(within(dialog).getByRole("button", {
      name: "Excluindo…",
    })).toBeDisabled());

    moveFocusToDocumentBody();
    await user.tab();
    expect(dialog).toContainElement(
      document.activeElement as HTMLElement,
    );

    await user.keyboard("{Escape}");
    expect(dialog).toBeInTheDocument();

    await act(async () => {
      finishArchive?.(obra({
        arquivadoEm: "2026-07-28T13:00:00.000Z",
        syncStatus: "PENDING_SYNC",
      }));
    });

    const trash = await screen.findByRole("region", {
      name: "Lixeira de obras",
    });
    await waitFor(() => expect(trash).toHaveFocus());
  });

  it("never renders prior-worksite local events after a tab fallback offline", async () => {
    const user = userEvent.setup();
    state.obras = [
      obra(),
      obra({
        id: "obra-2",
        nome: "Contorno Sul",
        status: "INATIVA",
      }),
    ];
    state.events = [operationalEvent("obra-1", {
      type: "OBRA_DESATIVADA",
      payload: { nome: "Evento indevido da obra 1" },
    })];
    api.buscarTimelineObra.mockRejectedValue(
      new Error("Timeline offline."),
    );
    render(<ObrasPage />);

    await user.click(screen.getByRole("tab", { name: "Desativadas" }));
    await user.click(screen.getByRole("button", {
      name: "Ontologia da obra",
    }));

    expect(screen.getByText("Nenhum evento operacional registrado."))
      .toBeInTheDocument();
    expect(screen.queryByText(/Evento indevido da obra 1/))
      .not.toBeInTheDocument();
  });

  it("synchronizes fallback focus and then renders the new worksite local trace", async () => {
    const user = userEvent.setup();
    state.obras = [
      obra(),
      obra({
        id: "obra-2",
        nome: "Contorno Sul",
        status: "INATIVA",
      }),
    ];
    state.events = [operationalEvent("obra-1")];
    api.buscarTimelineObra.mockRejectedValue(
      new Error("Timeline offline."),
    );
    const rendered = render(<ObrasPage />);

    await user.click(screen.getByRole("tab", { name: "Desativadas" }));
    await waitFor(() => {
      expect(api.setFocusedObraId).toHaveBeenCalledWith("obra-2");
    });

    state.events = [operationalEvent("obra-2", {
      payload: { nome: "Evento local da obra 2" },
    })];
    rendered.rerender(<ObrasPage />);
    await user.click(screen.getByRole("button", {
      name: "Ontologia da obra",
    }));

    expect(screen.getByText("nome: Evento local da obra 2"))
      .toBeInTheDocument();
  });

  it("unmounts ontology content and resets aria-expanded on tab changes", async () => {
    const user = userEvent.setup();
    state.obras = [
      obra(),
      obra({
        id: "obra-2",
        nome: "Contorno Sul",
        status: "INATIVA",
      }),
    ];
    render(<ObrasPage />);

    const activeToggle = screen.getByRole("button", {
      name: "Ontologia da obra",
    });
    await user.click(activeToggle);
    expect(activeToggle).toHaveAttribute("aria-expanded", "true");
    expect(screen.getByText("Rastreabilidade Cortex"))
      .toBeInTheDocument();

    await user.click(screen.getByRole("tab", { name: "Desativadas" }));
    const inactiveToggle = screen.getByRole("button", {
      name: "Ontologia da obra",
    });
    expect(inactiveToggle).toHaveAttribute("aria-expanded", "false");
    expect(screen.queryByText("Rastreabilidade Cortex"))
      .not.toBeInTheDocument();
  });

  it("returns to Ativas and focuses the newly created worksite", async () => {
    const user = userEvent.setup();
    state.obras = [
      obra(),
      obra({
        id: "obra-archived",
        nome: "Obra antiga",
        arquivadoEm: "2026-07-28T13:00:00.000Z",
      }),
    ];
    render(<ObrasPage />);

    await user.click(screen.getByRole("tab", { name: "Lixeira" }));
    await user.click(screen.getByRole("button", { name: "Criar obra" }));
    await user.click(screen.getByRole("button", {
      name: "Confirmar criação teste",
    }));

    expect(screen.getByRole("tab", { name: "Ativas" }))
      .toHaveAttribute("aria-selected", "true");
    expect(screen.getByRole("heading", { name: "Nova ponte" }))
      .toBeInTheDocument();
  });

  it("clears operational filters so a newly created worksite is visible", async () => {
    const user = userEvent.setup();
    state.obras = [
      obra(),
      obra({
        id: "obra-archived",
        nome: "Obra antiga",
        arquivadoEm: "2026-07-28T13:00:00.000Z",
      }),
    ];
    render(<ObrasPage />);

    await user.click(screen.getByRole("button", {
      name: "Concluídas",
    }));
    await user.selectOptions(
      screen.getByRole("combobox", { name: "Filtrar por UF" }),
      "MS",
    );
    await user.selectOptions(
      screen.getByRole("combobox", {
        name: "Filtrar por rodovia",
      }),
      "BR-262",
    );
    await user.click(screen.getByRole("tab", { name: "Lixeira" }));
    await user.click(screen.getByRole("button", { name: "Criar obra" }));
    await user.click(screen.getByRole("button", {
      name: "Confirmar criação teste",
    }));

    expect(screen.getByRole("heading", { name: "Nova ponte" }))
      .toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Todas" }))
      .toHaveClass("chip--active");
    expect(screen.getByRole("combobox", { name: "Filtrar por UF" }))
      .toHaveValue("");
    expect(screen.getByRole("combobox", {
      name: "Filtrar por rodovia",
    })).toHaveValue("");
  });
});
