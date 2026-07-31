// @vitest-environment jsdom

import "@testing-library/jest-dom/vitest";

import {
  cleanup,
  fireEvent,
  render,
  screen,
  waitFor,
} from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

const mocks = vi.hoisted(() => ({
  fetchCompleteServiceCatalog: vi.fn(),
  hydrateServiceCatalog: vi.fn(),
  listLocalServiceCatalog: vi.fn(),
  queueCreateService: vi.fn(),
  queueCreatePrice: vi.fn(),
  queueSupersedePrice: vi.fn(),
  queueCancelPrice: vi.fn(),
}));

vi.mock("./servicePriceApi", () => ({
  fetchCompleteServiceCatalog: mocks.fetchCompleteServiceCatalog,
}));

vi.mock("./servicePriceRepository", () => ({
  hydrateServiceCatalog: mocks.hydrateServiceCatalog,
  listLocalServiceCatalog: mocks.listLocalServiceCatalog,
  queueCreateService: mocks.queueCreateService,
  queueCreatePrice: mocks.queueCreatePrice,
  queueSupersedePrice: mocks.queueSupersedePrice,
  queueCancelPrice: mocks.queueCancelPrice,
}));

import { ServicePriceCatalogPage } from "./ServicePriceCatalogPage";

const OBRA_ID = "00000000-0000-4000-8000-000000000101";

const LOCAL_ROWS = [{
  service: {
    id: "00000000-0000-4000-8000-000000000301",
    code: "PAV.CBUQ",
    name: "Pavimentação CBUQ",
    description: null,
    status: "ACTIVE",
    syncStatus: "SYNCED",
    createdAt: "2026-07-22T12:00:00.000Z",
    updatedAt: "2026-07-22T12:00:00.000Z",
    lastError: null,
  },
  priceVersions: [],
}];

describe("ServicePriceCatalogPage", () => {
  // Sem desmontar entre os casos, cada render deixa a página anterior no DOM e
  // as buscas por papel passam a encontrar o mesmo botão várias vezes.
  afterEach(() => {
    cleanup();
  });

  beforeEach(() => {
    vi.clearAllMocks();
    vi.stubGlobal("navigator", { onLine: false });
    mocks.listLocalServiceCatalog.mockResolvedValue(LOCAL_ROWS);
    mocks.queueCreateService.mockResolvedValue({
      entityId: "00000000-0000-4000-8000-000000000302",
      clientMutationId: "00000000-0000-4000-8000-000000000501",
    });
    mocks.queueCreatePrice.mockResolvedValue({
      entityId: "00000000-0000-4000-8000-000000000401",
      clientMutationId: "00000000-0000-4000-8000-000000000502",
    });
  });

  it("shows persisted catalog offline and does not infer administration rights", async () => {
    render(
      <ServicePriceCatalogPage
        obraId={OBRA_ID}
        permissions={["FINANCEIRO_VISUALIZAR"]}
      />,
    );

    expect(await screen.findByText("Pavimentação CBUQ")).toBeInTheDocument();
    expect(screen.getByText(/dados locais/i)).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /novo serviço/i })).not
      .toBeInTheDocument();
  });

  it("queues a real catalog mutation for an authorized administrator", async () => {
    render(
      <ServicePriceCatalogPage
        obraId={OBRA_ID}
        permissions={["FINANCEIRO_VISUALIZAR", "FINANCEIRO_ADMINISTRAR"]}
      />,
    );

    fireEvent.click(await screen.findByRole("button", { name: /novo serviço/i }));
    fireEvent.change(screen.getByLabelText("Código do serviço"), {
      target: { value: "PAV.BASE" },
    });
    fireEvent.change(screen.getByLabelText("Nome do serviço"), {
      target: { value: "Base graduada" },
    });
    fireEvent.click(screen.getByRole("button", { name: /salvar offline/i }));

    await waitFor(() => expect(mocks.queueCreateService).toHaveBeenCalledWith(
      OBRA_ID,
      { code: "PAV.BASE", name: "Base graduada", description: "" },
    ));
  });

  it("propõe o código a partir do nome e reconhece o serviço que já existe", async () => {
    render(
      <ServicePriceCatalogPage
        obraId={OBRA_ID}
        permissions={["FINANCEIRO_VISUALIZAR", "FINANCEIRO_ADMINISTRAR"]}
      />,
    );

    fireEvent.click(await screen.findByRole("button", { name: /novo serviço/i }));
    fireEvent.change(screen.getByLabelText("Nome do serviço"), {
      target: { value: "Microrrevestimento a frio" },
    });
    expect(screen.getByLabelText("Código do serviço")).toHaveValue(
      "MICRORREVESTIMENTO-A-FRIO",
    );

    // Escrever o nome de um serviço que já está no catálogo precisa levar ao
    // registro existente, não a um segundo com o mesmo nome.
    fireEvent.change(screen.getByLabelText("Nome do serviço"), {
      target: { value: "pavimentação cbuq" },
    });
    expect(
      screen.getByRole("button", { name: /abrir preço deste serviço/i }),
    ).toBeInTheDocument();
  });

  it("cria o serviço e o primeiro preço na mesma ação", async () => {
    render(
      <ServicePriceCatalogPage
        obraId={OBRA_ID}
        permissions={["FINANCEIRO_VISUALIZAR", "FINANCEIRO_ADMINISTRAR"]}
      />,
    );

    fireEvent.click(await screen.findByRole("button", { name: /novo serviço/i }));
    fireEvent.change(screen.getByLabelText("Nome do serviço"), {
      target: { value: "Base graduada" },
    });
    fireEvent.click(screen.getByLabelText(/informar o custo agora/i));
    fireEvent.change(screen.getByLabelText("Unidade"), {
      target: { value: "m3" },
    });
    fireEvent.change(screen.getByLabelText("Valor unitário"), {
      target: { value: "98,70" },
    });
    fireEvent.change(screen.getByLabelText("Quantidade contratada"), {
      target: { value: "1200,000" },
    });
    fireEvent.change(screen.getByLabelText("Início da vigência"), {
      target: { value: "2026-07-01" },
    });
    fireEvent.click(screen.getByRole("button", { name: /salvar offline/i }));

    await waitFor(() => expect(mocks.queueCreateService).toHaveBeenCalledWith(
      OBRA_ID,
      {
        code: "BASE-GRADUADA",
        name: "Base graduada",
        description: "",
      },
    ));
    await waitFor(() => expect(mocks.queueCreatePrice).toHaveBeenCalledWith(
      OBRA_ID,
      expect.any(String),
      {
        unit: "M³",
        currency: "BRL",
        unitPrice: "98,70",
        contractedQuantity: "1200,000",
        validFrom: "2026-07-01",
        validTo: "",
        source: "CONTRATO_MEDIDO",
      },
    ));
  });

  it("queues a BRL price with contracted quantity and a backend-valid source", async () => {
    render(
      <ServicePriceCatalogPage
        obraId={OBRA_ID}
        permissions={["FINANCEIRO_VISUALIZAR", "FINANCEIRO_ADMINISTRAR"]}
      />,
    );

    fireEvent.click(await screen.findByRole("button", {
      name: /novo preço para pavimentação cbuq/i,
    }));
    expect(screen.getByLabelText("Moeda")).toHaveValue("BRL");
    expect(screen.getByLabelText("Moeda")).toHaveAttribute("readonly");
    expect(screen.getByPlaceholderText("CONTRATO_MEDIDO")).toBeInTheDocument();
    fireEvent.change(screen.getByLabelText("Unidade"), {
      target: { value: "M2" },
    });
    fireEvent.change(screen.getByLabelText("Quantidade contratada"), {
      target: { value: "800,000" },
    });
    fireEvent.change(screen.getByLabelText("Valor unitário"), {
      target: { value: "125,50" },
    });
    fireEvent.change(screen.getByLabelText("Início da vigência"), {
      target: { value: "2026-07-22" },
    });
    fireEvent.change(screen.getByLabelText(/Fonte do preço/), {
      target: { value: "CONTRATO_MEDIDO" },
    });
    fireEvent.click(screen.getByRole("button", { name: /salvar offline/i }));

    // "M2" digitado vira o símbolo: quem lança medição escreve do teclado, e a
    // unidade gravada precisa ser a mesma independente de como foi escrita.
    await waitFor(() => expect(mocks.queueCreatePrice).toHaveBeenCalledWith(
      OBRA_ID,
      LOCAL_ROWS[0].service.id,
      {
        unit: "M²",
        currency: "BRL",
        unitPrice: "125,50",
        contractedQuantity: "800,000",
        validFrom: "2026-07-22",
        validTo: "",
        source: "CONTRATO_MEDIDO",
      },
    ));
  });
});
