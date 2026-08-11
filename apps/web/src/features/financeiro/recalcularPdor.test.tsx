// @vitest-environment jsdom
import { cleanup, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

const recalcularPdor = vi.hoisted(() => vi.fn());
const loadPdorRevenueSnapshot = vi.hoisted(() => vi.fn());

vi.mock("../obras/obrasApi", () => ({ recalcularPdor }));
vi.mock("./pdorRevenueCacheRepository", () => ({
  loadPdorRevenueSnapshot,
}));

const { FinancePdorSection } = await import("./FinancePdorSection");

beforeEach(() => {
  recalcularPdor.mockReset();
  recalcularPdor.mockResolvedValue(null);
  loadPdorRevenueSnapshot.mockReset();
  loadPdorRevenueSnapshot.mockResolvedValue(null);
});

afterEach(cleanup);

/*
 * O cálculo já era disparado por evento — a cada mudança de RDO —, e essa era
 * a única porta. Só que o snapshot é cache: quando o que ele projetava deixa de
 * existir e nenhum RDO muda depois disso, a projeção velha fica na tela sem
 * nada que a tire de lá.
 */
describe("recalcular a previsão à mão", () => {
  it("manda a obra recalcular e avisa quando termina", async () => {
    const user = userEvent.setup();
    render(<FinancePdorSection obraId="obra-1" />);

    await user.click(
      await screen.findByRole("button", { name: "Recalcular previsão" }),
    );

    await waitFor(() =>
      expect(recalcularPdor).toHaveBeenCalledWith("obra-1"),
    );
    await screen.findByText(/recalculada com os dados de agora/i);
  });

  it("mostra o motivo quando o servidor recusa", async () => {
    const user = userEvent.setup();
    recalcularPdor.mockRejectedValue(
      new Error("Obra arquivada não recebe cálculo."),
    );
    render(<FinancePdorSection obraId="obra-1" />);

    await user.click(
      await screen.findByRole("button", { name: "Recalcular previsão" }),
    );

    await screen.findByText("Obra arquivada não recebe cálculo.");
  });
});
