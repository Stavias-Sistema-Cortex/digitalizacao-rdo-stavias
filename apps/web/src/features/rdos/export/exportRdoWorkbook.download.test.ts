// @vitest-environment jsdom

import { beforeEach, describe, expect, it, vi } from "vitest";

import { createEmptyRdo } from "../createEmptyRdo";
import {
  downloadAuthoritativeRdoWorkbook,
  rdoWorkbookFilename,
} from "./exportRdoWorkbook";
import type { RdoWorkbookSnapshot } from "./rdoWorkbookMapping";

const mocks = vi.hoisted(() => ({
  apiFetch: vi.fn(),
}));

vi.mock("../../../lib/api/apiClient", () => ({
  apiFetch: mocks.apiFetch,
}));

function snapshot(): RdoWorkbookSnapshot {
  return {
    obra: { id: "obra-1", nome: "Obra", codigoContrato: "CTR-1" },
    rdo: {
      ...createEmptyRdo(),
      id: "rdo-1",
      obraId: "obra-1",
      numeroRdo: "../ Relatório @ 42",
      dataRdo: "2026-07-22",
    },
  };
}

describe("authoritative RDO workbook download", () => {
  beforeEach(() => mocks.apiFetch.mockReset());

  it.each([
    ["HTML", "<html>login</html>", "text/html; charset=utf-8"],
    ["JSON", '{"error":"unauthorized"}', "application/json"],
  ])("rejects a 200 %s response before creating a download", async (
    _label,
    body,
    contentType,
  ) => {
    mocks.apiFetch.mockResolvedValue(new Response(body, {
      status: 200,
      headers: { "Content-Type": contentType },
    }));
    const createObjectUrl = vi.spyOn(URL, "createObjectURL");

    await expect(downloadAuthoritativeRdoWorkbook(snapshot())).rejects.toThrow(
      "sem um arquivo XLSX válido",
    );
    expect(createObjectUrl).not.toHaveBeenCalled();
  });

  it("sanitizes the local filename independently of response headers", () => {
    expect(rdoWorkbookFilename(snapshot())).toBe("rdo-Relatorio-42.xlsx");
  });
});
