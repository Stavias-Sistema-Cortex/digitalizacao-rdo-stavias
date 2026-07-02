import * as XLSX from "@e965/xlsx";

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

export async function readSpreadsheetWorkbook(
  file: File,
): Promise<SpreadsheetSheet[]> {
  const buffer = await file.arrayBuffer();
  const workbook = XLSX.read(buffer, {
    type: "array",
    cellDates: true,
  });

  return workbook.SheetNames.map((sheetName) => {
    const sheet = workbook.Sheets[sheetName];
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
