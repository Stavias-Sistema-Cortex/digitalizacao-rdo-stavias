import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it } from "vitest";

import { FinanceRevenueTracePage } from "./FinanceRevenueTracePage";
import type { RevenueTraceRow } from "./servicePriceApi";

const ROW: RevenueTraceRow = {
  worksiteId: "obra-1",
  worksiteName: "BR-101",
  rdoId: "rdo-1",
  rdoNumber: "RDO-014",
  executionId: "execution-1",
  executionDate: "2026-07-22",
  serviceId: "service-1",
  serviceCode: "PAV.CBUQ",
  serviceName: "Aplicação de CBUQ",
  priceVersionId: "price-1",
  priceVersion: 3,
  quantity: "10.000",
  unit: "T",
  unitPrice: "125.0000",
  currency: "BRL",
  revenue: "1250.00",
  coverageCode: "ACCEPTED_EXACT",
  revenueEvidenceId: "evidence-1",
  revenueEventId: "event-1",
  eventCommitSequence: 812,
  acceptedAt: "2026-07-22T15:00:00Z",
};

describe("FinanceRevenueTracePage", () => {
  it("mostra quantidade vezes o preço congelado e soma apenas evidências visíveis", () => {
    const html = renderToStaticMarkup(
      <FinanceRevenueTracePage obraId="obra-1" rows={[ROW]} />,
    );

    expect(html).toContain("10,000 × R$ 125,0000");
    expect(html).toContain("R$ 1.250,00");
    expect(html).toContain("RDO-014");
    expect(html).toContain("ACEITA EXATA");
    expect(html).not.toMatch(/margem|custo previsto|receita estimada/i);
  });
});
