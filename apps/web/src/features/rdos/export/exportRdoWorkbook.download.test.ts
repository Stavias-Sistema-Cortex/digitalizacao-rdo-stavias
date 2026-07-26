// @vitest-environment jsdom

import {
  afterEach,
  beforeEach,
  describe,
  expect,
  it,
  vi,
} from "vitest";

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
  afterEach(() => vi.restoreAllMocks());

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

  it("downloads a valid XLSX response with the shared safe anchor behavior", async () => {
    mocks.apiFetch.mockResolvedValue(new Response("xlsx", {
      status: 200,
      headers: {
        "Content-Type":
          "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
      },
    }));
    let downloadedBlob: Blob | undefined;
    vi.spyOn(URL, "createObjectURL").mockImplementation((blob) => {
      downloadedBlob = blob;
      return "blob:rdo-xlsx";
    });
    const revokeObjectUrl = vi.spyOn(URL, "revokeObjectURL")
      .mockImplementation(() => undefined);
    const clickedAnchor = document.createElement("a");
    vi.spyOn(document, "createElement").mockReturnValue(clickedAnchor);
    vi.spyOn(clickedAnchor, "click").mockImplementation(() => undefined);

    await downloadAuthoritativeRdoWorkbook(snapshot());

    expect(downloadedBlob?.type).toBe(
      "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
    );
    expect(clickedAnchor?.href).toBe("blob:rdo-xlsx");
    expect(clickedAnchor?.download).toBe("rdo-Relatorio-42.xlsx");
    expect(clickedAnchor?.rel).toBe("noopener");
    expect(revokeObjectUrl).toHaveBeenCalledWith("blob:rdo-xlsx");
  });
});
