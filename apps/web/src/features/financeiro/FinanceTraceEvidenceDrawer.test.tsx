import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it } from "vitest";

import { FinanceTraceEvidenceDrawer } from "./FinanceTraceEvidenceDrawer";
import type { RevenueTraceEvidence } from "./servicePriceApi";

describe("FinanceTraceEvidenceDrawer", () => {
  it("expõe toda a cadeia ontológica e seus IDs sem inventar relações", () => {
    const detail: RevenueTraceEvidence = {
      row: {
        worksiteId: "obra-1", worksiteName: "Obra", rdoId: "rdo-1",
        rdoNumber: "RDO-14", executionId: "execution-1",
        executionDate: "2026-07-22", serviceId: "service-1",
        serviceCode: "PAV", serviceName: "Pavimentação",
        priceVersionId: "price-1", priceVersion: 2, quantity: "1.000",
        unit: "T", unitPrice: "100.0000", currency: "BRL",
        revenue: "100.00", coverageCode: "ACCEPTED_EXACT",
        revenueEvidenceId: "evidence-1", revenueEventId: "event-1",
        eventCommitSequence: 22, acceptedAt: "2026-07-22T10:00:00Z",
      },
      ontologyLinks: [{
        sourceType: "RDO_EXECUTION",
        sourceId: "execution-1",
        relationType: "VALUED_BY",
        targetType: "SERVICE_PRICE_VERSION",
        targetId: "price-1",
        active: true,
      }],
    };

    const html = renderToStaticMarkup(
      <FinanceTraceEvidenceDrawer detail={detail} onClose={() => undefined} />,
    );

    for (const id of [
      "rdo-1", "execution-1", "service-1", "price-1", "evidence-1", "event-1",
    ]) {
      expect(html).toContain(id);
    }
    expect(html).toContain("VALUED_BY");
  });
});
