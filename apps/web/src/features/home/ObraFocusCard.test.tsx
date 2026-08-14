import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it, vi } from "vitest";

import type {
  ObraLocalRecord,
  OperationalEventRecord,
} from "../../lib/db/db.types";
import { ObraFocusCard } from "./ObraFocusCard";

vi.mock("./ObraEvolucaoMapa", () => ({
  ObraEvolucaoMapa: () => <div>Mapa</div>,
}));

vi.mock("./ProgressChart", () => ({
  ProgressChart: () => <div>Gráfico</div>,
}));

function obra(updatedAt: string): ObraLocalRecord {
  return {
    id: "obra-1",
    codigoContrato: "CTR-1",
    nome: "Duplicação da SP-001",
    cliente: null,
    cidade: "Campinas",
    uf: "SP",
    rodovia: "SP-001",
    status: "ATIVA",
    observacoes: null,
    latitude: null,
    longitude: null,
    valorContratual: null,
    arquivadoEm: null,
    updatedAt,
  };
}

describe("ObraFocusCard timestamps", () => {
  it.each([
    ["canonical instant", "2026-07-22T01:30:00.000Z", "21/07/2026, 22:30"],
    ["legacy civil clock", "2026-07-22T01:30:00", "22/07/2026, 01:30"],
  ])("shows %s with the mixed timestamp contract", (_case, updatedAt, expected) => {
    const current = obra(updatedAt);
    const html = renderToStaticMarkup(
      <ObraFocusCard
        obra={current}
        obraOptions={[current]}
        onSelectObra={() => {}}
        snapshots={[]}
        events={[]}
        latestRdo={null}
      />,
    );

    expect(html).toContain(expected);
  });

  it("counts a legacy UTC event at the 30-day boundary independently of device timezone", () => {
    vi.useFakeTimers();
    vi.setSystemTime(new Date("2026-08-14T12:00:00Z"));
    const current = obra("2026-08-14T12:00:00Z");
    const occurrence: OperationalEventRecord = {
      id: "event-1",
      type: "OCORRENCIA_REGISTRADA",
      principalEntity: { tipo: "OBRA", id: current.id },
      principalEntityKey: `OBRA:${current.id}`,
      relatedEntities: [],
      obraId: current.id,
      rdoId: null,
      colaboradorId: null,
      occurredAt: "2026-07-15T09:30:00",
      syncedAt: null,
      origin: "SYNC",
      responsibleUserId: null,
      responsibleUserName: null,
      payload: {},
      syncStatus: "SYNCED",
      schemaVersion: 13,
    };

    try {
      const html = renderToStaticMarkup(
        <ObraFocusCard
          obra={current}
          obraOptions={[current]}
          onSelectObra={() => {}}
          snapshots={[]}
          events={[occurrence]}
          latestRdo={null}
        />,
      );

      expect(html).toContain(
        "<dt>Ocorrências nos últimos 30 dias</dt><dd class=\"metric-value\">0</dd>",
      );
    } finally {
      vi.useRealTimers();
    }
  });
});
