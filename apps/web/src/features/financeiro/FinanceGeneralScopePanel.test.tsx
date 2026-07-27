// @vitest-environment jsdom

import { fireEvent, render, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";

import type { FinanceControlUnit } from "./financeiro.types";
import { FinanceGeneralScopePanel } from "./FinanceGeneralScopePanel";

const units: FinanceControlUnit[] = [
  {
    id: "unit-obra-1",
    tipo: "OBRA",
    obraId: "obra-1",
    ativoId: null,
    codigo: "CTR-204",
    nome: "Obra Alfa",
    status: "ATIVA",
    versao: 3,
  },
  {
    id: "unit-ativo-1",
    tipo: "ATIVO",
    obraId: null,
    ativoId: "ativo-1",
    codigo: "EQ-17",
    nome: "Escavadeira 17",
    status: "ATIVA",
    versao: 1,
  },
];

describe("FinanceGeneralScopePanel", () => {
  it("apresenta somente unidades reais recebidas e seleciona o registro inteiro", () => {
    const onSelect = vi.fn();

    render(
      <FinanceGeneralScopePanel
        units={units}
        selectedUnitId="unit-ativo-1"
        canAdminister={false}
        onSelect={onSelect}
        onChanged={vi.fn()}
      />,
    );

    expect(screen.getByText("2 unidades no escopo")).toBeInTheDocument();
    expect(screen.getByText("Obra Alfa")).toBeInTheDocument();
    expect(screen.getByText("Escavadeira 17")).toBeInTheDocument();
    expect(screen.queryByText("Via Nascentes")).not.toBeInTheDocument();

    const selected = screen.getByRole("button", {
      name: "Equipamento: Escavadeira 17 · EQ-17",
    });
    expect(selected).toHaveAttribute("aria-pressed", "true");

    fireEvent.click(
      screen.getByRole("button", { name: "Obra: Obra Alfa · CTR-204" }),
    );
    expect(onSelect).toHaveBeenCalledWith(units[0]);
  });
});
