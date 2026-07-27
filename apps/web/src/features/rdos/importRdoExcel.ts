import {
  readSpreadsheetWorkbook,
  type SpreadsheetSheet,
} from "../../lib/files/readSpreadsheetWorkbook";
import {
  preflightRdoImportFile,
  RdoImportResourceError,
  readValidatedRdoImportBytes,
} from "../../lib/files/rdoImportResourcePolicy";
import {
  createEmptyControleGeometrico,
  createEmptyEquipamento,
  createEmptyMaoObra,
  createEmptyMaterial,
  createEmptyRdo,
} from "./createEmptyRdo";
import {
  extractBoundedPdfLines,
  type PdfDocumentForTextExtraction,
} from "./boundedPdfTextExtraction";
import type {
  CondicaoClimatica,
  ControleGeometricoDraft,
  EquipamentoDraft,
  MaoObraDraft,
  MaterialDraft,
  RdoDraft,
} from "./rdo.types";
import { normalizarUnidade } from "./unidades";

type WorkSheet = SpreadsheetSheet;

export interface RdoImportResult {
  draft: RdoDraft;
  summary: string;
  warnings: string[];
}

const LEGACY_FRONT_HINTS = [
  "RELATÓRIO DIÁRIO DE OBRA",
  "MÃO DE OBRA",
  "EQUIPAMENTOS",
];

const LEGACY_BACK_HINTS = [
  "MATERIAIS",
  "CONTROLE GEOMÉTRICO",
  "OBSERVAÇÕES GERAIS",
];

export async function importarRdoArquivo(
  file: File,
  preenchidoPorSessao: string,
): Promise<RdoImportResult> {
  const preflight = preflightRdoImportFile(file);
  const bytes = await readValidatedRdoImportBytes(file, preflight);

  if (preflight.kind === "PDF") {
    return importarRdoPdf(file, preenchidoPorSessao, bytes);
  }

  if (preflight.kind === "SPREADSHEET") {
    return importarRdoPlanilha(file, preenchidoPorSessao, bytes);
  }

  throw new Error(
    "Envie um RDO em PDF, XLS, XLSX ou XLSM.",
  );
}

export async function importarRdoExcel(
  file: File,
  preenchidoPorSessao: string,
): Promise<RdoImportResult> {
  return importarRdoArquivo(file, preenchidoPorSessao);
}

async function importarRdoPlanilha(
  file: File,
  preenchidoPorSessao: string,
  bytes: Uint8Array,
): Promise<RdoImportResult> {
  const workbook = await readSpreadsheetWorkbook(file, { bytes });

  const frente = pickSheet(workbook, "frente", 0);
  const verso = pickSheet(workbook, "verso", 1);
  const warnings: string[] = [];

  if (!frente) {
    throw new Error(
      "Não encontrei a aba de frente do RDO na planilha.",
    );
  }

  if (!hasAnyHint(frente, LEGACY_FRONT_HINTS)) {
    warnings.push(
      "A aba de frente não parece seguir exatamente o layout v.1 de RDO; importei pelos campos reconhecidos.",
    );
  }

  if (verso && !hasAnyHint(verso, LEGACY_BACK_HINTS)) {
    warnings.push(
      "A aba de verso não parece seguir exatamente o layout v.1 de RDO; alguns blocos podem ficar em branco.",
    );
  }

  const draft = createEmptyRdo();
  const fileBaseName = file.name.replace(/\.[^.]+$/, "").trim();
  const contrato = firstText(
    rangeText(frente, "Q6:U6"),
    rangeText(frente, "Q5:U5", "N° DA OBRA"),
  );
  const dataRdo = firstText(
    dateString(cell(frente, "AA6")),
    dateString(cell(verso, "AB4")),
    dateString(cell(verso, "AB3")),
  );
  const apontadorRdo = verso
    ? rangeText(verso, "B71:J71")
    : "";
  const encarregadoObra = verso
    ? rangeText(verso, "L71:T71")
    : "";
  const fiscalizacaoCampo = verso
    ? rangeText(verso, "V71:AD71")
    : "";

  draft.numeroRdo =
    firstText(fileBaseName, dataRdo ? `RDO-${dataRdo}` : "") ||
    draft.numeroRdo;
  draft.dataRdo = dataRdo || draft.dataRdo;
  draft.cliente = rangeText(frente, "B6:P6");
  draft.contrato = contrato;
  draft.rodovia = rangeText(frente, "V6:Z6");
  draft.cidade = "";
  draft.uf = "";
  draft.kmInicialProgramado = rangeText(frente, "Q10:U10");
  draft.kmFinalProgramado = rangeText(frente, "Q12:U12");
  draft.kmInicialInterditado = rangeText(frente, "V10:Z10");
  draft.kmFinalInterditado = rangeText(frente, "V12:Z12");
  draft.turno = detectTurno(frente);
  draft.horaInicio = firstText(
    timeString(cell(frente, "AA10")),
    timeString(cell(frente, "AF10")),
    draft.horaInicio,
  );
  draft.horaFim = firstText(
    timeString(cell(frente, "AA12")),
    timeString(cell(frente, "AF12")),
    draft.horaFim,
  );
  draft.condicaoManha = detectCondicao(frente, 10);
  draft.condicaoTarde = detectCondicao(frente, 11);
  draft.condicaoNoite = detectCondicao(frente, 12);
  draft.pluviometriaMm =
    parseNumber(rangeText(frente, "M10:P12")) ??
    draft.pluviometriaMm;
  draft.preenchidoPor =
    firstText(apontadorRdo, preenchidoPorSessao) || "";
  draft.apontadorRdo = apontadorRdo;
  draft.encarregadoObra = encarregadoObra;
  draft.fiscalizacaoCampo = fiscalizacaoCampo;
  draft.observacoes = verso ? rangeText(verso, "B63:AD69") : "";
  draft.maoObra = parseMaoObra(frente);
  draft.equipamentos = parseEquipamentos(frente);
  draft.controlesGeometricos = [
    ...parseTrechoTrabalhado(frente),
    ...(verso ? parseControleGeometrico(verso) : []),
  ];
  draft.materiais = verso ? parseMateriais(verso) : [];

  if (!draft.obraId && contrato) {
    warnings.push(
      `O Excel trouxe N° da obra/contrato "${contrato}", mas o rascunho ainda precisa do Obra ID do Córtex antes de sincronizar.`,
    );
  }

  const summary =
    `Importado de ${file.name}: ${draft.maoObra.length} mão de obra, ` +
    `${draft.equipamentos.length} equipamentos, ${draft.materiais.length} materiais ` +
    `e ${draft.controlesGeometricos.length} controles geométricos.`;

  return {
    draft,
    summary,
    warnings,
  };
}

async function importarRdoPdf(
  file: File,
  preenchidoPorSessao: string,
  bytes: Uint8Array,
): Promise<RdoImportResult> {
  const lines = await extractPdfLines(bytes);
  const textoExtraido = lines.join("\n").trim();

  if (!textoExtraido) {
    throw new Error(
      "Não encontrei texto selecionável neste PDF. Para PDF escaneado, será necessário OCR antes da importação.",
    );
  }

  const draft = createEmptyRdo();
  const fileBaseName = file.name.replace(/\.[^.]+$/, "").trim();
  const dataRdo = firstText(
    extractFirstDate(textoExtraido),
    draft.dataRdo,
  );
  const numeroRdo = firstText(
    extractLabeledValue(lines, [
      "nº rdo",
      "n° rdo",
      "numero rdo",
      "número rdo",
      "rdo",
    ]),
    fileBaseName,
  );
  const contrato = extractLabeledValue(lines, [
    "nº da obra",
    "n° da obra",
    "numero da obra",
    "número da obra",
    "contrato",
  ]);
  const apontadorRdo = extractLabeledValue(lines, [
    "apontador rdo",
    "apontador",
    "preenchido por",
  ]);
  const encarregadoObra = extractLabeledValue(lines, [
    "encarregado da obra",
    "encarregado obra",
    "encarregado",
  ]);
  const fiscalizacaoCampo = extractLabeledValue(lines, [
    "fiscalização de campo",
    "fiscalizacao de campo",
    "fiscalização campo",
    "fiscalizacao campo",
  ]);

  draft.numeroRdo = numeroRdo || draft.numeroRdo;
  draft.dataRdo = dataRdo || draft.dataRdo;
  draft.cliente = extractLabeledValue(lines, ["cliente"]);
  draft.contrato = contrato;
  draft.rodovia = extractLabeledValue(lines, ["rodovia"]);
  draft.cidade = extractLabeledValue(lines, ["cidade"]);
  draft.uf = extractLabeledValue(lines, ["uf"]);
  draft.turno = detectPdfTurno(textoExtraido);
  draft.horaInicio = firstText(
    extractLabeledValue(lines, [
      "hora início",
      "hora inicio",
      "início",
      "inicio",
    ]),
    draft.horaInicio,
  );
  draft.horaFim = firstText(
    extractLabeledValue(lines, [
      "hora fim",
      "término",
      "termino",
      "fim",
    ]),
    draft.horaFim,
  );
  draft.preenchidoPor =
    firstText(apontadorRdo, preenchidoPorSessao) || "";
  draft.apontadorRdo = apontadorRdo;
  draft.encarregadoObra = encarregadoObra;
  draft.fiscalizacaoCampo = fiscalizacaoCampo;
  draft.observacoes = buildPdfObservacoes(textoExtraido);

  const warnings = [
    "PDF importado por texto selecionável. Confira os campos antes de salvar; tabelas muito visuais ou escaneadas podem ficar em branco.",
  ];

  if (!draft.obraId && contrato) {
    warnings.push(
      `O PDF trouxe N° da obra/contrato "${contrato}", mas o rascunho ainda precisa do Obra ID do Córtex antes de sincronizar.`,
    );
  }

  return {
    draft,
    summary: `Importado de ${file.name}: texto do PDF identificado e rascunho editável criado.`,
    warnings,
  };
}

async function extractPdfLines(
  bytes: Uint8Array,
): Promise<string[]> {
  let loadingTask: {
    promise: Promise<PdfDocumentForTextExtraction>;
    destroy: () => Promise<void>;
  } | null = null;
  try {
    const [pdfjsLib, pdfWorker] = await Promise.all([
      import("pdfjs-dist/legacy/build/pdf.mjs"),
      import("pdfjs-dist/legacy/build/pdf.worker.mjs?url"),
    ]);
    pdfjsLib.GlobalWorkerOptions.workerSrc = pdfWorker.default;
    loadingTask = pdfjsLib.getDocument({
      data: bytes,
    });
    const document = await loadingTask.promise;
    return await extractBoundedPdfLines(document);
  } catch (error: unknown) {
    if (error instanceof RdoImportResourceError) {
      throw error;
    }
    throw new Error(
      "Não foi possível ler o PDF de RDO; o arquivo pode estar corrompido ou fora do formato permitido.",
      { cause: error },
    );
  } finally {
    if (loadingTask) {
      await loadingTask.destroy().catch(() => undefined);
    }
  }
}

function extractLabeledValue(
  lines: string[],
  labels: string[],
): string {
  const normalizedLabels = labels.map((label) =>
    normalize(label).replace(/:/g, ""),
  );

  for (let index = 0; index < lines.length; index += 1) {
    const line = lines[index];
    const normalizedLine = normalize(line).replace(/:/g, "");
    const matchedLabelIndex = normalizedLabels.findIndex(
      (label) => normalizedLine.includes(label),
    );

    if (matchedLabelIndex < 0) {
      continue;
    }

    const fromSameLine = valueAfterLabel(
      line,
      labels[matchedLabelIndex],
    );
    if (fromSameLine) {
      return fromSameLine;
    }

    const nextLine = lines
      .slice(index + 1)
      .find((candidate) => candidate.trim());
    if (nextLine) {
      return nextLine.trim();
    }
  }

  return "";
}

function valueAfterLabel(
  line: string,
  label: string,
): string {
  const colonIndex = line.indexOf(":");
  const candidate =
    colonIndex >= 0
      ? line.slice(colonIndex + 1)
      : line.slice(Math.min(line.length, label.length));
  const cleaned = candidate
    .replace(/^[\s:;.,\-–—]+/, "")
    .replace(/\s+/g, " ")
    .trim();

  if (!cleaned || normalize(cleaned) === normalize(label)) {
    return "";
  }

  return cleaned;
}

function extractFirstDate(text: string): string {
  const br = text.match(
    /\b(\d{1,2})\/(\d{1,2})\/(\d{4})\b/,
  );
  if (br) {
    return `${br[3]}-${br[2].padStart(2, "0")}-${br[1].padStart(2, "0")}`;
  }

  const iso = text.match(
    /\b(\d{4})-(\d{1,2})-(\d{1,2})\b/,
  );
  if (iso) {
    return `${iso[1]}-${iso[2].padStart(2, "0")}-${iso[3].padStart(2, "0")}`;
  }

  return "";
}

function detectPdfTurno(
  text: string,
): RdoDraft["turno"] {
  const normalized = normalize(text);

  return normalized.includes("noturno") &&
    !normalized.includes("diurno")
    ? "NOTURNO"
    : "DIURNO";
}

function buildPdfObservacoes(text: string): string {
  const maxLength = 8000;
  const trimmed = text.trim();
  const visibleText =
    trimmed.length > maxLength
      ? `${trimmed.slice(0, maxLength)}\n[Texto extraído truncado para conferência inicial.]`
      : trimmed;

  return `Texto extraído do PDF importado:\n${visibleText}`;
}

function pickSheet(
  workbook: WorkSheet[],
  token: string,
  fallbackIndex: number,
): WorkSheet | null {
  const normalizedToken = normalize(token);
  const byName = workbook.find((sheet) =>
    normalize(sheet.sheet).includes(normalizedToken),
  );

  return byName ?? workbook[fallbackIndex] ?? null;
}

function hasAnyHint(sheet: WorkSheet, hints: string[]): boolean {
  for (let row = 0; row <= Math.min(sheet.data.length - 1, 80); row++) {
    const cells = sheet.data[row] ?? [];
    for (let col = 0; col < cells.length; col++) {
      const value = valueText(cells[col]);
      if (
        hints.some((hint) =>
          normalize(value).includes(normalize(hint)),
        )
      ) {
        return true;
      }
    }
  }

  return false;
}

function cell(sheet: WorkSheet | null | undefined, address: string): unknown {
  if (!sheet) {
    return null;
  }

  const position = decodeCellAddress(address);
  return sheet.data[position.row]?.[position.column] ?? null;
}

function rangeText(
  sheet: WorkSheet | null | undefined,
  address: string,
  ignoredText?: string,
): string {
  if (!sheet) {
    return "";
  }

  const range = decodeRangeAddress(address);
  const values: string[] = [];
  const ignored = normalize(ignoredText ?? "");

  for (let row = range.startRow; row <= range.endRow; row++) {
    for (let col = range.startColumn; col <= range.endColumn; col++) {
      const value = valueText(sheet.data[row]?.[col] ?? null);
      if (!value) {
        continue;
      }
      if (ignored && normalize(value) === ignored) {
        continue;
      }
      values.push(value);
    }
  }

  return [...new Set(values)].join(" ").trim();
}

function rangeTextByColumns(
  sheet: WorkSheet,
  row: number,
  startColumn: string,
  endColumn: string,
): string {
  return rangeText(sheet, `${startColumn}${row}:${endColumn}${row}`);
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

function firstText(...values: Array<string | null | undefined>): string {
  return (
    values.find(
      (value): value is string =>
        typeof value === "string" && value.trim() !== "",
    )?.trim() ?? ""
  );
}

function parseNumber(value: unknown): number | null {
  if (typeof value === "number" && Number.isFinite(value)) {
    return value;
  }

  const text = valueText(value);
  if (!text) {
    return null;
  }

  if (/^(x|sim|ok)$/i.test(text)) {
    return 1;
  }

  const match = text.match(/-?\d[\d.,]*/);
  if (!match) {
    return null;
  }

  let normalized = match[0];
  if (normalized.includes(",")) {
    normalized = normalized.replace(/\./g, "").replace(",", ".");
  }

  const parsed = Number(normalized);
  return Number.isFinite(parsed) ? parsed : null;
}

function parseQuantity(value: unknown): number | "" {
  return parseNumber(value) ?? "";
}

function dateString(value: unknown): string {
  if (value instanceof Date && !Number.isNaN(value.getTime())) {
    return value.toISOString().slice(0, 10);
  }

  if (typeof value === "number") {
    const parsed = excelSerialDate(value);
    if (parsed) {
      return parsed.toISOString().slice(0, 10);
    }
  }

  const text = valueText(value);
  const br = text.match(/^(\d{1,2})\/(\d{1,2})\/(\d{4})$/);
  if (br) {
    return `${br[3]}-${br[2].padStart(2, "0")}-${br[1].padStart(2, "0")}`;
  }

  const iso = text.match(/^(\d{4})-(\d{1,2})-(\d{1,2})$/);
  if (iso) {
    return `${iso[1]}-${iso[2].padStart(2, "0")}-${iso[3].padStart(2, "0")}`;
  }

  return "";
}

function timeString(value: unknown): string {
  if (value instanceof Date && !Number.isNaN(value.getTime())) {
    return `${value.getHours().toString().padStart(2, "0")}:${value
      .getMinutes()
      .toString()
      .padStart(2, "0")}`;
  }

  if (typeof value === "number" && value >= 0 && value < 1) {
    const totalMinutes = Math.round(value * 24 * 60);
    const hours = Math.floor(totalMinutes / 60) % 24;
    const minutes = totalMinutes % 60;
    return `${hours.toString().padStart(2, "0")}:${minutes
      .toString()
      .padStart(2, "0")}`;
  }

  const text = valueText(value);
  const match = text.match(/^(\d{1,2}):(\d{2})/);
  if (!match) {
    return "";
  }

  return `${match[1].padStart(2, "0")}:${match[2]}`;
}

function detectTurno(sheet: WorkSheet): RdoDraft["turno"] {
  const diurno = firstText(
    rangeText(sheet, "AA10:AE10"),
    rangeText(sheet, "AA12:AE12"),
  );
  const noturno = firstText(
    rangeText(sheet, "AF10:AJ10"),
    rangeText(sheet, "AF12:AJ12"),
  );

  return !diurno && noturno ? "NOTURNO" : "DIURNO";
}

function detectCondicao(
  sheet: WorkSheet,
  row: number,
): CondicaoClimatica {
  if (rangeTextByColumns(sheet, row, "D", "F")) {
    return "BOM";
  }
  if (rangeTextByColumns(sheet, row, "G", "I")) {
    return "CHUVA";
  }
  if (rangeTextByColumns(sheet, row, "J", "L")) {
    return "IMPOSSIBILITADO";
  }

  return "";
}

function parseMaoObra(sheet: WorkSheet): MaoObraDraft[] {
  const items: MaoObraDraft[] = [];

  for (let row = 16; row <= 28; row++) {
    items.push(
      ...parseMaoObraBlock(sheet, row, "B", "G", "J"),
      ...parseMaoObraBlock(sheet, row, "M", "R", "U"),
    );
  }

  return items;
}

function parseMaoObraBlock(
  sheet: WorkSheet,
  row: number,
  cargoColumn: string,
  contratadoColumn: string,
  subcontratadoColumn: string,
): MaoObraDraft[] {
  const cargo = rangeTextByColumns(sheet, row, cargoColumn, cargoColumn);
  if (!cargo) {
    return [];
  }

  const contratado = parseNumber(
    rangeTextByColumns(sheet, row, contratadoColumn, contratadoColumn),
  );
  const subcontratado = parseNumber(
    rangeTextByColumns(sheet, row, subcontratadoColumn, subcontratadoColumn),
  );
  const items: MaoObraDraft[] = [];

  if (contratado !== null) {
    items.push({
      ...createEmptyMaoObra(),
      cargo,
      tipoVinculo: "CONTRATADO",
      quantidade: contratado,
    });
  }

  if (subcontratado !== null) {
    items.push({
      ...createEmptyMaoObra(),
      cargo,
      tipoVinculo: "TERCEIRIZADO",
      quantidade: subcontratado,
    });
  }

  return items;
}

function parseEquipamentos(sheet: WorkSheet): EquipamentoDraft[] {
  const items: EquipamentoDraft[] = [];

  for (let row = 36; row <= 51; row++) {
    items.push(
      ...parseEquipamentoBlock(sheet, row, "B", "I", "L", "O"),
      ...parseEquipamentoBlock(sheet, row, "T", "AB", "AE", "AH"),
    );
  }

  return items;
}

function parseEquipamentoBlock(
  sheet: WorkSheet,
  row: number,
  descricaoColumn: string,
  proprioColumn: string,
  subcontratadoColumn: string,
  prefixoColumn: string,
): EquipamentoDraft[] {
  const descricao = rangeTextByColumns(
    sheet,
    row,
    descricaoColumn,
    descricaoColumn,
  );
  if (!descricao) {
    return [];
  }

  const prefixo = rangeTextByColumns(sheet, row, prefixoColumn, prefixoColumn);
  const proprio = parseNumber(
    rangeTextByColumns(sheet, row, proprioColumn, proprioColumn),
  );
  const subcontratado = parseNumber(
    rangeTextByColumns(sheet, row, subcontratadoColumn, subcontratadoColumn),
  );
  const items: EquipamentoDraft[] = [];

  if (proprio !== null || prefixo) {
    items.push({
      ...createEmptyEquipamento(),
      prefixo,
      descricao,
      tipoEquipamento: descricao,
      tipoVinculo: "PROPRIO",
      quantidade: proprio ?? 1,
    });
  }

  if (subcontratado !== null) {
    items.push({
      ...createEmptyEquipamento(),
      prefixo,
      descricao,
      tipoEquipamento: descricao,
      tipoVinculo: "TERCEIRIZADO",
      quantidade: subcontratado,
    });
  }

  return items;
}

function parseMateriais(sheet: WorkSheet): MaterialDraft[] {
  const items: MaterialDraft[] = [];

  for (let row = 8; row <= 18; row++) {
    items.push(
      ...parseMaterialBlock(sheet, row, "B", "E", "G", "H"),
      ...parseMaterialBlock(sheet, row, "L", "O", "Q", "R"),
      ...parseMaterialBlock(sheet, row, "V", "Y", "AA", "AB"),
    );
  }

  const usinado = parseNumber(rangeText(sheet, "B19:G19"));
  const aplicado = parseNumber(rangeText(sheet, "B20:G20"));
  const sobra = parseNumber(rangeText(sheet, "B21:G21"));

  if (items.length === 0 && (usinado !== null || aplicado !== null || sobra !== null)) {
    items.push({
      ...createEmptyMaterial(),
      materialNome: "Material usinado/aplicado",
      quantidadeUsinada: usinado ?? "",
      quantidadeAplicada: aplicado ?? "",
      quantidadeSobra: sobra ?? "",
    });
  }

  return items.map((item) => ({
    ...item,
    quantidadeUsinada:
      item.quantidadeUsinada === "" && usinado !== null
        ? usinado
        : item.quantidadeUsinada,
    quantidadeSobra:
      item.quantidadeSobra === "" && sobra !== null
        ? sobra
        : item.quantidadeSobra,
  }));
}

function parseMaterialBlock(
  sheet: WorkSheet,
  row: number,
  materialColumn: string,
  quantidadeColumn: string,
  unidadeColumn: string,
  notaFiscalColumn: string,
): MaterialDraft[] {
  const materialNome = rangeTextByColumns(
    sheet,
    row,
    materialColumn,
    materialColumn,
  );
  const quantidade = parseNumber(
    rangeTextByColumns(sheet, row, quantidadeColumn, quantidadeColumn),
  );
  const unidade = normalizarUnidade(
    rangeTextByColumns(sheet, row, unidadeColumn, unidadeColumn),
  );
  const notaFiscal = rangeTextByColumns(
    sheet,
    row,
    notaFiscalColumn,
    notaFiscalColumn,
  );

  if (!materialNome && quantidade === null && !unidade && !notaFiscal) {
    return [];
  }

  return [
    {
      ...createEmptyMaterial(),
      materialNome,
      unidade,
      quantidadeAplicada: quantidade ?? "",
      notaFiscal,
    },
  ];
}

function parseTrechoTrabalhado(sheet: WorkSheet): ControleGeometricoDraft[] {
  const items: ControleGeometricoDraft[] = [];

  for (let row = 60; row <= 82; row++) {
    const kmInicial = rangeTextByColumns(sheet, row, "B", "D");
    const kmFinal = rangeTextByColumns(sheet, row, "E", "G");
    const numero = rangeTextByColumns(sheet, row, "H", "I");
    const comprimentoM = parseQuantity(rangeTextByColumns(sheet, row, "J", "K"));
    const larguraM = parseQuantity(rangeTextByColumns(sheet, row, "L", "M"));
    const espessuraM = parseNumber(rangeTextByColumns(sheet, row, "N", "O"));
    const pista = rangeTextByColumns(sheet, row, "P", "Q");
    const faixa = rangeTextByColumns(sheet, row, "R", "S");
    const ordemServico = rangeTextByColumns(sheet, row, "T", "W");
    const atividadeObservacoes = rangeTextByColumns(sheet, row, "X", "AJ");

    if (
      !firstText(
        kmInicial,
        kmFinal,
        numero,
        valueText(comprimentoM),
        valueText(larguraM),
        valueText(espessuraM),
        pista,
        faixa,
        ordemServico,
        atividadeObservacoes,
      )
    ) {
      continue;
    }

    items.push({
      ...createEmptyControleGeometrico(),
      subtrecho: firstText(numero, kmInicial, kmFinal),
      numero,
      kmInicial,
      kmFinal,
      pista,
      faixa,
      ordemServico,
      atividadeObservacoes,
      comprimentoM,
      larguraM,
      espessura1Cm: espessuraM === null ? "" : espessuraM * 100,
      observacoes: atividadeObservacoes,
    });
  }

  return items;
}

function parseControleGeometrico(sheet: WorkSheet): ControleGeometricoDraft[] {
  const items: ControleGeometricoDraft[] = [];

  for (let row = 26; row <= 61; row++) {
    const subtrecho = rangeTextByColumns(sheet, row, "B", "E");
    const comprimentoM = parseQuantity(rangeTextByColumns(sheet, row, "F", "H"));
    const larguraM = parseQuantity(rangeTextByColumns(sheet, row, "I", "K"));
    const espessura1M = parseNumber(rangeTextByColumns(sheet, row, "L", "N"));
    const espessura2M = parseNumber(rangeTextByColumns(sheet, row, "O", "Q"));
    const espessura3M = parseNumber(rangeTextByColumns(sheet, row, "R", "T"));
    const massa = parseNumber(rangeTextByColumns(sheet, row, "AA", "AD"));

    if (
      !firstText(
        subtrecho,
        valueText(comprimentoM),
        valueText(larguraM),
        valueText(espessura1M),
        valueText(espessura2M),
        valueText(espessura3M),
        valueText(massa),
      )
    ) {
      continue;
    }

    items.push({
      ...createEmptyControleGeometrico(),
      subtrecho,
      comprimentoM,
      larguraM,
      espessura1Cm: espessura1M === null ? "" : espessura1M * 100,
      espessura2Cm: espessura2M === null ? "" : espessura2M * 100,
      espessura3Cm: espessura3M === null ? "" : espessura3M * 100,
      observacoes:
        massa === null
          ? ""
          : `Massa informada no Excel: ${massa} t`,
    });
  }

  return items;
}

function decodeCellAddress(address: string): {
  row: number;
  column: number;
} {
  const match = address.match(/^([A-Z]+)(\d+)$/i);
  if (!match) {
    throw new Error(`Endereço de célula inválido: ${address}`);
  }

  return {
    row: Number(match[2]) - 1,
    column: columnIndex(match[1]),
  };
}

function decodeRangeAddress(address: string): {
  startRow: number;
  endRow: number;
  startColumn: number;
  endColumn: number;
} {
  const [startAddress, endAddress = startAddress] = address.split(":");
  const start = decodeCellAddress(startAddress);
  const end = decodeCellAddress(endAddress);

  return {
    startRow: Math.min(start.row, end.row),
    endRow: Math.max(start.row, end.row),
    startColumn: Math.min(start.column, end.column),
    endColumn: Math.max(start.column, end.column),
  };
}

function columnIndex(column: string): number {
  return column
    .toUpperCase()
    .split("")
    .reduce(
      (sum, character) =>
        sum * 26 + character.charCodeAt(0) - 64,
      0,
    ) - 1;
}

function excelSerialDate(value: number): Date | null {
  if (!Number.isFinite(value) || value < 20_000) {
    return null;
  }

  const epoch = Date.UTC(1899, 11, 30);
  return new Date(epoch + value * 86_400_000);
}

function normalize(value: string): string {
  return value
    .normalize("NFD")
    .replace(/[\u0300-\u036f]/g, "")
    .toLowerCase()
    .trim();
}
