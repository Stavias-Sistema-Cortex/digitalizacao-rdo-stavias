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
  downloadRdoWorkbook,
  rdoWorkbookFilename,
} from "./exportRdoWorkbook";
import type { RdoExportDownloadPermit } from "./rdoExportDownload";
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

const AUTHORIZATION_REQUIRED_MESSAGE =
  "A autorização atual para exportar o RDO não foi confirmada; o download foi bloqueado.";

function approvedPermit(): RdoExportDownloadPermit {
  return {
    assertCurrentAuthorization: () => true,
  };
}

function rejectingPermit(): RdoExportDownloadPermit {
  return {
    assertCurrentAuthorization: () => {
      throw new Error("A autorização do RDO foi revogada.");
    },
  };
}

const downloadLocalWithoutPermit = downloadRdoWorkbook as unknown as (
  value: RdoWorkbookSnapshot,
) => Promise<void>;
const downloadAuthoritativeWithoutPermit =
  downloadAuthoritativeRdoWorkbook as unknown as (
    value: RdoWorkbookSnapshot,
  ) => Promise<void>;

const directWorkbookDownloads = [
  {
    label: "local XLSX",
    withoutPermit: () => downloadLocalWithoutPermit(snapshot()),
    withPermit: (permit: RdoExportDownloadPermit) =>
      downloadRdoWorkbook(snapshot(), permit),
  },
  {
    label: "authoritative XLSX",
    withoutPermit: () => downloadAuthoritativeWithoutPermit(snapshot()),
    withPermit: (permit: RdoExportDownloadPermit) =>
      downloadAuthoritativeRdoWorkbook(snapshot(), permit),
  },
];

describe("authoritative RDO workbook download", () => {
  beforeEach(() => mocks.apiFetch.mockReset());
  afterEach(() => {
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
  });

  it.each(directWorkbookDownloads)(
    "fails closed when authorization is omitted from the $label wrapper",
    async ({ withoutPermit }) => {
      const fetchTemplate = vi.fn(() => {
        throw new Error("The local XLSX generator must not run.");
      });
      vi.stubGlobal("fetch", fetchTemplate);
      mocks.apiFetch.mockResolvedValue(new Response("xlsx", {
        status: 200,
        headers: { "Content-Type": "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" },
      }));
      const createObjectUrl = vi.spyOn(URL, "createObjectURL");
      const click = vi.spyOn(HTMLAnchorElement.prototype, "click");

      await expect(withoutPermit()).rejects.toThrow(
        AUTHORIZATION_REQUIRED_MESSAGE,
      );

      expect(fetchTemplate).not.toHaveBeenCalled();
      expect(mocks.apiFetch).not.toHaveBeenCalled();
      expect(createObjectUrl).not.toHaveBeenCalled();
      expect(click).not.toHaveBeenCalled();
    },
  );

  it.each(directWorkbookDownloads)(
    "fails closed when authorization rejects in the $label wrapper",
    async ({ withPermit }) => {
      const fetchTemplate = vi.fn(() => {
        throw new Error("The local XLSX generator must not run.");
      });
      vi.stubGlobal("fetch", fetchTemplate);
      mocks.apiFetch.mockResolvedValue(new Response("xlsx", {
        status: 200,
        headers: { "Content-Type": "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" },
      }));
      const createObjectUrl = vi.spyOn(URL, "createObjectURL");
      const click = vi.spyOn(HTMLAnchorElement.prototype, "click");

      await expect(withPermit(rejectingPermit())).rejects.toThrow(
        "A autorização do RDO foi revogada.",
      );

      expect(fetchTemplate).not.toHaveBeenCalled();
      expect(mocks.apiFetch).not.toHaveBeenCalled();
      expect(createObjectUrl).not.toHaveBeenCalled();
      expect(click).not.toHaveBeenCalled();
    },
  );

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

    await expect(downloadAuthoritativeRdoWorkbook(snapshot(), approvedPermit())).rejects.toThrow(
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

    await downloadAuthoritativeRdoWorkbook(snapshot(), approvedPermit());

    expect(downloadedBlob?.type).toBe(
      "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
    );
    expect(clickedAnchor?.href).toBe("blob:rdo-xlsx");
    expect(clickedAnchor?.download).toBe("rdo-Relatorio-42.xlsx");
    expect(clickedAnchor?.rel).toBe("noopener");
    expect(revokeObjectUrl).toHaveBeenCalledWith("blob:rdo-xlsx");
  });
});
