// @vitest-environment jsdom

import type { PropsWithChildren, ReactNode } from "react";
import { cleanup, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import type { ObraLocalRecord } from "../../lib/db/db.types";

const mocks = vi.hoisted(() => ({
  listObrasLocais: vi.fn(),
  listLocalRdosByObra: vi.fn(),
  listTarefasByObra: vi.fn(),
}));

vi.mock("../../components/shell/CortexShell", () => ({
  CortexShell: ({ children }: PropsWithChildren) => <>{children}</>,
}));

vi.mock("../../components/workspace/OperationalWorkspace", () => ({
  OperationalWorkspace: ({
    title,
    children,
  }: {
    title: string;
    children: ReactNode;
  }) => <main><h1>{title}</h1>{children}</main>,
}));

vi.mock("../auth/authSession", () => ({
  getSession: () => null,
}));

vi.mock("../../lib/db/obraLocalRepository", () => ({
  filterOperationalObras: (
    obras: Array<{ arquivadoEm?: string | null }>,
  ) => obras.filter((obra) => obra.arquivadoEm == null),
  listObrasLocais: mocks.listObrasLocais,
}));

vi.mock("../../lib/db/rdoRepository", () => ({
  listLocalRdosByObra: mocks.listLocalRdosByObra,
}));

vi.mock("../../lib/db/tarefaRepository", () => ({
  createTarefa: vi.fn(),
  deleteTarefa: vi.fn(),
  listTarefasByObra: mocks.listTarefasByObra,
  setTarefaConclusao: vi.fn(),
}));

vi.mock("./colaboradoresAcademy", () => ({
  hidratarColaboradoresAcademy: vi.fn(),
  listarColaboradoresConhecidos: vi.fn().mockResolvedValue([]),
}));

vi.mock("../home/homeHydration", () => ({
  hydrateObrasRelacionadas: vi.fn().mockResolvedValue(0),
}));

vi.mock("../home/lastAccessedObra", () => ({
  colaboradorStorageKey: () => "anonymous",
  getLastAccessedObraId: () => "obra-arquivada",
  setLastAccessedObraId: vi.fn(),
}));

vi.mock("./TarefaEficienciaChart", () => ({
  TarefaEficienciaChart: () => <div>Gráfico</div>,
}));

import { TarefasPage } from "./TarefasPage";

function obra(
  id: string,
  values: Partial<ObraLocalRecord> = {},
): ObraLocalRecord {
  return {
    id,
    codigoContrato: id,
    nome: id,
    cliente: null,
    cidade: null,
    uf: null,
    rodovia: null,
    status: "ATIVA",
    observacoes: null,
    latitude: null,
    longitude: null,
    valorContratual: null,
    arquivadoEm: null,
    updatedAt: "2026-07-28T12:00:00.000Z",
    ...values,
  };
}

beforeEach(() => {
  vi.clearAllMocks();
  mocks.listObrasLocais.mockResolvedValue([
    obra("obra-ativa", { nome: "Obra ativa" }),
    obra("obra-inativa", {
      nome: "Obra inativa",
      status: "INATIVA",
    }),
    obra("obra-arquivada", {
      nome: "Obra arquivada",
      status: "INATIVA",
      arquivadoEm: "2026-07-28T13:00:00.000Z",
    }),
  ]);
  mocks.listLocalRdosByObra.mockResolvedValue([]);
  mocks.listTarefasByObra.mockResolvedValue([]);
});

afterEach(cleanup);

describe("TarefasPage operational worksite selector", () => {
  it("drops an archived stored focus before loading details and keeps inactive operational tabs", async () => {
    render(<TarefasPage />);

    const navigation = await screen.findByRole("navigation", {
      name: "Obras",
    });
    expect(navigation).toHaveTextContent("Obra ativa");
    expect(navigation).toHaveTextContent("Obra inativa");
    expect(navigation).not.toHaveTextContent("Obra arquivada");

    await waitFor(() => {
      expect(mocks.listTarefasByObra).toHaveBeenCalledOnce();
    });
    expect(["obra-ativa", "obra-inativa"]).toContain(
      mocks.listTarefasByObra.mock.calls[0]?.[0],
    );
    expect(mocks.listTarefasByObra).not.toHaveBeenCalledWith(
      "obra-arquivada",
    );
    expect(mocks.listLocalRdosByObra).not.toHaveBeenCalledWith(
      "obra-arquivada",
    );
  });
});
