import {
  assertSafeZipExpansion,
  RDO_IMPORT_LIMITS,
  RdoImportResourceError,
} from "./rdoImportResourcePolicy";

export type SpreadsheetCell =
  | string
  | number
  | boolean
  | Date
  | null;

export interface SpreadsheetSheet {
  sheet: string;
  data: SpreadsheetCell[][];
}

interface SpreadsheetReadLimits {
  sheets: number;
  rowsPerSheet: number;
  columnsPerSheet: number;
  cells: number;
}

interface SpreadsheetReadOptions {
  bytes?: Uint8Array;
  limits?: Partial<SpreadsheetReadLimits>;
}

export async function readSpreadsheetWorkbook(
  file: File,
  options: SpreadsheetReadOptions = {},
): Promise<SpreadsheetSheet[]> {
  const limits: SpreadsheetReadLimits = {
    sheets: options.limits?.sheets ?? RDO_IMPORT_LIMITS.workbookSheets,
    rowsPerSheet:
      options.limits?.rowsPerSheet ??
      RDO_IMPORT_LIMITS.workbookRowsPerSheet,
    columnsPerSheet:
      options.limits?.columnsPerSheet ??
      RDO_IMPORT_LIMITS.workbookColumnsPerSheet,
    cells: options.limits?.cells ?? RDO_IMPORT_LIMITS.workbookCells,
  };

  try {
    if (
      !options.bytes &&
      (!Number.isFinite(file.size) ||
        file.size <= 0 ||
        file.size > RDO_IMPORT_LIMITS.fileBytes)
    ) {
      throw new RdoImportResourceError(
        "A planilha de RDO está vazia ou excede o limite seguro de tamanho.",
      );
    }

    const bytes = options.bytes ??
      new Uint8Array(await file.arrayBuffer());
    if (isZip(bytes)) {
      assertSafeZipExpansion(bytes);
    } else if (!isOle(bytes)) {
      throw new Error("unsupported spreadsheet signature");
    }

    const XLSX = await import("@e965/xlsx");
    const workbook = XLSX.read(bytes, {
      type: "array",
      cellDates: true,
      cellFormula: true,
    });
    if (workbook.SheetNames.length > limits.sheets) {
      throw new RdoImportResourceError(
        `A planilha possui mais de ${limits.sheets} abas.`,
      );
    }

    const result: SpreadsheetSheet[] = [];
    let declaredCells = 0;
    let actualCells = 0;

    for (const sheetName of workbook.SheetNames) {
      const sheet = workbook.Sheets[sheetName];
      if (!sheet) {
        throw new Error("missing worksheet");
      }
      const reference = sheet["!ref"];
      if (reference) {
        const range = XLSX.utils.decode_range(reference);
        const rows = range.e.r - range.s.r + 1;
        const columns = range.e.c - range.s.c + 1;
        if (rows > limits.rowsPerSheet) {
          throw new RdoImportResourceError(
            `A aba "${sheetName}" possui mais de ${
              limits.rowsPerSheet
            } linhas.`,
          );
        }
        if (columns > limits.columnsPerSheet) {
          throw new RdoImportResourceError(
            `A aba "${sheetName}" possui mais de ${
              limits.columnsPerSheet
            } colunas.`,
          );
        }
        declaredCells += rows * columns;
        if (declaredCells > limits.cells) {
          throw new RdoImportResourceError(
            `A planilha excede o limite de ${limits.cells} células.`,
          );
        }
      }

      for (const address of Object.keys(sheet)) {
        if (address.startsWith("!")) {
          continue;
        }
        actualCells += 1;
        if (actualCells > limits.cells) {
          throw new RdoImportResourceError(
            `A planilha excede o limite de ${limits.cells} células.`,
          );
        }
        const candidate = sheet[address] as { f?: unknown } | undefined;
        if (
          candidate &&
          typeof candidate.f === "string" &&
          candidate.f.trim()
        ) {
          throw new RdoImportResourceError(
            "A planilha contém fórmulas e não pode ser importada com segurança.",
          );
        }
      }

      const data = XLSX.utils.sheet_to_json<SpreadsheetCell[]>(
        sheet,
        {
          header: 1,
          raw: true,
          defval: null,
          blankrows: false,
        },
      );
      result.push({
        sheet: sheetName,
        data,
      });
    }

    return result;
  } catch (error: unknown) {
    if (error instanceof RdoImportResourceError) {
      throw error;
    }
    throw new RdoImportResourceError(
      "Não foi possível ler a planilha de RDO; o arquivo pode estar corrompido ou fora do formato permitido.",
    );
  }
}

function isZip(bytes: Uint8Array): boolean {
  return bytes.length >= 4 &&
    bytes[0] === 0x50 &&
    bytes[1] === 0x4b &&
    (
      (bytes[2] === 0x03 && bytes[3] === 0x04) ||
      (bytes[2] === 0x05 && bytes[3] === 0x06) ||
      (bytes[2] === 0x07 && bytes[3] === 0x08)
    );
}

function isOle(bytes: Uint8Array): boolean {
  const signature = [
    0xd0,
    0xcf,
    0x11,
    0xe0,
    0xa1,
    0xb1,
    0x1a,
    0xe1,
  ];
  return bytes.length >= signature.length &&
    signature.every((value, index) => bytes[index] === value);
}
