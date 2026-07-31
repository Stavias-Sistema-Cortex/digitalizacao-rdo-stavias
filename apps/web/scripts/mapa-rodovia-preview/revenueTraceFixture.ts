/** Substitui `revenueTraceCacheRepository` no harness de verificação visual. */
import { OBRA_FIXTURE } from "./fixtures";

function row(overrides: Record<string, unknown>) {
  return {
    worksiteId: OBRA_FIXTURE.id,
    worksiteName: OBRA_FIXTURE.nome,
    rdoId: "00000000-0000-4000-8000-0000000000f4",
    rdoNumber: "0042",
    executionId: "exec-1",
    executionDate: "2026-03-02",
    serviceId: "serv-recape",
    serviceCode: "REC-01",
    serviceName: "Recapeamento CBUQ faixa 4C",
    priceVersionId: "preco-1",
    priceVersion: 3,
    quantity: 5250,
    unit: "m²",
    unitPrice: "42.50",
    currency: "BRL",
    revenue: "223125.00",
    coverageCode: "COMPLETE_ACCEPTED_EXACT",
    revenueEvidenceId: "ev-1",
    revenueEventId: "evt-1",
    eventCommitSequence: 10,
    acceptedAt: "2026-03-02T20:00:00Z",
    ...overrides,
  };
}

const ROWS = [
  row({}),
  row({
    executionId: "exec-2",
    rdoId: "00000000-0000-4000-8000-0000000000f5",
    rdoNumber: "0043",
    executionDate: "2026-03-04",
    quantity: 3500,
    revenue: "148750.00",
  }),
  row({
    executionId: "exec-3",
    serviceId: "serv-fresagem",
    serviceCode: "FRE-02",
    serviceName: "Fresagem descontínua de pavimento",
    quantity: 8750,
    unitPrice: "9.80",
    revenue: "85750.00",
  }),
  row({
    executionId: "exec-4",
    serviceId: "serv-pintura",
    serviceCode: "SIN-07",
    serviceName: "Pintura de ligação",
    quantity: 8750,
    unitPrice: "2.10",
    revenue: "18375.00",
  }),
];

export function loadRevenueTraceSnapshot(request: {
  obraId: string;
  from: string;
  to: string;
}) {
  const rows = ROWS.filter(
    (item) =>
      item.executionDate >= request.from && item.executionDate <= request.to,
  );
  return Promise.resolve({
    response: {
      from: request.from,
      to: request.to,
      totalRevenue: rows
        .reduce((total, item) => total + Number(item.revenue), 0)
        .toFixed(2),
      evidenceCount: rows.length,
      rows,
      nextCursor: null,
      coverage: "COMPLETE",
      highWaterMark: 10,
    },
    mode: "ONLINE",
    fetchedAt: "2026-03-06T18:00:00.000Z",
    source: "SERVER_CONFIRMED",
    coverage: "COMPLETE",
  });
}
