import {
  readSpreadsheetWorkbook,
  type SpreadsheetSheet,
} from "../../lib/files/readSpreadsheetWorkbook";
import type {
  ProgramacaoSemanalDia,
  ProgramacaoSemanalImportada,
  ProgramacaoSemanalLinha,
} from "./programacaoSemanal.types";

type SheetData = SpreadsheetSheet["data"];

type PdfTextItem = {
  text: string;
  x: number;
  y: number;
  width: number;
  height: number;
  rotated: boolean;
};

const LABELS = [
  "Serviço",
  "Obra",
  "Cliente",
  "Rodovia",
  "Sentido",
  "Período",
  "Km Inicial",
  "Km Final",
  "Faixa",
  "Ton. Massa",
  "Tipo do Cap",
  "Teor Cap (Projeto)",
  "Cap",
] as const;

type ProgramacaoLabel = (typeof LABELS)[number];

const LABEL_KEYS: Record<
  ProgramacaoLabel,
  keyof ProgramacaoSemanalLinha["raw"]
> = {
  Serviço: "servico",
  Obra: "obra",
  Cliente: "cliente",
  Rodovia: "rodovia",
  Sentido: "sentido",
  Período: "periodo",
  "Km Inicial": "kmInicial",
  "Km Final": "kmFinal",
  Faixa: "faixa",
  "Ton. Massa": "toneladaMassa",
  "Tipo do Cap": "tipoCap",
  "Teor Cap (Projeto)": "teorCapProjeto",
  Cap: "cap",
};

export async function importarProgramacaoSemanal(
  file: File,
): Promise<ProgramacaoSemanalImportada> {
  const extension = file.name
    .split(".")
    .pop()
    ?.toLowerCase();

  if (extension === "pdf") {
    return importarPdf(file);
  }

  if (
    extension === "xlsx" ||
    extension === "xls" ||
    extension === "xlsm"
  ) {
    return importarExcel(file);
  }

  throw new Error(
    "Envie uma programação em PDF, XLS, XLSX ou XLSM.",
  );
}

async function importarPdf(
  file: File,
): Promise<ProgramacaoSemanalImportada> {
  const [pdfjsLib, pdfWorker] = await Promise.all([
    import("pdfjs-dist/legacy/build/pdf.mjs"),
    import("pdfjs-dist/legacy/build/pdf.worker.mjs?url"),
  ]);
  pdfjsLib.GlobalWorkerOptions.workerSrc = pdfWorker.default;

  const data = new Uint8Array(
    await file.arrayBuffer(),
  );
  const document = await pdfjsLib.getDocument({
    data,
  }).promise;

  const page = await document.getPage(1);
  const content = await page.getTextContent();
  const items = content.items
    .map((item): PdfTextItem | null => {
      if (!("str" in item) || !item.str.trim()) {
        return null;
      }

      const transform = item.transform;
      const rotated =
        Math.abs(transform[1]) >
        Math.abs(transform[0]);

      return {
        text: item.str.replace(/\s+/g, " ").trim(),
        x: transform[4],
        y: transform[5],
        width: item.width,
        height: item.height,
        rotated,
      };
    })
    .filter(
      (item): item is PdfTextItem => item !== null,
    );

  const dateItems = items
    .filter((item) =>
      parseDateText(item.text),
    )
    .sort((a, b) => a.x - b.x);

  if (dateItems.length === 0) {
    throw new Error(
      "Não encontrei as colunas de datas no PDF da programação.",
    );
  }

  const columns = dateItems.map((item, index) => ({
    data: parseDateText(item.text) ?? "",
    diaSemana:
      findNearestText(items, item.x, item.y - 7) ??
      "",
    center: item.x + item.width / 2,
    left: 0,
    right: 0,
    index,
  }));

  for (let index = 0; index < columns.length; index += 1) {
    const previous = columns[index - 1];
    const current = columns[index];
    const next = columns[index + 1];

    current.left = previous
      ? (previous.center + current.center) / 2
      : current.center - 25;
    current.right = next
      ? (current.center + next.center) / 2
      : current.center + 25;
  }

  const labelRows = items
    .filter(
      (item) =>
        item.x >= 95 &&
        item.x <= 145 &&
        matchLabel(item.text),
    )
    .map((item) => ({
      label: matchLabel(item.text) as ProgramacaoLabel,
      y: item.y,
    }))
    .sort((a, b) => b.y - a.y);

  const serviceRows = labelRows.filter(
    (row) => row.label === "Serviço",
  );

  const fechamentoItems = items.filter((item) =>
    /^\d+º\s+fechamento$/i.test(item.text),
  );

  const dias = columns.map<ProgramacaoSemanalDia>(
    (column) => {
      const linhas: ProgramacaoSemanalLinha[] = [];

      serviceRows.forEach((serviceRow, blockIndex) => {
        const nextServiceY =
          serviceRows[blockIndex + 1]?.y ?? -Infinity;
        const blockRows = labelRows.filter(
          (row) =>
            row.y <= serviceRow.y + 1 &&
            row.y > nextServiceY + 1,
        );
        const fechamento =
          nearestFechamento(
            fechamentoItems,
            serviceRow.y,
          ) ?? `${blockIndex + 1}º Fechamento`;
        const raw: Record<string, string> = {};

        for (const row of blockRows) {
          const key = LABEL_KEYS[row.label];
          raw[key] = textAtPdfCell(
            items,
            row.y,
            column.left,
            column.right,
          );
        }

        const line = buildLine(
          file.name,
          column.data,
          fechamento,
          raw,
        );

        if (hasUsefulLineData(line)) {
          linhas.push(line);
        }
      });

      const baixada = items.some(
        (item) =>
          normalize(item.text) === "baixada" &&
          item.x >= column.left &&
          item.x <= column.right,
      );

      return {
        data: column.data,
        diaSemana: column.diaSemana,
        status:
          linhas.length > 0
            ? "COM_DADOS"
            : baixada
              ? "BAIXADA"
              : "VAZIO",
        linhas,
      };
    },
  );

  return {
    arquivoNome: file.name,
    fonteTipo: "PDF",
    encarregadoExtraido:
      extractEncarregado(items),
    dias,
    avisos: buildWarnings(dias),
  };
}

async function importarExcel(
  file: File,
): Promise<ProgramacaoSemanalImportada> {
  const workbook = await readSpreadsheetWorkbook(file);
  const sheet =
    workbook.find((candidate) =>
      hasProgramacaoHint(candidate.data),
    ) ?? workbook[0];

  if (!sheet) {
    throw new Error(
      "Não consegui ler a primeira aba da programação.",
    );
  }

  const dateRowIndex = sheet.data.findIndex(
    (row) =>
      row.filter((cell) =>
        parseDateCell(cell),
      ).length >= 2,
  );

  if (dateRowIndex < 0) {
    throw new Error(
      "Não encontrei a linha de datas na planilha de programação.",
    );
  }

  const dateCells = sheet.data[dateRowIndex]
    .map((cell, column) => ({
      data: parseDateCell(cell),
      column,
    }))
    .filter(
      (cell): cell is {
        data: string;
        column: number;
      } => Boolean(cell.data),
    );

  const serviceRows = sheet.data
    .map((row, index) => ({
      row,
      index,
      labelColumn: findLabelColumn(row, "Serviço"),
    }))
    .filter(
      (entry) => entry.labelColumn >= 0,
    );

  const dias = dateCells.map<ProgramacaoSemanalDia>(
    (dateCell) => {
      const linhas: ProgramacaoSemanalLinha[] = [];

      serviceRows.forEach((serviceRow, blockIndex) => {
        const nextServiceIndex =
          serviceRows[blockIndex + 1]?.index ??
          sheet.data.length;
        const labelColumn = serviceRow.labelColumn;
        const raw: Record<string, string> = {};

        for (
          let rowIndex = serviceRow.index;
          rowIndex < nextServiceIndex;
          rowIndex += 1
        ) {
          const row = sheet.data[rowIndex] ?? [];
          const label = matchLabel(
            valueText(row[labelColumn]),
          );

          if (!label) {
            continue;
          }

          raw[LABEL_KEYS[label]] = valueText(
            row[dateCell.column],
          );
        }

        const fechamento =
          findFechamentoInRows(
            sheet.data,
            serviceRow.index,
            nextServiceIndex,
          ) ?? `${blockIndex + 1}º Fechamento`;
        const line = buildLine(
          file.name,
          dateCell.data,
          fechamento,
          raw,
        );

        if (hasUsefulLineData(line)) {
          linhas.push(line);
        }
      });

      const baixada = hasBaixadaInColumn(
        sheet.data,
        dateCell.column,
      );

      return {
        data: dateCell.data,
        diaSemana:
          valueText(
            sheet.data[dateRowIndex + 1]?.[
              dateCell.column
            ],
          ) || weekdayLabel(dateCell.data),
        status:
          linhas.length > 0
            ? "COM_DADOS"
            : baixada
              ? "BAIXADA"
              : "VAZIO",
        linhas,
      };
    },
  );

  return {
    arquivoNome: file.name,
    fonteTipo: "EXCEL",
    encarregadoExtraido:
      extractEncarregadoFromRows(sheet.data),
    dias,
    avisos: buildWarnings(dias),
  };
}

function buildLine(
  fileName: string,
  data: string,
  fechamento: string,
  raw: Record<string, string>,
): ProgramacaoSemanalLinha {
  const kmInicial = raw.kmInicial ?? "";
  const kmFinal = raw.kmFinal ?? "";

  return {
    id: stableId(
      fileName,
      data,
      fechamento,
      raw.servico,
      raw.obra,
      kmInicial,
      kmFinal,
    ),
    fechamento,
    servico: raw.servico ?? "",
    obraNome: raw.obra ?? "",
    cliente: raw.cliente ?? "",
    rodovia: raw.rodovia ?? "",
    sentido: raw.sentido ?? "",
    periodo: raw.periodo ?? "",
    kmInicial,
    kmFinal,
    faixa: raw.faixa ?? "",
    toneladaMassa: parseNumber(raw.toneladaMassa),
    tipoCap: raw.tipoCap ?? "",
    teorCapProjeto: parsePercentNumber(
      raw.teorCapProjeto,
    ),
    cap: parseNumber(raw.cap),
    extensaoM: calculateExtensaoM(
      kmInicial,
      kmFinal,
    ),
    raw,
  };
}

function hasUsefulLineData(
  line: ProgramacaoSemanalLinha,
): boolean {
  return Boolean(
    line.servico ||
      line.obraNome ||
      line.kmInicial ||
      line.kmFinal ||
      line.toneladaMassa !== null,
  );
}

function textAtPdfCell(
  items: PdfTextItem[],
  rowY: number,
  left: number,
  right: number,
): string {
  return items
    .filter((item) => {
      if (item.rotated) {
        return false;
      }

      const centerX = item.x + item.width / 2;
      return (
        centerX >= left &&
        centerX < right &&
        Math.abs(item.y - rowY) <= 2.4
      );
    })
    .sort((a, b) => a.x - b.x)
    .map((item) => item.text)
    .join(" ")
    .replace(/\s+/g, " ")
    .trim();
}

function extractEncarregado(
  items: PdfTextItem[],
): string {
  const label = items.find(
    (item) =>
      normalize(item.text) === "encarregado",
  );

  if (!label) {
    return "";
  }

  return items
    .filter(
      (item) =>
        Math.abs(item.y - label.y) <= 2 &&
        item.x > label.x + label.width,
    )
    .sort((a, b) => a.x - b.x)
    .map((item) => item.text)
    .join(" ")
    .replace(/\s+/g, " ")
    .trim();
}

function extractEncarregadoFromRows(
  rows: SheetData,
): string {
  for (const row of rows) {
    const index = row.findIndex(
      (cell) =>
        normalize(valueText(cell)) ===
        "encarregado",
    );

    if (index >= 0) {
      return valueText(row[index + 1]);
    }
  }

  return "";
}

function nearestFechamento(
  items: PdfTextItem[],
  y: number,
): string | null {
  const nearest = items
    .map((item) => ({
      item,
      distance: Math.abs(item.y - y),
    }))
    .sort((a, b) => a.distance - b.distance)[0];

  return nearest && nearest.distance <= 4
    ? nearest.item.text
    : null;
}

function findNearestText(
  items: PdfTextItem[],
  x: number,
  y: number,
): string | null {
  const nearest = items
    .filter((item) => !item.rotated)
    .map((item) => ({
      item,
      distance:
        Math.abs(item.x - x) +
        Math.abs(item.y - y) * 2,
    }))
    .sort((a, b) => a.distance - b.distance)[0];

  return nearest && nearest.distance < 12
    ? nearest.item.text
    : null;
}

function findLabelColumn(
  row: SheetData[number],
  label: ProgramacaoLabel,
): number {
  return row.findIndex(
    (cell) =>
      normalize(valueText(cell)) ===
      normalize(label),
  );
}

function findFechamentoInRows(
  rows: SheetData,
  start: number,
  end: number,
): string | null {
  for (let index = start; index < end; index += 1) {
    for (const cell of rows[index] ?? []) {
      const text = valueText(cell);
      if (/^\d+º?\s+fechamento$/i.test(text)) {
        return text.replace(
          /^(\d+)o\b/i,
          "$1º",
        );
      }
    }
  }

  return null;
}

function hasBaixadaInColumn(
  rows: SheetData,
  column: number,
): boolean {
  return rows.some(
    (row) =>
      normalize(valueText(row[column])) ===
      "baixada",
  );
}

function hasProgramacaoHint(
  rows: SheetData,
): boolean {
  return rows.some((row) =>
    row.some((cell) =>
      normalize(valueText(cell)).includes(
        "programacao semanal prevista",
      ),
    ),
  );
}

function matchLabel(
  value: string,
): ProgramacaoLabel | null {
  const normalized = normalize(value);

  return (
    LABELS.find(
      (label) =>
        normalize(label) === normalized,
    ) ?? null
  );
}

function parseDateCell(
  value: unknown,
): string | null {
  if (value instanceof Date) {
    return value.toISOString().slice(0, 10);
  }

  if (typeof value === "number") {
    const parsed = excelSerialDate(value);
    return parsed ? parsed.toISOString().slice(0, 10) : null;
  }

  return parseDateText(valueText(value));
}

function parseDateText(
  value: string,
): string | null {
  const match = value
    .trim()
    .match(/^(\d{1,2})\/(\d{1,2})\/(\d{4})$/);

  if (!match) {
    return null;
  }

  const month = Number(match[1]);
  const day = Number(match[2]);
  const year = Number(match[3]);

  if (
    month < 1 ||
    month > 12 ||
    day < 1 ||
    day > 31
  ) {
    return null;
  }

  return `${year}-${String(month).padStart(2, "0")}-${String(day).padStart(2, "0")}`;
}

function weekdayLabel(date: string): string {
  const parsed = new Date(`${date}T00:00:00`);

  if (Number.isNaN(parsed.getTime())) {
    return "";
  }

  return new Intl.DateTimeFormat("pt-BR", {
    weekday: "long",
  }).format(parsed);
}

function excelSerialDate(value: number): Date | null {
  if (!Number.isFinite(value) || value < 20_000) {
    return null;
  }

  const epoch = Date.UTC(1899, 11, 30);
  return new Date(epoch + value * 86_400_000);
}

function valueText(value: unknown): string {
  if (value === null || value === undefined) {
    return "";
  }

  if (value instanceof Date) {
    return value.toISOString().slice(0, 10);
  }

  return String(value).replace(/\s+/g, " ").trim();
}

function parseNumber(value: unknown): number | null {
  if (
    typeof value === "number" &&
    Number.isFinite(value)
  ) {
    return value;
  }

  const text = valueText(value);
  if (!text) {
    return null;
  }

  const normalized = text
    .replace(/%/g, "")
    .replace(/\./g, ".")
    .replace(",", ".");
  const number = Number(normalized);

  return Number.isFinite(number) ? number : null;
}

function parsePercentNumber(
  value: unknown,
): number | null {
  return parseNumber(value);
}

function calculateExtensaoM(
  kmInicial: string,
  kmFinal: string,
): number | null {
  const start = parseNumber(kmInicial);
  const end = parseNumber(kmFinal);

  if (
    start === null ||
    end === null ||
    end < start
  ) {
    return null;
  }

  return Math.round((end - start) * 1000 * 1000) / 1000;
}

function stableId(
  ...parts: Array<string | number | null | undefined>
): string {
  return parts
    .map((part) =>
      normalize(String(part ?? "x")),
    )
    .join("-")
    .replace(/[^a-z0-9]+/g, "-")
    .replace(/^-+|-+$/g, "");
}

function buildWarnings(
  dias: ProgramacaoSemanalDia[],
): string[] {
  const warnings: string[] = [];

  if (
    dias.every(
      (dia) => dia.status !== "COM_DADOS",
    )
  ) {
    warnings.push(
      "Nenhum dia com programação detalhada foi identificado no arquivo.",
    );
  }

  const totalLinhas = dias.reduce(
    (sum, dia) => sum + dia.linhas.length,
    0,
  );

  if (totalLinhas === 0) {
    warnings.push(
      "A semana foi reconhecida, mas não encontrei linhas de serviço importáveis.",
    );
  }

  return warnings;
}

function normalize(value: string): string {
  return value
    .normalize("NFD")
    .replace(/\p{Diacritic}/gu, "")
    .replace(/:/g, "")
    .toLowerCase()
    .trim();
}
