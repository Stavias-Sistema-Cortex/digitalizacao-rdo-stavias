// @vitest-environment jsdom

import {
  cleanup,
  fireEvent,
  render,
  screen,
} from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { createEmptyRdo, createEmptyServicoExecutado } from "./createEmptyRdo";
import type { RdoCreationContextLookup } from "./rdoLookupApi";

const mocks = vi.hoisted(() => ({
  listRdoAttachments: vi.fn(),
  getCachedContext: vi.fn(),
  requireContext: vi.fn(),
  getLocalRdo: vi.fn(),
}));

vi.mock("../../lib/db/rdoAttachmentRepository", () => ({
  listRdoAttachments: mocks.listRdoAttachments,
  markRdoAttachmentRemoved: vi.fn(),
  putRdoAttachment: vi.fn(),
}));

vi.mock("./rdoCreationContextRepository", () => ({
  getCachedRdoCreationContext: mocks.getCachedContext,
  requireRdoCreationContext: mocks.requireContext,
}));

vi.mock("../../lib/db/rdoRepository", () => ({
  getLocalRdo: mocks.getLocalRdo,
}));

/*
 * A regra de catálogo entra de verdade, e não copiada para dentro do mock.
 * É ela que decide se a linha duplicada avisa e segura o envio, e uma cópia
 * aqui envelheceria sozinha — o teste passaria contra a versão do teste
 * enquanto o produto usasse outra.
 */
vi.mock("../../lib/db/localRdoService", async (importOriginal) => ({
  ...(await importOriginal<typeof import("../../lib/db/localRdoService")>()),
  rascunhoDifereDoQueEstaGravado: vi.fn().mockResolvedValue(false),
}));

vi.mock("./useRdoLocalPersistence", () => ({
  useRdoLocalPersistence: () => ({
    isSaving: false,
    isSyncing: false,
    message: "",
    error: "",
    saveLocally: vi.fn(),
    synchronize: vi.fn(),
  }),
}));

vi.mock("./RdoWorkforceEditor", () => ({
  RdoWorkforceEditor: () => <section aria-label="Equipe" />,
}));

import { RdoCreatePage } from "./RdoCreatePage";

function context(): RdoCreationContextLookup {
  return {
    obra: {
      id: "obra-a",
      codigoContrato: "CTR-A",
      codigoCw: "CW-A",
      nome: "Obra A",
      cliente: "Cliente",
      cidade: "Cidade",
      uf: "SP",
      rodovia: "BR-1",
      status: "ATIVA",
      version: 4,
      kmInicialEixo: null,
      kmFinalEixo: null,
    },
    data: "2026-07-22",
    nextNumberSuggestion: "RDO-1",
    previousRdo: null,
    previousWorkforce: [],
    programacoes: [],
    colaboradores: [],
    equipamentos: [],
    serviceCatalog: [],
    priceCatalog: [],
    coverage: {
      previousWorkforce: { status: "COMPLETE", complete: true, total: 0, returned: 0 },
      programacoes: { status: "COMPLETE", complete: true, total: 0, returned: 0 },
      colaboradores: { status: "COMPLETE", complete: true, total: 0, returned: 0 },
      equipamentos: { status: "COMPLETE", complete: true, total: 0, returned: 0 },
      serviceCatalog: { status: "COMPLETE", complete: true, total: 1, returned: 1 },
      priceCatalog: { status: "COMPLETE", complete: true, total: 1, returned: 1 },
    },
    provenance: {
      receiptVersion: 16,
      sourceVersion: 4,
      worksiteId: "obra-a",
      selectedDate: "2026-07-22",
      previousRdoId: null,
      generatedAt: "2026-07-22T10:00:00Z",
    },
    freshness: {
      status: "FRESH",
      sourceVersion: 4,
      generatedAt: "2026-07-22T10:00:00Z",
      staleAfter: "2026-07-22T11:00:00Z",
    },
  };
}

function rascunhoComFresagemNaCaixa() {
  return {
    ...createEmptyRdo(),
    id: "rdo-na-tela",
    obraId: "obra-a",
    dataRdo: "2026-07-22",
    syncStatus: "PENDING_SYNC" as const,
    servicosExecutados: [
      {
        ...createEmptyServicoExecutado(),
        localId: "servico-fresagem",
        serviceId: "service-fresagem",
        priceVersionId: "preco-1",
        servicoNome: "PAV-001 · Fresagem",
        unidade: "m3",
        trechoInicial: "12+300",
        trechoFinal: "12+400",
        pista: "Sul",
        faixa: "2",
        larguraM: 7,
        espessuraM: 0.09,
      },
    ],
  };
}

function renderizar() {
  render(
    <RdoCreatePage
      initialDraft={rascunhoComFresagemNaCaixa()}
      isExisting
      creationContext={context()}
      onBackToList={vi.fn()}
      onSaved={vi.fn()}
    />,
  );
}

function valores(rotulo: string): string[] {
  return screen
    .getAllByLabelText(rotulo)
    .map((campo) => (campo as HTMLInputElement).value);
}

beforeEach(() => {
  mocks.listRdoAttachments.mockResolvedValue([]);
  mocks.getCachedContext.mockResolvedValue(undefined);
  mocks.requireContext.mockReset();
  mocks.getLocalRdo.mockReset();
  mocks.getLocalRdo.mockResolvedValue(undefined);
});

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
});

/**
 * Três serviços caem na mesma caixa e repetem o mesmo trecho.
 *
 * <p>Fresagem, imprimação e CBUQ acontecem no mesmo quilômetro, na mesma pista,
 * com a mesma largura. Lançar os três era preencher os mesmos seis campos três
 * vezes — trabalho que só cria a chance de o segundo lançamento discordar do
 * primeiro sobre onde a obra estava.
 */
describe("duplicar um serviço executado", () => {
  it("repete o trecho e as medidas na linha nova", () => {
    renderizar();

    fireEvent.click(screen.getByRole("button", { name: "Duplicar" }));

    expect(valores("Km inicial")).toEqual(["12+300", "12+300"]);
    expect(valores("Km final")).toEqual(["12+400", "12+400"]);
    expect(valores("Pista")).toEqual(["Sul", "Sul"]);
    expect(valores("Faixa")).toEqual(["2", "2"]);
    expect(valores("Largura (m)")).toEqual(["7", "7"]);
    expect(valores("Espessura (m)")).toEqual(["0.09", "0.09"]);
  });

  /*
   * O serviço em branco é o ponto do recurso, não uma falta: é o único campo
   * que se veio trocar, e copiá-lo deixaria duas linhas idênticas subirem por
   * descuido, cada uma somando a mesma produção na medição.
   */
  it("deixa em branco o serviço da cópia", () => {
    renderizar();

    fireEvent.click(screen.getByRole("button", { name: "Duplicar" }));

    expect(valores("Tipo de serviço")).toEqual(["PAV-001 · Fresagem", ""]);
  });

  it("põe a cópia logo abaixo da linha copiada", () => {
    renderizar();

    fireEvent.click(screen.getByRole("button", { name: "Duplicar" }));

    expect(screen.getByText("Serviço 1")).toBeDefined();
    expect(screen.getByText("Serviço 2")).toBeDefined();
    expect(
      screen.getAllByRole("button", { name: "Duplicar" }),
    ).toHaveLength(2);
  });

  it("duplica a linha em que se clicou", () => {
    renderizar();

    fireEvent.click(screen.getByRole("button", { name: "Duplicar" }));
    fireEvent.change(screen.getAllByLabelText("Km inicial")[1], {
      target: { value: "20+000" },
    });
    fireEvent.click(screen.getAllByRole("button", { name: "Duplicar" })[1]);

    expect(valores("Km inicial")).toEqual(["12+300", "20+000", "20+000"]);
  });

  /*
   * A cópia sem serviço não pode subir calada. O servidor lê a linha sem
   * serviço, sem nome e sem quantidade como vazia e a descarta — o RDO não
   * quebra, mas a linha some do aparelho na releitura seguinte, levando o
   * trecho que alguém acabou de copiar. O aviso é o que transforma o
   * desaparecimento silencioso num pedido visível de um toque.
   */
  it("avisa que a cópia ainda espera um serviço do catálogo", () => {
    renderizar();

    expect(screen.queryByRole("alert")).toBeNull();

    fireEvent.click(screen.getByRole("button", { name: "Duplicar" }));

    expect(
      screen.getByText(
        /Selecione no catálogo um serviço com unidade válida/,
      ),
    ).toBeVisible();
  });

  /*
   * Duplicar é edição de rascunho, e rascunho é do aparelho: acontece com o
   * caminhão parado no acostamento, sem sinal. Nenhuma ida à rede pode estar
   * escondida no caminho.
   */
  it("duplica sem tocar na rede", () => {
    const rede = vi.spyOn(globalThis, "fetch");
    renderizar();

    fireEvent.click(screen.getByRole("button", { name: "Duplicar" }));

    expect(rede).not.toHaveBeenCalled();
  });
});
