// @vitest-environment jsdom

import { fireEvent, render, screen, within } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";

import { FinanceSectionIndex } from "./FinanceSectionIndex";

describe("FinanceSectionIndex", () => {
  it("expõe somente o fluxo de receita e mantém a seleção funcional", () => {
    const onSelect = vi.fn();

    render(
      <FinanceSectionIndex activeSection="receita" onSelect={onSelect} />,
    );

    const navigation = screen.getByRole("navigation", {
      name: "Áreas do Financeiro",
    });
    expect(within(navigation).getByText("Receita operacional")).toBeInTheDocument();
    expect(
      within(navigation).getByRole("button", { name: "Rastreio de receita" }),
    ).toHaveAttribute("aria-current", "page");
    expect(
      within(navigation).getByRole("button", { name: "Serviços e preços" }),
    ).not.toHaveAttribute("aria-current");
    expect(within(navigation).getAllByRole("button")).toHaveLength(3);
    expect(navigation).not.toHaveTextContent(
      /visão geral|compras|notas fiscais|pagamentos|rateios|centros de custo|relatórios/i,
    );

    fireEvent.click(
      within(navigation).getByRole("button", { name: "Serviços e preços" }),
    );

    expect(onSelect).toHaveBeenCalledWith("servicos-precos");
  });
});
