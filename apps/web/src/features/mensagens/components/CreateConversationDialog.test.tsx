// @vitest-environment jsdom

import {
  cleanup,
  render,
  screen,
  waitFor,
  within,
} from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";

import type { ObraLocalRecord } from "../../../lib/db/db.types";
import { CreateConversationDialog } from "./CreateConversationDialog";

vi.mock("../../rdos/rdoLookupApi", () => ({
  buscarColaboradoresDaObra: vi.fn().mockResolvedValue([]),
}));

vi.mock("../mensagensApi", () => ({
  buscarDiretorioDeMensagens: vi.fn().mockResolvedValue([]),
  createConversationApi: vi.fn(),
}));

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

afterEach(cleanup);

describe("CreateConversationDialog worksite selector", () => {
  it("keeps inactive operational worksites and excludes archived ones", async () => {
    render(
      <CreateConversationDialog
        obrasPromise={Promise.resolve([
          obra("Obra ativa"),
          obra("Obra inativa", { status: "INATIVA" }),
          obra("Obra arquivada", {
            status: "INATIVA",
            arquivadoEm: "2026-07-28T13:00:00.000Z",
          }),
        ])}
        alfa
        onClose={vi.fn()}
        onCreated={vi.fn()}
      />,
    );

    const selector = await screen.findByRole("combobox", {
      name: "Filtrar pessoas por obra (opcional)",
    });
    await waitFor(() => {
      const options = within(selector).getAllByRole("option");
      expect(options.map((option) => option.textContent)).toEqual([
        "Todas as pessoas",
        "Obra ativa",
        "Obra inativa",
      ]);
    });
    expect(selector).not.toHaveTextContent("Obra arquivada");
  });
});
