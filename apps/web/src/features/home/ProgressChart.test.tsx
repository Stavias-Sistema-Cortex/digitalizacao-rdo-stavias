// @vitest-environment jsdom

import { cleanup, render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";

import { ProgressChart } from "./ProgressChart";
import type { MonthlyPoint } from "./progressSeries";

afterEach(cleanup);

const PONTOS: MonthlyPoint[] = [
  { month: "2026-05", fisicoPct: 30, apontadaPct: 45, pdorPct: 60 },
  { month: "2026-06", fisicoPct: 48, apontadaPct: 80, pdorPct: null },
  { month: "2026-07", fisicoPct: 55, apontadaPct: 90, pdorPct: 75 },
];

function svgPaths(): SVGPathElement[] {
  return [
    ...document.querySelectorAll<SVGPathElement>("svg path"),
  ];
}

describe("ProgressChart", () => {
  it("desenha as três séries e abre buraco onde o valor é nulo", () => {
    render(
      <ProgressChart
        points={PONTOS}
        period="6M"
        onPeriodChange={() => undefined}
      />,
    );

    const paths = svgPaths();
    expect(paths).toHaveLength(3);
    // Cada série vira um caminho contínuo com um M inicial…
    const continuas = paths.filter(
      (path) => (path.getAttribute("d") ?? "").split("M").length === 2,
    );
    // …menos a receita, que tem um mês sem base canônica: dois segmentos
    // (dois M), porque buraco não se liga com reta — seria inventar valor.
    const comBuraco = paths.filter(
      (path) => (path.getAttribute("d") ?? "").split("M").length === 3,
    );
    expect(continuas).toHaveLength(2);
    expect(comBuraco).toHaveLength(1);

    // Os três meses aparecem no eixo.
    expect(screen.getByText("Mai")).toBeTruthy();
    expect(screen.getByText("Jun")).toBeTruthy();
    expect(screen.getByText("Jul")).toBeTruthy();
  });

  it("a legenda desliga uma série sem apagar as outras", async () => {
    render(
      <ProgressChart
        points={PONTOS}
        period="6M"
        onPeriodChange={() => undefined}
      />,
    );

    await userEvent.click(
      screen.getByRole("button", { name: /Produção apontada/ }),
    );

    expect(svgPaths()).toHaveLength(2);
    expect(
      screen
        .getByRole("button", { name: /Produção apontada/ })
        .getAttribute("aria-pressed"),
    ).toBe("false");
  });

  it("o seletor de período repassa a escolha", async () => {
    const onPeriodChange = vi.fn();
    render(
      <ProgressChart
        points={PONTOS}
        period="6M"
        onPeriodChange={onPeriodChange}
      />,
    );

    await userEvent.click(screen.getByRole("button", { name: "3m" }));
    expect(onPeriodChange).toHaveBeenCalledWith("3M");
  });

  it("vazio diz de onde os pontos nascem, não só que faltam", () => {
    render(
      <ProgressChart
        points={[]}
        period="6M"
        onPeriodChange={() => undefined}
      />,
    );

    expect(
      screen.getByText(/produção precisa de programação e apontamentos/i),
    ).toBeTruthy();
    expect(document.querySelector("svg")).toBeNull();
  });
});
