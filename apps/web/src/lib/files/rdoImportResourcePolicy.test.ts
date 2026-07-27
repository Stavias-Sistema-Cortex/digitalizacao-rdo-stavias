// @vitest-environment jsdom
import { describe, expect, it, vi } from "vitest";
import { strToU8, zipSync } from "fflate";

import { importarRdoArquivo } from "../../features/rdos/importRdoExcel";
import {
  assertSafeZipExpansion,
  RDO_IMPORT_LIMITS,
  preflightRdoImportFile,
  readValidatedRdoImportBytes,
} from "./rdoImportResourcePolicy";

describe("RDO import resource policy", () => {
  it("rejects an oversized file before reading any bytes", async () => {
    const arrayBuffer = vi.fn<() => Promise<ArrayBuffer>>();
    const oversized = {
      name: "rdo-grande.pdf",
      type: "application/pdf",
      size: RDO_IMPORT_LIMITS.fileBytes + 1,
      arrayBuffer,
    } as unknown as File;

    await expect(
      importarRdoArquivo(oversized, "Sessão atual"),
    ).rejects.toThrow("O arquivo de RDO excede o limite");
    expect(arrayBuffer).not.toHaveBeenCalled();
  });

  it("accepts an absent MIME but rejects a supplied MIME inconsistent with the extension", () => {
    expect(
      preflightRdoImportFile(
        new File(["%PDF-1.7"], "rdo.pdf", { type: "" }),
      ).kind,
    ).toBe("PDF");

    expect(() =>
      preflightRdoImportFile(
        new File(["%PDF-1.7"], "rdo.xlsx", {
          type: "application/pdf",
        }),
      )
    ).toThrow("tipo MIME");
  });

  it("rejects magic bytes inconsistent with the accepted extension and MIME", async () => {
    const file = new File(["PK\u0003\u0004"], "rdo.pdf", {
      type: "application/pdf",
    });
    const preflight = preflightRdoImportFile(file);

    await expect(
      readValidatedRdoImportBytes(file, preflight),
    ).rejects.toThrow("assinatura");
  });

  it("counts actual ZIP output and rejects one expanded entry over its limit", () => {
    const archive = zipSync({
      "xl/worksheets/sheet1.xml": strToU8("á".repeat(2_048)),
    });

    expect(() =>
      assertSafeZipExpansion(archive, {
        entries: 8,
        entryBytes: 1_024,
        expandedBytes: 4_096,
        compressionRatio: 1_000,
      })
    ).toThrow("entrada descompactada");
  });

  it("rejects a ZIP with more entries than the deterministic policy permits", () => {
    const archive = zipSync({
      "[Content_Types].xml": strToU8("a"),
      "xl/workbook.xml": strToU8("b"),
      "xl/worksheets/sheet1.xml": strToU8("c"),
    });

    expect(() =>
      assertSafeZipExpansion(archive, {
        entries: 2,
        entryBytes: 1_024,
        expandedBytes: 4_096,
        compressionRatio: 1_000,
      })
    ).toThrow("mais de 2 entradas");
  });

  it("rejects aggregate expanded bytes and excessive compression ratio", () => {
    const archive = zipSync({
      "xl/a.xml": strToU8("a".repeat(2_048)),
      "xl/b.xml": strToU8("b".repeat(2_048)),
    });

    expect(() =>
      assertSafeZipExpansion(archive, {
        entries: 8,
        entryBytes: 3_000,
        expandedBytes: 3_000,
        compressionRatio: 1_000,
      })
    ).toThrow("planilha descompactada");
    expect(() =>
      assertSafeZipExpansion(archive, {
        entries: 8,
        entryBytes: 3_000,
        expandedBytes: 6_000,
        compressionRatio: 2,
      })
    ).toThrow("taxa de expansão");
  });
});
