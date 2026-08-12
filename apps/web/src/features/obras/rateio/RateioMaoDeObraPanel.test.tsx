// @vitest-environment jsdom

import {
  cleanup,
  fireEvent,
  render,
  screen,
  waitFor,
  within,
} from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import type { ObraLocalRecord } from "../../../lib/db/db.types";

import { RateioMaoDeObraPanel } from "./RateioMaoDeObraPanel";

const mocks = vi.hoisted(() => ({
  lerLocal: vi.fn(),
  buscarServidor: vi.fn(),
}));

vi.mock("./apontamentosDoAparelho", async () => {
  const real = await vi.importActual<
    typeof import("./apontamentosDoAparelho")
  >("./apontamentosDoAparelho");
  return { ...real, lerApontamentosDoAparelho: mocks.lerLocal };
});

vi.mock("./rateioApi", () => ({
  buscarApontamentosDoServidor: mocks.buscarServidor,
}));

function obra(id: string, nome: string, status = "ATIVA"): ObraLocalRecord {
  return {
    id,
    codigoContrato: `C-${id}`,
    nome,
    cliente: null,
    cidade: null,
    uf: null,
    rodovia: null,
    status,
    observacoes: null,
    latitude: null,
    longitude: null,
    valorContratual: null,
    updatedAt: "2026-07-01T00:00:00.000Z",
  };
}

const OBRAS = [
  obra("obra-a", "Obra Norte"),
  obra("obra-b", "Obra Sul"),
  obra("obra-c", "Obra Parada", "CONCLUIDA"),
];

function apontamento(
  data: string,
  obraId: string,
  extras: Record<string, unknown> = {},
) {
  return {
    colaboradorId: "col-1",
    nome: "PESSOA UM",
    funcao: "AJUDANTE DE OBRA",
    obraId,
    data,
    encarregado: "FRENTE A",
    rdoId: `rdo-${data}-${obraId}`,
    ...extras,
  };
}

function leitura(apontamentos: unknown[], extras: Record<string, number> = {}) {
  return {
    apontamentos,
    rdosLidos: apontamentos.length,
    rdosSemConteudo: 0,
    rdosSemMaoDeObra: 0,
    ...extras,
  };
}

beforeEach(() => {
  mocks.lerLocal.mockReset();
  mocks.buscarServidor.mockReset();
  Object.defineProperty(window.navigator, "onLine", {
    configurable: true,
    value: false,
  });
});

afterEach(() => {
  cleanup();
});

describe("a tela do rateio", () => {
  it("monta a matriz com uma linha por pessoa e o percentual por obra", async () => {
    mocks.lerLocal.mockResolvedValue(
      leitura([
        apontamento("2026-07-01", "obra-a"),
        apontamento("2026-07-02", "obra-a"),
        apontamento("2026-07-03", "obra-b"),
      ]),
    );

    render(<RateioMaoDeObraPanel obras={OBRAS} mesInicial="2026-07" />);

    const linha = await screen.findByRole("row", { name: /PESSOA UM/ });
    expect(within(linha).getByText("AJUDANTE DE OBRA")).toBeInTheDocument();
    // Dois dias de três numa obra, um na outra.
    expect(within(linha).getByText("66,7%")).toBeInTheDocument();
    expect(within(linha).getByText("33,3%")).toBeInTheDocument();
    // A frente abre o bloco de linhas dentro da matriz.
    const tabela = screen.getByRole("table");
    expect(within(tabela).getByText("FRENTE A")).toBeInTheDocument();
  });

  it("abre o mês inteiro em colunas, não só os dias trabalhados", async () => {
    mocks.lerLocal.mockResolvedValue(
      leitura([apontamento("2026-07-01", "obra-a")]),
    );

    render(<RateioMaoDeObraPanel obras={OBRAS} mesInicial="2026-07" />);

    await screen.findByRole("row", { name: /PESSOA UM/ });
    const [cabecalho] = screen.getAllByRole("rowgroup");
    // Nome, função, 31 dias e a coluna da obra que apareceu.
    expect(within(cabecalho).getAllByRole("columnheader")).toHaveLength(
      2 + 31 + 1,
    );
  });

  it("diz que o retrato é do aparelho quando não há rede", async () => {
    mocks.lerLocal.mockResolvedValue(
      leitura([apontamento("2026-07-01", "obra-a")]),
    );

    render(<RateioMaoDeObraPanel obras={OBRAS} mesInicial="2026-07" />);

    expect(
      await screen.findByText(/Retrato deste aparelho/),
    ).toBeInTheDocument();
    expect(mocks.buscarServidor).not.toHaveBeenCalled();
  });

  it("troca pelo retrato do servidor quando há rede", async () => {
    Object.defineProperty(window.navigator, "onLine", {
      configurable: true,
      value: true,
    });
    mocks.lerLocal.mockResolvedValue(leitura([]));
    mocks.buscarServidor.mockResolvedValue({
      ...leitura([apontamento("2026-07-01", "obra-a")]),
      completo: true,
    });

    render(<RateioMaoDeObraPanel obras={OBRAS} mesInicial="2026-07" />);

    expect(await screen.findByText(/Retrato do servidor/)).toBeInTheDocument();
    expect(
      await screen.findByRole("row", { name: /PESSOA UM/ }),
    ).toBeInTheDocument();
  });

  /*
   * Servidor calado não pode apagar a tela: o que já está no aparelho continua
   * valendo, e a faixa diz de onde veio.
   */
  it("fica com o retrato do aparelho quando o servidor não responde", async () => {
    Object.defineProperty(window.navigator, "onLine", {
      configurable: true,
      value: true,
    });
    mocks.lerLocal.mockResolvedValue(
      leitura([apontamento("2026-07-01", "obra-a")]),
    );
    mocks.buscarServidor.mockRejectedValue(new Error("sem rede"));

    render(<RateioMaoDeObraPanel obras={OBRAS} mesInicial="2026-07" />);

    expect(
      await screen.findByRole("row", { name: /PESSOA UM/ }),
    ).toBeInTheDocument();
    await waitFor(() =>
      expect(screen.getByText(/Retrato deste aparelho/)).toBeInTheDocument(),
    );
  });

  it("avisa quantos RDOs ainda não desceram inteiros", async () => {
    mocks.lerLocal.mockResolvedValue(
      leitura([apontamento("2026-07-01", "obra-a")], { rdosSemConteudo: 4 }),
    );

    render(<RateioMaoDeObraPanel obras={OBRAS} mesInicial="2026-07" />);

    expect(
      await screen.findByText(/4 RDOs ainda não desceram/),
    ).toBeInTheDocument();
  });

  it("esconde a obra que não está em execução, sem falsear o percentual", async () => {
    mocks.lerLocal.mockResolvedValue(
      leitura([
        apontamento("2026-07-01", "obra-a"),
        apontamento("2026-07-02", "obra-c"),
      ]),
    );

    render(<RateioMaoDeObraPanel obras={OBRAS} mesInicial="2026-07" />);

    const linha = await screen.findByRole("row", { name: /PESSOA UM/ });
    // Metade do tempo foi para uma obra que a tela não mostra: o que aparece
    // continua sendo 50%, e não 100%.
    expect(within(linha).getByText("50%")).toBeInTheDocument();
    expect(screen.queryByText("Obra Parada")).not.toBeInTheDocument();

    fireEvent.click(screen.getByLabelText(/Só obras em execução/));
    await waitFor(() =>
      expect(screen.getAllByText("Obra Parada").length).toBeGreaterThan(0),
    );
  });

  it("procura pelo nome da pessoa", async () => {
    mocks.lerLocal.mockResolvedValue(
      leitura([
        apontamento("2026-07-01", "obra-a"),
        apontamento("2026-07-01", "obra-a", {
          colaboradorId: "col-2",
          nome: "OUTRA PESSOA",
        }),
      ]),
    );

    render(<RateioMaoDeObraPanel obras={OBRAS} mesInicial="2026-07" />);
    await screen.findByRole("row", { name: /PESSOA UM/ });

    fireEvent.change(screen.getByPlaceholderText(/Procurar pessoa/), {
      target: { value: "outra" },
    });

    await waitFor(() =>
      expect(screen.queryByRole("row", { name: /^PESSOA UM/ })).toBeNull(),
    );
    expect(
      screen.getByRole("row", { name: /OUTRA PESSOA/ }),
    ).toBeInTheDocument();
  });

  it("anda de mês e relê o período", async () => {
    mocks.lerLocal.mockResolvedValue(leitura([]));

    render(<RateioMaoDeObraPanel obras={OBRAS} mesInicial="2026-07" />);
    expect(await screen.findByText("Julho de 2026")).toBeInTheDocument();

    fireEvent.click(screen.getByLabelText("Próximo mês"));

    expect(await screen.findByText("Agosto de 2026")).toBeInTheDocument();
    await waitFor(() =>
      expect(mocks.lerLocal).toHaveBeenCalledWith({
        inicio: "2026-08-01",
        fim: "2026-08-31",
      }),
    );
  });

  /*
   * O rateio não guarda retrato próprio: ele é sempre recalculado do que os
   * RDOs dizem agora. É isso que faz o RDO apagado sumir daqui junto — não há
   * cópia guardada que possa sobreviver ao apagamento e continuar contando
   * dias para uma obra.
   */
  it("refaz a conta quando a sincronização termina", async () => {
    mocks.lerLocal.mockResolvedValue(
      leitura([apontamento("2026-07-01", "obra-a")]),
    );

    render(<RateioMaoDeObraPanel obras={OBRAS} mesInicial="2026-07" />);
    await screen.findByRole("row", { name: /PESSOA UM/ });
    expect(mocks.lerLocal).toHaveBeenCalledTimes(1);

    // O RDO foi apagado em outra máquina e a sincronização trouxe a novidade.
    mocks.lerLocal.mockResolvedValue(leitura([]));
    window.dispatchEvent(new Event("cortex:sync-completed"));

    await waitFor(() =>
      expect(screen.queryByRole("row", { name: /PESSOA UM/ })).toBeNull(),
    );
  });

  it("explica o vazio conforme a causa", async () => {
    mocks.lerLocal.mockResolvedValue(leitura([]));
    render(<RateioMaoDeObraPanel obras={OBRAS} mesInicial="2026-07" />);

    expect(await screen.findByText(/Nenhum RDO neste período/)).toBeInTheDocument();
  });

  it("não deixa exportar o que não existe", async () => {
    mocks.lerLocal.mockResolvedValue(leitura([]));
    render(<RateioMaoDeObraPanel obras={OBRAS} mesInicial="2026-07" />);

    expect(
      await screen.findByRole("button", { name: /Exportar planilha/ }),
    ).toBeDisabled();
  });
});
