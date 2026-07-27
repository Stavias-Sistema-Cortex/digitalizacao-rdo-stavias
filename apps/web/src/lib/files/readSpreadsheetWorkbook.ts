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

export interface SpreadsheetWorkbookLimits {
  maxSheets: number;
  maxRowsPerSheet: number;
  maxColumnsPerSheet: number;
  maxCells: number;
}

export interface SpreadsheetWorkbookReadOptions {
  limits?: SpreadsheetWorkbookLimits;
}

export class SpreadsheetWorkbookLimitError extends Error {
  constructor(message: string) {
    super(message);
    this.name = "SpreadsheetWorkbookLimitError";
  }
}

export async function readSpreadsheetWorkbook(
  source: File | ArrayBuffer | Uint8Array,
  options: SpreadsheetWorkbookReadOptions = {},
): Promise<SpreadsheetSheet[]> {
  const XLSX = await import("@e965/xlsx");
  const buffer =
    source instanceof Uint8Array
      ? source
      : source instanceof ArrayBuffer
        ? new Uint8Array(source)
        : new Uint8Array(await source.arrayBuffer());
  const limits = options.limits;
  const workbookIndex = limits
    ? XLSX.read(buffer, {
        type: "array",
        bookSheets: true,
      })
    : null;

  if (
    limits &&
    workbookIndex &&
    workbookIndex.SheetNames.length > limits.maxSheets
  ) {
    throw new SpreadsheetWorkbookLimitError(
      `A planilha possui mais de ${limits.maxSheets} abas. Remova as abas que não pertencem ao RDO e tente novamente.`,
    );
  }

  const workbook = XLSX.read(buffer, {
    type: "array",
    cellDates: true,
    ...(limits
      ? {
          nodim: true,
          sheetRows: limits.maxRowsPerSheet + 1,
          sheets: workbookIndex?.SheetNames,
        }
      : {}),
  });
  let workbookCells = 0;

  return workbook.SheetNames.map((sheetName) => {
    const sheet = workbook.Sheets[sheetName];
    if (!sheet) {
      throw new Error(`A aba "${sheetName}" não pôde ser lida.`);
    }

    if (limits) {
      const cellKeys = Object.keys(sheet).filter(
        (key) => !key.startsWith("!"),
      );
      workbookCells += cellKeys.length;
      let rows = 0;
      let columns = 0;

      for (const key of cellKeys) {
        const position = XLSX.utils.decode_cell(key);
        rows = Math.max(rows, position.r + 1);
        columns = Math.max(columns, position.c + 1);
      }

      for (const rangeText of [
        sheet["!ref"],
        sheet["!fullref"],
      ]) {
        if (!rangeText) {
          continue;
        }
        const range = XLSX.utils.decode_range(rangeText);
        rows = Math.max(rows, range.e.r + 1);
        columns = Math.max(columns, range.e.c + 1);
      }

      if (rows > limits.maxRowsPerSheet) {
        throw new SpreadsheetWorkbookLimitError(
          `A aba "${sheetName}" possui mais de ${limits.maxRowsPerSheet} linhas. Remova dados fora do RDO e tente novamente.`,
        );
      }
      if (columns > limits.maxColumnsPerSheet) {
        throw new SpreadsheetWorkbookLimitError(
          `A aba "${sheetName}" possui mais de ${limits.maxColumnsPerSheet} colunas. Remova dados fora do RDO e tente novamente.`,
        );
      }
      if (workbookCells > limits.maxCells) {
        throw new SpreadsheetWorkbookLimitError(
          `A planilha possui mais de ${limits.maxCells} células preenchidas. Simplifique o arquivo e tente novamente.`,
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

    return {
      sheet: sheetName,
      data,
    };
  });
}
