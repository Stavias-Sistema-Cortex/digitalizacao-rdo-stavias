export const RDO_IMPORT_ACCEPT = [
  ".pdf",
  ".xlsx",
  ".xls",
  ".xlsm",
  "application/pdf",
  "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
  "application/vnd.ms-excel",
  "application/vnd.ms-excel.sheet.macroEnabled.12",
].join(",");

/**
 * RDOs are normally two-page field documents. These limits leave generous
 * room for historical reports while bounding browser memory and parser work.
 */
export const RDO_IMPORT_LIMITS = Object.freeze({
  maxFileBytes: 12 * 1024 * 1024,
  maxPdfPages: 20,
  maxPdfTextItemsPerPage: 5_000,
  maxPdfTextItems: 20_000,
  maxPdfTextCharacters: 250_000,
  maxWorkbookSheets: 8,
  maxWorkbookRowsPerSheet: 500,
  maxWorkbookColumnsPerSheet: 256,
  maxWorkbookCells: 50_000,
  maxArchiveEntries: 1_024,
  maxArchiveEntryBytes: 32 * 1024 * 1024,
  maxArchiveExpandedBytes: 64 * 1024 * 1024,
});

type RdoImportExtension = "pdf" | "xlsx" | "xls" | "xlsm";

export type ValidatedRdoImport =
  | {
      kind: "PDF";
      extension: "pdf";
      data: Uint8Array;
    }
  | {
      kind: "SPREADSHEET";
      extension: Exclude<RdoImportExtension, "pdf">;
      data: Uint8Array;
    };

interface RdoImportFormat {
  mimeTypes: ReadonlySet<string>;
}

const FORMATS: Readonly<Record<RdoImportExtension, RdoImportFormat>> = {
  pdf: {
    mimeTypes: new Set(["application/pdf"]),
  },
  xlsx: {
    mimeTypes: new Set([
      "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
    ]),
  },
  xls: {
    mimeTypes: new Set(["application/vnd.ms-excel"]),
  },
  xlsm: {
    mimeTypes: new Set([
      "application/vnd.ms-excel.sheet.macroenabled.12",
    ]),
  },
};

export class RdoImportSafeError extends Error {
  constructor(message: string) {
    super(message);
    this.name = "RdoImportSafeError";
  }
}

export async function readValidatedRdoImport(
  file: File,
): Promise<ValidatedRdoImport> {
  const extension = file.name
    .match(/\.([^.]+)$/)?.[1]
    ?.toLowerCase() as RdoImportExtension | undefined;
  const format = extension ? FORMATS[extension] : undefined;

  if (!extension || !format) {
    throw new RdoImportSafeError(
      "Envie um RDO em PDF, XLS, XLSX ou XLSM.",
    );
  }

  if (!Number.isSafeInteger(file.size) || file.size <= 0) {
    throw new RdoImportSafeError(
      "O arquivo de RDO está vazio ou possui tamanho inválido. Selecione outro arquivo.",
    );
  }

  if (file.size > RDO_IMPORT_LIMITS.maxFileBytes) {
    throw new RdoImportSafeError(
      "O arquivo de RDO excede o limite seguro de 12 MiB. Reduza o arquivo ou exporte apenas o relatório necessário.",
    );
  }

  const declaredType = file.type.trim().toLowerCase();
  if (declaredType && !format.mimeTypes.has(declaredType)) {
    throw new RdoImportSafeError(
      `O tipo declarado do arquivo não corresponde à extensão .${extension}. Exporte novamente o RDO no formato correto.`,
    );
  }

  let data: Uint8Array;
  try {
    data = new Uint8Array(await file.arrayBuffer());
  } catch {
    throw new RdoImportSafeError(
      "Não foi possível ler o arquivo de RDO. Selecione o arquivo novamente.",
    );
  }

  if (extension === "pdf") {
    validatePdfSignature(data);
    return {
      kind: "PDF",
      extension: "pdf",
      data,
    };
  }

  validateSpreadsheetSignature(data, extension);
  if (extension !== "xls") {
    await validateArchiveBudget(data);
  }

  return {
    kind: "SPREADSHEET",
    extension,
    data,
  };
}

function validatePdfSignature(data: Uint8Array) {
  const headerLength = Math.min(data.length, 1_024);
  let hasPdfHeader = false;

  for (let index = 0; index <= headerLength - 5; index += 1) {
    if (
      data[index] === 0x25 &&
      data[index + 1] === 0x50 &&
      data[index + 2] === 0x44 &&
      data[index + 3] === 0x46 &&
      data[index + 4] === 0x2d
    ) {
      hasPdfHeader = true;
      break;
    }
  }

  if (!hasPdfHeader) {
    throw new RdoImportSafeError(
      "O conteúdo do arquivo não corresponde a um PDF válido. Exporte o RDO novamente.",
    );
  }
}

function validateSpreadsheetSignature(
  data: Uint8Array,
  extension: Exclude<RdoImportExtension, "pdf">,
) {
  const isZip =
    data.length >= 4 &&
    data[0] === 0x50 &&
    data[1] === 0x4b &&
    ((data[2] === 0x03 && data[3] === 0x04) ||
      (data[2] === 0x05 && data[3] === 0x06) ||
      (data[2] === 0x07 && data[3] === 0x08));
  const isOle =
    data.length >= 8 &&
    data[0] === 0xd0 &&
    data[1] === 0xcf &&
    data[2] === 0x11 &&
    data[3] === 0xe0 &&
    data[4] === 0xa1 &&
    data[5] === 0xb1 &&
    data[6] === 0x1a &&
    data[7] === 0xe1;

  const valid = extension === "xls" ? isOle : isZip;
  if (!valid) {
    throw new RdoImportSafeError(
      `O conteúdo do arquivo não corresponde a uma planilha .${extension} válida. Exporte o RDO novamente.`,
    );
  }
}

async function validateArchiveBudget(data: Uint8Array) {
  const { unzipSync } = await import("fflate");
  let entries = 0;
  let expandedBytes = 0;

  try {
    unzipSync(data, {
      filter: (entry) => {
        entries += 1;
        expandedBytes += entry.originalSize;

        if (entries > RDO_IMPORT_LIMITS.maxArchiveEntries) {
          throw new RdoImportSafeError(
            "A planilha possui arquivos internos demais para uma importação segura. Exporte somente o RDO necessário.",
          );
        }
        if (
          entry.originalSize >
          RDO_IMPORT_LIMITS.maxArchiveEntryBytes
        ) {
          throw new RdoImportSafeError(
            "A planilha possui conteúdo interno grande demais para uma importação segura. Simplifique o arquivo e tente novamente.",
          );
        }
        if (
          expandedBytes >
          RDO_IMPORT_LIMITS.maxArchiveExpandedBytes
        ) {
          throw new RdoImportSafeError(
            "A planilha expandida excede o limite seguro de 64 MiB. Simplifique o arquivo e tente novamente.",
          );
        }

        return false;
      },
    });
  } catch (error: unknown) {
    if (error instanceof RdoImportSafeError) {
      throw error;
    }
    throw new RdoImportSafeError(
      "Não foi possível validar a estrutura da planilha. O arquivo pode estar corrompido; exporte-o novamente.",
    );
  }
}
