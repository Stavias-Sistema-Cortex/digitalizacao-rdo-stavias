// @vitest-environment jsdom

import { cleanup, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";

import type { ObraLocalRecord } from "../../lib/db/db.types";
import { ObraEvolucaoMapa } from "./ObraEvolucaoMapa";

const carregarMapaObra = vi.hoisted(() => vi.fn());

vi.mock("../obras/map/obraMapApi", async (importOriginal) => {
  const actual = await importOriginal<
    typeof import("../obras/map/obraMapApi")
  >();
  return { ...actual, carregarMapaObra };
});

vi.mock("../obras/map/LeafletTrechoMap", () => ({
  LeafletTrechoMap: () => <div>Mapa</div>,
}));

const OBRA: ObraLocalRecord = {
  id: "obra-1",
  codigoContrato: "CTR-1",
  nome: "SP-001",
  cliente: null,
  cidade: null,
  uf: "SP",
  rodovia: "SP-001",
  status: "ATIVA",
  observacoes: null,
  latitude: -22.9,
  longitude: -47.1,
  valorContratual: null,
  arquivadoEm: null,
  updatedAt: "2026-07-22T01:30:00.000Z",
};

afterEach(() => {
  cleanup();
  carregarMapaObra.mockReset();
});

describe("ObraEvolucaoMapa timestamps", () => {
  it("shows a cached reading instant in Brasília", async () => {
    carregarMapaObra.mockResolvedValue({
      dados: {
        obra: {
          id: OBRA.id,
          nome: OBRA.nome,
          latitude: OBRA.latitude,
          longitude: OBRA.longitude,
        },
        features: [{
          id: "trecho-1",
          categoria: "TRECHO_DESENHADO",
          objetoTipo: "OBRA",
          objetoId: OBRA.id,
          geometry: {
            type: "LineString",
            coordinates: [[-47.1, -22.9], [-47.0, -22.8]],
          },
          properties: {},
          fonte: "DISPOSITIVO",
          versao: 1,
          validoDesde: "2026-07-21",
          validoAte: null,
        }],
      },
      origem: "CACHE_LOCAL",
      obtidoEm: "2026-07-22T01:30:00.000Z",
    });

    render(<ObraEvolucaoMapa obra={OBRA} janela={{ inicio: null }} />);

    expect(await screen.findByText(/Mostrando o que já estava/)).toHaveTextContent(
      "Mostrando o que já estava neste aparelho, de 21/07/2026 às 22:30.",
    );
  });
});
