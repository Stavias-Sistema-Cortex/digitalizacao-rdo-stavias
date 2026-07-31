// @vitest-environment jsdom
import { cleanup, render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";

import { TrechoPeriodoFiltro } from "./TrechoPeriodoFiltro";
import type { DiaExecutado } from "./trechoGeometry";

const dias: DiaExecutado[] = [
  {
    data: "2026-03-02",
    totalSegmentos: 2,
    totalRdos: 1,
    extensaoM: 1500,
    massaTonelada: 380,
    kmMin: 171,
    kmMax: 172,
  },
  {
    data: "2026-03-05",
    totalSegmentos: 1,
    totalRdos: 1,
    extensaoM: 500,
    massaTonelada: 120,
    kmMin: 170,
    kmMax: 171,
  },
];

function renderFiltro(overrides: Partial<Parameters<typeof TrechoPeriodoFiltro>[0]> = {}) {
  const props = {
    disponivel: { de: "2026-03-02", ate: "2026-03-05" },
    valor: { de: null, ate: null },
    dias,
    diaSelecionado: null,
    onAlterarPeriodo: vi.fn(),
    onSelecionarDia: vi.fn(),
    ...overrides,
  };
  return { ...render(<TrechoPeriodoFiltro {...props} />), props };
}

afterEach(cleanup);

describe("TrechoPeriodoFiltro", () => {
  it("mostra um marco por dia com produção lançada", () => {
    renderFiltro();

    const linha = screen.getByRole("list", {
      name: "Dias com produção lançada",
    });
    expect(linha.querySelectorAll("li")).toHaveLength(2);
    expect(screen.getByText("02/03")).toBeInTheDocument();
    expect(screen.getByText("05/03")).toBeInTheDocument();
  });

  it("limita os campos ao período que a obra realmente registrou", () => {
    renderFiltro();

    const de = screen.getByLabelText("De") as HTMLInputElement;
    const ate = screen.getByLabelText("Até") as HTMLInputElement;
    expect(de.min).toBe("2026-03-02");
    expect(ate.max).toBe("2026-03-05");
  });

  it("seleciona e desmarca um dia da linha do tempo", async () => {
    const user = userEvent.setup();
    const { props } = renderFiltro();

    await user.click(screen.getByText("02/03"));
    expect(props.onSelecionarDia).toHaveBeenCalledWith("2026-03-02");

    cleanup();
    const marcado = renderFiltro({ diaSelecionado: "2026-03-02" });
    await user.click(screen.getByText("02/03"));
    expect(marcado.props.onSelecionarDia).toHaveBeenCalledWith(null);
  });

  it("informa o dia ativo por aria-pressed", () => {
    renderFiltro({ diaSelecionado: "2026-03-05" });

    const botoes = screen.getAllByRole("button", { pressed: true });
    expect(botoes).toHaveLength(1);
    expect(botoes[0].textContent).toContain("05/03");
  });

  it("desabilita a volta ao período completo quando nada está filtrado", () => {
    renderFiltro();

    expect(
      screen.getByRole("button", { name: "Todo o período" }),
    ).toBeDisabled();
  });

  it("limpa dia e período ao voltar para o intervalo completo", async () => {
    const user = userEvent.setup();
    const { props } = renderFiltro({ valor: { de: "2026-03-02", ate: null } });

    await user.click(screen.getByRole("button", { name: "Todo o período" }));

    expect(props.onSelecionarDia).toHaveBeenCalledWith(null);
    expect(props.onAlterarPeriodo).toHaveBeenCalledWith({
      de: null,
      ate: null,
    });
  });

  it("explica a ausência de datas em vez de mostrar uma linha vazia", () => {
    renderFiltro({ disponivel: { de: null, ate: null }, dias: [] });

    expect(
      screen.getByText(/Nenhuma data de execução registrada ainda/),
    ).toBeInTheDocument();
    expect(screen.queryByRole("list")).not.toBeInTheDocument();
  });

  it("avisa quando o período escolhido não tem produção", () => {
    renderFiltro({ dias: [], valor: { de: "2026-03-20", ate: "2026-03-25" } });

    expect(
      screen.getByText("Nenhuma produção lançada dentro do período escolhido."),
    ).toBeInTheDocument();
  });
});
