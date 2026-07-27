import {
  Unzip,
  UnzipInflate,
} from "fflate";

export const RDO_IMPORT_LIMITS = Object.freeze({
  // RDOs are two-page documents with small tabular payloads. Ten MiB leaves
  // ample room for the reviewed template while bounding the first allocation.
  fileBytes: 10 * 1024 * 1024,
  pdfPages: 12,
  pdfTextItems: 20_000,
  pdfTextChars: 500_000,
  zipEntries: 256,
  zipEntryBytes: 16 * 1024 * 1024,
  zipExpandedBytes: 64 * 1024 * 1024,
  zipCompressionRatio: 500,
  workbookSheets: 12,
  workbookRowsPerSheet: 2_048,
  workbookColumnsPerSheet: 128,
  workbookCells: 100_000,
});

export type RdoImportPreflight =
  | {
      kind: "PDF";
      extension: "pdf";
      container: "PDF";
    }
  | {
      kind: "SPREADSHEET";
      extension: "xlsx" | "xlsm";
      container: "ZIP";
    }
  | {
      kind: "SPREADSHEET";
      extension: "xls";
      container: "OLE";
    };

const MIME_BY_EXTENSION: Record<
  RdoImportPreflight["extension"],
  ReadonlySet<string>
> = {
  pdf: new Set(["application/pdf"]),
  xlsx: new Set([
    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
  ]),
  xlsm: new Set([
    "application/vnd.ms-excel.sheet.macroenabled.12",
  ]),
  xls: new Set(["application/vnd.ms-excel"]),
};

export class RdoImportResourceError extends Error {
  constructor(message: string) {
    super(message);
    this.name = "RdoImportResourceError";
  }
}

export interface ZipExpansionLimits {
  entries: number;
  entryBytes: number;
  expandedBytes: number;
  compressionRatio: number;
}

export function preflightRdoImportFile(file: File): RdoImportPreflight {
  if (!Number.isFinite(file.size) || file.size <= 0) {
    throw new RdoImportResourceError(
      "O arquivo de RDO está vazio ou possui tamanho inválido.",
    );
  }
  if (file.size > RDO_IMPORT_LIMITS.fileBytes) {
    throw new RdoImportResourceError(
      `O arquivo de RDO excede o limite de ${
        formatMiB(RDO_IMPORT_LIMITS.fileBytes)
      } MiB e não foi lido.`,
    );
  }

  const extension = file.name
    .split(".")
    .pop()
    ?.trim()
    .toLowerCase();
  const preflight = preflightForExtension(extension);
  const mime = file.type.trim().toLowerCase();

  if (mime && !MIME_BY_EXTENSION[preflight.extension].has(mime)) {
    throw new RdoImportResourceError(
      `O tipo MIME "${file.type}" não corresponde à extensão .${
        preflight.extension
      } do arquivo de RDO.`,
    );
  }

  return preflight;
}

export async function readValidatedRdoImportBytes(
  file: File,
  preflight: RdoImportPreflight,
): Promise<Uint8Array> {
  // Re-check immediately before allocation so every caller keeps the same
  // fail-before-arrayBuffer boundary.
  if (file.size > RDO_IMPORT_LIMITS.fileBytes) {
    throw new RdoImportResourceError(
      `O arquivo de RDO excede o limite de ${
        formatMiB(RDO_IMPORT_LIMITS.fileBytes)
      } MiB e não foi lido.`,
    );
  }

  let bytes: Uint8Array;
  try {
    bytes = new Uint8Array(await file.arrayBuffer());
  } catch {
    throw new RdoImportResourceError(
      "Não foi possível ler os bytes do arquivo de RDO.",
    );
  }

  if (!matchesContainerSignature(bytes, preflight.container)) {
    throw new RdoImportResourceError(
      `A assinatura do arquivo não corresponde à extensão .${
        preflight.extension
      }.`,
    );
  }

  return bytes;
}

export function assertSafeZipExpansion(
  bytes: Uint8Array,
  limits: ZipExpansionLimits = {
    entries: RDO_IMPORT_LIMITS.zipEntries,
    entryBytes: RDO_IMPORT_LIMITS.zipEntryBytes,
    expandedBytes: RDO_IMPORT_LIMITS.zipExpandedBytes,
    compressionRatio: RDO_IMPORT_LIMITS.zipCompressionRatio,
  },
): void {
  let entryCount = 0;
  let totalExpandedBytes = 0;

  try {
    const unzip = new Unzip((entry) => {
      entryCount += 1;
      if (entryCount > limits.entries) {
        throw new RdoImportResourceError(
          `A planilha compactada possui mais de ${limits.entries} entradas.`,
        );
      }
      if (
        entry.originalSize !== undefined &&
        entry.originalSize > limits.entryBytes
      ) {
        throw new RdoImportResourceError(
          `Uma entrada descompactada da planilha excede ${
            formatMiB(limits.entryBytes)
          } MiB.`,
        );
      }
      if (
        entry.size !== undefined &&
        entry.originalSize !== undefined &&
        entry.originalSize > 0 &&
        entry.originalSize / Math.max(entry.size, 1) >
          limits.compressionRatio
      ) {
        throw new RdoImportResourceError(
          "A taxa de expansão da planilha compactada excede o limite seguro.",
        );
      }

      let entryExpandedBytes = 0;
      entry.ondata = (error, chunk) => {
        if (error) {
          throw error;
        }
        entryExpandedBytes += chunk.byteLength;
        totalExpandedBytes += chunk.byteLength;
        if (entryExpandedBytes > limits.entryBytes) {
          entry.terminate();
          throw new RdoImportResourceError(
            `Uma entrada descompactada da planilha excede ${
              formatMiB(limits.entryBytes)
            } MiB.`,
          );
        }
        if (totalExpandedBytes > limits.expandedBytes) {
          entry.terminate();
          throw new RdoImportResourceError(
            `A planilha descompactada excede ${
              formatMiB(limits.expandedBytes)
            } MiB.`,
          );
        }
        if (
          totalExpandedBytes / Math.max(bytes.byteLength, 1) >
            limits.compressionRatio
        ) {
          entry.terminate();
          throw new RdoImportResourceError(
            "A taxa de expansão da planilha compactada excede o limite seguro.",
          );
        }
      };
      entry.start();
    });
    unzip.register(UnzipInflate);
    unzip.push(bytes, true);
  } catch (error: unknown) {
    if (error instanceof RdoImportResourceError) {
      throw error;
    }
    throw new RdoImportResourceError(
      "Não foi possível validar a expansão da planilha compactada.",
    );
  }

  if (entryCount === 0) {
    throw new RdoImportResourceError(
      "Não foi possível ler a planilha de RDO; o arquivo compactado está corrompido ou vazio.",
    );
  }
}

function preflightForExtension(
  extension: string | undefined,
): RdoImportPreflight {
  switch (extension) {
    case "pdf":
      return { kind: "PDF", extension, container: "PDF" };
    case "xlsx":
    case "xlsm":
      return { kind: "SPREADSHEET", extension, container: "ZIP" };
    case "xls":
      return { kind: "SPREADSHEET", extension, container: "OLE" };
    default:
      throw new RdoImportResourceError(
        "Envie um RDO em PDF, XLS, XLSX ou XLSM.",
      );
  }
}

function matchesContainerSignature(
  bytes: Uint8Array,
  container: RdoImportPreflight["container"],
): boolean {
  if (container === "PDF") {
    const searchLimit = Math.min(bytes.length - 4, 1_024);
    for (let index = 0; index <= searchLimit; index += 1) {
      if (
        bytes[index] === 0x25 &&
        bytes[index + 1] === 0x50 &&
        bytes[index + 2] === 0x44 &&
        bytes[index + 3] === 0x46 &&
        bytes[index + 4] === 0x2d
      ) {
        return true;
      }
    }
    return false;
  }

  if (container === "ZIP") {
    return bytes.length >= 4 &&
      bytes[0] === 0x50 &&
      bytes[1] === 0x4b &&
      (
        (bytes[2] === 0x03 && bytes[3] === 0x04) ||
        (bytes[2] === 0x05 && bytes[3] === 0x06) ||
        (bytes[2] === 0x07 && bytes[3] === 0x08)
      );
  }

  const oleSignature = [
    0xd0,
    0xcf,
    0x11,
    0xe0,
    0xa1,
    0xb1,
    0x1a,
    0xe1,
  ];
  return bytes.length >= oleSignature.length &&
    oleSignature.every((value, index) => bytes[index] === value);
}

function formatMiB(bytes: number): number {
  return bytes / (1024 * 1024);
}
