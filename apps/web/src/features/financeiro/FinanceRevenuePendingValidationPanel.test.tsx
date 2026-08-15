// @vitest-environment jsdom

import { cleanup, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

const {
  fetchPendingRevenueExecutions,
  decidePendingRevenueExecution,
} = vi.hoisted(() => ({
  fetchPendingRevenueExecutions: vi.fn(),
  decidePendingRevenueExecution: vi.fn(),
}));

vi.mock("./revenuePendingApi", () => ({
  fetchPendingRevenueExecutions,
  decidePendingRevenueExecution,
}));

import { FinanceRevenuePendingValidationPanel } from "./FinanceRevenuePendingValidationPanel";

const OBRA_ID = "00000000-0000-4000-8000-000000000101";
const RDO_ID = "00000000-0000-4000-8000-000000000102";
const EXECUTION_ID = "00000000-0000-4000-8000-000000000103";

const EXACT_ROW = {
  worksiteId: OBRA_ID,
  worksiteName: "Obra real",
  rdoId: RDO_ID,
  rdoNumber: "RDO-0030",
  executionId: EXECUTION_ID,
  serviceId: "00000000-0000-4000-8000-000000000104",
  serviceCode: "FRES.001",
  serviceName: "Fresagem funcional",
  executionDate: "2026-08-14",
  quantity: "2.500",
  unit: "M2",
  rdoEntityVersion: 19,
  approvalState: "REGISTERED" as const,
  legacyEvidenceState: "NONE" as const,
  priceState: "EXACT_ACTIVE" as const,
  priceReason: "EXACT_ACTIVE_PRICE",
  currentUnitPrice: "500.0000",
  currency: "BRL" as const,
  evidenceUnitPrice: null,
  evidenceCurrency: null,
};

const LEGACY_VERIFIABLE_ROW = {
  ...EXACT_ROW,
  approvalState: "LEGACY_UNVERIFIED" as const,
  legacyEvidenceState: "VERIFIABLE" as const,
  priceState: "UNAVAILABLE" as const,
  priceReason: "EXACT_PRICE_NOT_FOUND",
  currentUnitPrice: null,
  currency: null,
  evidenceUnitPrice: "487.5000",
  evidenceCurrency: "BRL" as const,
};

const LEGACY_INVALID_ROW = {
  ...LEGACY_VERIFIABLE_ROW,
  legacyEvidenceState: "INVALID" as const,
  priceState: "EXACT_ACTIVE" as const,
  priceReason: "EXACT_ACTIVE_PRICE",
  currentUnitPrice: "999.0000",
  currency: "BRL" as const,
  evidenceUnitPrice: null,
  evidenceCurrency: null,
};

afterEach(() => {
  cleanup();
  vi.clearAllMocks();
});

beforeEach(() => {
  Object.defineProperty(navigator, "onLine", {
    configurable: true,
    value: true,
  });
  fetchPendingRevenueExecutions.mockResolvedValue([EXACT_ROW]);
  decidePendingRevenueExecution.mockResolvedValue(undefined);
});

describe("FinanceRevenuePendingValidationPanel", () => {
  it("mostra a produção real pendente sem somá-la como receita", async () => {
    render(
      <FinanceRevenuePendingValidationPanel
        obraId={OBRA_ID}
        canApprove={false}
      />,
    );

    expect(await screen.findByText("Fresagem funcional")).toBeVisible();
    expect(screen.getByText("RDO-0030")).toBeVisible();
    expect(screen.getByText("2,500 M2")).toBeVisible();
    expect(screen.getByText("R$ 500,0000 / M2")).toBeVisible();
    expect(screen.getByText(/somente quem tem FINANCEIRO_APROVAR/i))
      .toBeVisible();
    expect(screen.queryByRole("button", { name: "Validar" }))
      .not.toBeInTheDocument();
    expect(document.body).not.toHaveTextContent(/R\$ 1\.250,00/);
  });

  it("valida usando a versão canônica e recarrega as projeções depois da confirmação", async () => {
    const user = userEvent.setup();
    const onDecisionApplied = vi.fn();
    render(
      <FinanceRevenuePendingValidationPanel
        obraId={OBRA_ID}
        canApprove
        onDecisionApplied={onDecisionApplied}
      />,
    );

    await user.click(await screen.findByRole("button", { name: "Validar" }));

    await waitFor(() => {
      expect(decidePendingRevenueExecution).toHaveBeenCalledWith(
        expect.objectContaining({
          rdoId: RDO_ID,
          executionId: EXECUTION_ID,
          decision: "VALIDAR",
          justification: null,
          baseVersion: 19,
        }),
      );
    });
    expect(onDecisionApplied).toHaveBeenCalledTimes(1);
    expect(await screen.findByText(/validada e registrada na receita/i))
      .toBeVisible();
  });

  it("não deixa validar quando não existe preço exato vigente", async () => {
    fetchPendingRevenueExecutions.mockResolvedValue([{
      ...EXACT_ROW,
      priceState: "UNAVAILABLE",
      priceReason: "EXACT_PRICE_NOT_FOUND",
      currentUnitPrice: null,
      currency: null,
    }]);
    render(
      <FinanceRevenuePendingValidationPanel obraId={OBRA_ID} canApprove />,
    );

    expect(await screen.findByText(/não há preço exato vigente/i)).toBeVisible();
    expect(screen.getByRole("button", { name: "Validar" }))
      .toBeDisabled();
    expect(decidePendingRevenueExecution).not.toHaveBeenCalled();
  });

  it("valida uma evidência legada verificável pelo preço nela persistido, não pelo preço atual", async () => {
    const user = userEvent.setup();
    fetchPendingRevenueExecutions.mockResolvedValue([LEGACY_VERIFIABLE_ROW]);
    render(
      <FinanceRevenuePendingValidationPanel obraId={OBRA_ID} canApprove />,
    );

    expect(await screen.findByText(/preço persistido na evidência/i))
      .toBeVisible();
    expect(screen.getByText("R$ 487,5000 / M2")).toBeVisible();
    expect(screen.queryByText(/preço exato vigente/i)).not.toBeInTheDocument();

    await user.click(screen.getByRole("button", { name: "Validar" }));
    await waitFor(() => {
      expect(decidePendingRevenueExecution).toHaveBeenCalledWith(
        expect.objectContaining({
          decision: "VALIDAR",
          baseVersion: 19,
        }),
      );
    });
  });

  it("oferece somente rejeição quando a evidência legada é inválida", async () => {
    fetchPendingRevenueExecutions.mockResolvedValue([LEGACY_INVALID_ROW]);
    render(
      <FinanceRevenuePendingValidationPanel obraId={OBRA_ID} canApprove />,
    );

    expect(await screen.findByText(/evidência histórica não é verificável/i))
      .toBeVisible();
    expect(screen.queryByRole("button", { name: "Validar" }))
      .not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Rejeitar" }))
      .toBeEnabled();
  });

  it("exige justificativa antes de rejeitar uma execução", async () => {
    const user = userEvent.setup();
    render(
      <FinanceRevenuePendingValidationPanel obraId={OBRA_ID} canApprove />,
    );

    await user.click(await screen.findByRole("button", { name: "Rejeitar" }));
    const confirm = screen.getByRole("button", {
      name: "Confirmar rejeição",
    });
    expect(confirm).toBeDisabled();

    await user.type(
      screen.getByRole("textbox", { name: /justificativa da rejeição/i }),
      "Medição precisa ser corrigida.",
    );
    await user.click(confirm);

    await waitFor(() => {
      expect(decidePendingRevenueExecution).toHaveBeenCalledWith(
        expect.objectContaining({
          decision: "REJEITAR",
          justification: "Medição precisa ser corrigida.",
          baseVersion: 19,
        }),
      );
    });
  });
});
