import { jsPDF } from "jspdf";

import corporateWordmark from "../../../assets/login/stavias-logo.png?inline";
import type { NumericInput } from "../rdo.types";
import {
  buildRdoExportProjection,
  rdoExportEquipmentOwnership,
  type RdoExportProjection,
} from "./rdoExportProjection";
import {
  RdoWorkbookExportError,
  sanitizeRdoCellText,
  type RdoWorkbookSnapshot,
} from "./rdoWorkbookMapping";
import {
  assertPdfRenderableText,
  validateOriginalPdfSources,
} from "./rdoPdfValidation";

export { assertPdfRenderableText, validateOriginalPdfSources } from "./rdoPdfValidation";

const PAGE_WIDTH = 210;
const PAGE_MARGIN = 10;
const CONTENT_WIDTH = PAGE_WIDTH - (2 * PAGE_MARGIN);
const SECTION_HEIGHT = 5;
const CELL_PADDING = 0.7;
const SECTION_GRAY = 225;
const WORDMARK_WIDTH = 38;
const WORDMARK_HEIGHT = WORDMARK_WIDTH * (329 / 1200);
const WORDMARK_TOP = 4;
const WORDMARK_ALIAS = "rdo-corporate-wordmark";
const MIN_READABLE_FONT_SIZE = 4;
const UNREADABLE_FITTED_TEXT_MESSAGE =
  "O conteúdo do RDO não permanece legível na célula fixa do PDF; nenhum conteúdo foi truncado.";

type FontStyle = "normal" | "bold";
type TextAlignment = "left" | "center" | "right";

function userText(value: string, allowLineBreaks = false): string {
  assertPdfRenderableText(value, allowLineBreaks);
  const sanitized = sanitizeRdoCellText(value);
  assertPdfRenderableText(sanitized, allowLineBreaks);
  return sanitized;
}

function validateProjectionText(projection: RdoExportProjection): void {
  const { obra, rdo } = projection.snapshot;
  const singleLineValues = [
    obra?.nome ?? "",
    obra?.codigoContrato ?? "",
    rdo.numeroRdo,
    rdo.rodovia,
    rdo.turno,
    rdo.horaInicio,
    rdo.horaFim,
    rdo.kmInicialProgramado,
    rdo.kmFinalProgramado,
    rdo.kmInicialInterditado,
    rdo.kmFinalInterditado,
    projection.apontadorName,
    rdo.encarregadoObra,
    rdo.fiscalizacaoCampo,
    ...projection.workforce.map((item) => item.role),
    ...projection.equipment.flatMap((item) => [
      item.descricao,
      item.prefixo,
    ]),
    ...projection.worked.flatMap((item) => [
      item.start,
      item.end,
      item.itemNumber,
      item.roadway,
      item.lane,
      item.serviceOrder,
      item.activity,
    ]),
    ...projection.materials.flatMap((item) => [
      item.description,
      item.unit,
      item.invoice,
    ]),
    ...projection.geometry.map((item) => item.subtrecho),
  ];
  for (const value of singleLineValues) {
    userText(value);
  }
  userText(projection.observations, true);
}

function setFont(
  document: jsPDF,
  style: FontStyle,
  size: number,
): void {
  document.setFont("helvetica", style);
  document.setFontSize(size);
}

function drawText(
  document: jsPDF,
  value: string,
  x: number,
  y: number,
  style: FontStyle,
  size: number,
  align: TextAlignment = "left",
): void {
  if (!value) return;
  assertPdfRenderableText(value);
  setFont(document, style, size);
  document.text(value, x, y, { align });
}

function drawFittedText(
  document: jsPDF,
  value: string,
  x: number,
  baseline: number,
  width: number,
  style: FontStyle,
  maximumSize: number,
): void {
  if (!value.trim()) return;
  assertPdfRenderableText(value);
  setFont(document, style, maximumSize);
  const measuredWidth = document.getTextWidth(value);
  const size = measuredWidth <= width
    ? maximumSize
    : maximumSize * (width / measuredWidth);
  if (size < MIN_READABLE_FONT_SIZE) {
    throw new RdoWorkbookExportError(
      "RDO_EXPORT_PRINT_OVERFLOW",
      UNREADABLE_FITTED_TEXT_MESSAGE,
    );
  }
  drawText(document, value, x, baseline, style, size);
}

function drawRow(
  document: jsPDF,
  top: number,
  widths: number[],
  height: number,
  values: string[],
  style: FontStyle,
  maximumSize: number,
): number {
  if (widths.length !== values.length) {
    throw new Error("Células e larguras incompatíveis.");
  }
  document.setDrawColor(0);
  document.setLineWidth(0.12);
  let x = PAGE_MARGIN;
  for (let index = 0; index < values.length; index += 1) {
    document.rect(x, top, widths[index], height);
    drawFittedText(
      document,
      values[index],
      x + CELL_PADDING,
      top + height - 1.2,
      widths[index] - (2 * CELL_PADDING),
      style,
      maximumSize,
    );
    x += widths[index];
  }
  return top + height;
}

function sectionBar(
  document: jsPDF,
  top: number,
  title: string,
): number {
  document.setDrawColor(0);
  document.setFillColor(SECTION_GRAY, SECTION_GRAY, SECTION_GRAY);
  document.setLineWidth(0.12);
  document.rect(
    PAGE_MARGIN,
    top,
    CONTENT_WIDTH,
    SECTION_HEIGHT,
    "FD",
  );
  drawText(
    document,
    title,
    PAGE_MARGIN + 1.4,
    top + 3.5,
    "bold",
    7,
  );
  return top + SECTION_HEIGHT;
}

function item<T>(values: T[], index: number): T | undefined {
  return index >= 0 && index < values.length
    ? values[index]
    : undefined;
}

function numeric(value: NumericInput | number | null): string {
  return typeof value === "number" && Number.isFinite(value)
    ? String(value)
    : "";
}

function date(value: string): string {
  const match = /^(\d{4})-(\d{2})-(\d{2})$/.exec(value);
  return match
    ? `${match[3]}/${match[2]}/${match[1]}`
    : userText(value);
}

function weekday(value: string): string {
  const match = /^(\d{4})-(\d{2})-(\d{2})$/.exec(value);
  if (!match) return "";
  return new Intl.DateTimeFormat("pt-BR", {
    weekday: "long",
    timeZone: "UTC",
  }).format(
    new Date(
      Date.UTC(
        Number(match[1]),
        Number(match[2]) - 1,
        Number(match[3]),
      ),
    ),
  );
}

function weather(value: string): string {
  switch (value.trim().toLocaleUpperCase("pt-BR")) {
    case "BOM": return "Bom";
    case "NUBLADO": return "Nublado";
    case "CHUVA": return "Chuva";
    case "IMPOSSIBILITADO": return "Impossibilitado";
    case "NAO_APLICAVEL": return "N/A";
    default: return "";
  }
}

function range(start: string, end: string): string {
  const safeStart = userText(start);
  const safeEnd = userText(end);
  if (!safeStart) return safeEnd;
  if (!safeEnd) return safeStart;
  return `${safeStart} a ${safeEnd}`;
}

function labelValue(label: string, value: string): string {
  return value ? `${label}: ${value}` : `${label}:`;
}

function drawHeading(
  document: jsPDF,
  face: "FRENTE" | "VERSO",
  rdoNumber: string,
): number {
  document.addImage(
    corporateWordmark,
    "PNG",
    PAGE_MARGIN,
    WORDMARK_TOP,
    WORDMARK_WIDTH,
    WORDMARK_HEIGHT,
    WORDMARK_ALIAS,
    "FAST",
  );
  drawText(
    document,
    `${face}  |  RDO ${rdoNumber}`,
    PAGE_WIDTH - PAGE_MARGIN,
    14,
    "bold",
    8,
    "right",
  );
  drawText(
    document,
    "RELATÓRIO DIÁRIO DE OBRA",
    PAGE_WIDTH / 2,
    21,
    "bold",
    11,
    "center",
  );
  document.setDrawColor(0);
  document.setLineWidth(0.12);
  document.line(PAGE_MARGIN, 24, PAGE_WIDTH - PAGE_MARGIN, 24);
  return 27;
}

function drawFrontIdentity(
  document: jsPDF,
  top: number,
  projection: RdoExportProjection,
): number {
  const { obra, rdo } = projection.snapshot;
  let y = drawRow(
    document,
    top,
    [90, 55, 45],
    7,
    [
      labelValue("OBRA", userText(obra?.nome ?? "")),
      labelValue(
        "CONTRATO/CÓDIGO",
        userText(obra?.codigoContrato ?? ""),
      ),
      labelValue("RDO", userText(rdo.numeroRdo)),
    ],
    "bold",
    7,
  );
  y = drawRow(
    document,
    y,
    [52, 32, 58, 48],
    7,
    [
      labelValue("RODOVIA", userText(rdo.rodovia)),
      labelValue("DATA", date(rdo.dataRdo)),
      labelValue("DIA", weekday(rdo.dataRdo)),
      labelValue("TURNO", userText(rdo.turno)),
    ],
    "normal",
    6.6,
  );
  return y + 2;
}

function drawConditions(
  document: jsPDF,
  top: number,
  projection: RdoExportProjection,
): number {
  const { rdo } = projection.snapshot;
  const widths = [47.5, 47.5, 47.5, 47.5];
  let y = drawRow(
    document,
    top,
    widths,
    7,
    [
      labelValue("MANHÃ", weather(rdo.condicaoManha)),
      labelValue("TARDE", weather(rdo.condicaoTarde)),
      labelValue("NOITE", weather(rdo.condicaoNoite)),
      labelValue("CHUVA (mm)", numeric(rdo.pluviometriaMm)),
    ],
    "normal",
    6.4,
  );
  y = drawRow(
    document,
    y,
    widths,
    7,
    [
      labelValue(
        "PROGRAMADO",
        range(rdo.kmInicialProgramado, rdo.kmFinalProgramado),
      ),
      labelValue(
        "INTERDITADO",
        range(rdo.kmInicialInterditado, rdo.kmFinalInterditado),
      ),
      labelValue("INÍCIO", userText(rdo.horaInicio)),
      labelValue("FIM", userText(rdo.horaFim)),
    ],
    "normal",
    6.2,
  );
  return y + 2;
}

function drawWorkforce(
  document: jsPDF,
  top: number,
  projection: RdoExportProjection,
): number {
  const widths = [56, 18, 21, 56, 18, 21];
  let y = drawRow(
    document,
    top,
    widths,
    4,
    [
      "FUNÇÃO",
      "PRÓPRIA",
      "SUBCONT.",
      "FUNÇÃO",
      "PRÓPRIA",
      "SUBCONT.",
    ],
    "bold",
    5.4,
  );
  for (let row = 0; row < 13; row += 1) {
    const left = item(projection.workforce, row);
    const right = item(projection.workforce, row + 13);
    y = drawRow(
      document,
      y,
      widths,
      3,
      [
        left ? userText(left.role) : "",
        left && !left.subcontracted ? numeric(left.quantity) : "",
        left?.subcontracted ? numeric(left.quantity) : "",
        right ? userText(right.role) : "",
        right && !right.subcontracted ? numeric(right.quantity) : "",
        right?.subcontracted ? numeric(right.quantity) : "",
      ],
      "normal",
      5.2,
    );
  }
  return y + 2;
}

function equipmentCells(
  projection: RdoExportProjection,
  index: number,
): string[] {
  const value = item(projection.equipment, index);
  if (!value) return ["", "", "", ""];
  return [
    userText(value.descricao),
    userText(value.prefixo),
    numeric(value.quantidade),
    rdoExportEquipmentOwnership(value.tipoVinculo) === "OWNED"
      ? "PRÓPRIO"
      : "TERCEIRO",
  ];
}

function drawEquipment(
  document: jsPDF,
  top: number,
  projection: RdoExportProjection,
): number {
  const widths = [45, 18, 12, 20, 45, 18, 12, 20];
  let y = drawRow(
    document,
    top,
    widths,
    4,
    [
      "DESCRIÇÃO",
      "PREFIXO",
      "QTD.",
      "VÍNCULO",
      "DESCRIÇÃO",
      "PREFIXO",
      "QTD.",
      "VÍNCULO",
    ],
    "bold",
    5.1,
  );
  for (let row = 0; row < 16; row += 1) {
    y = drawRow(
      document,
      y,
      widths,
      3,
      [
        ...equipmentCells(projection, row),
        ...equipmentCells(projection, row + 16),
      ],
      "normal",
      5,
    );
  }
  return y + 2;
}

function workedCells(
  projection: RdoExportProjection,
  index: number,
): string[] {
  const value = item(projection.worked, index);
  if (!value) return Array.from({ length: 10 }, () => "");
  return [
    userText(value.start),
    userText(value.end),
    userText(value.itemNumber),
    numeric(value.length),
    numeric(value.width),
    value.thicknessMeters === null
      ? ""
      : numeric(value.thicknessMeters * 100),
    userText(value.roadway),
    userText(value.lane),
    userText(value.serviceOrder),
    userText(value.activity),
  ];
}

function drawWorked(
  document: jsPDF,
  top: number,
  projection: RdoExportProjection,
): void {
  const widths = [14, 14, 9, 13, 12, 12, 18, 15, 20, 63];
  let y = drawRow(
    document,
    top,
    widths,
    4,
    [
      "INÍCIO",
      "FIM",
      "Nº",
      "COMP.",
      "LARG.",
      "ESP. cm",
      "PISTA",
      "FAIXA",
      "OS",
      "ATIVIDADE / SERVIÇO",
    ],
    "bold",
    5.2,
  );
  for (let row = 0; row < 21; row += 1) {
    y = drawRow(
      document,
      y,
      widths,
      5,
      workedCells(projection, row),
      "normal",
      5.2,
    );
  }
}

function materialCells(
  projection: RdoExportProjection,
  index: number,
): string[] {
  const value = item(projection.materials, index);
  if (!value) return ["", "", "", ""];
  return [
    userText(value.description),
    numeric(value.quantity),
    userText(value.unit),
    userText(value.invoice),
  ];
}

function drawMaterials(
  document: jsPDF,
  top: number,
  projection: RdoExportProjection,
): number {
  const widths = [
    35, 12, 7, 9.333333,
    35, 12, 7, 9.333333,
    35, 12, 7, 9.333334,
  ];
  let y = drawRow(
    document,
    top,
    widths,
    4,
    [
      "MATERIAL", "QTD.", "UN.", "NF",
      "MATERIAL", "QTD.", "UN.", "NF",
      "MATERIAL", "QTD.", "UN.", "NF",
    ],
    "bold",
    4.8,
  );
  for (let row = 0; row < 10; row += 1) {
    y = drawRow(
      document,
      y,
      widths,
      4,
      [
        ...materialCells(projection, row),
        ...materialCells(projection, row + 10),
        ...materialCells(projection, row + 20),
      ],
      "normal",
      4.7,
    );
  }
  return y + 2;
}

function drawObservations(
  document: jsPDF,
  top: number,
  observations: string,
): number {
  const height = 35;
  const safe = userText(observations, true)
    .replace(/\r\n/g, "\n")
    .replace(/\r/g, "\n");
  document.setDrawColor(0);
  document.setLineWidth(0.12);
  document.rect(PAGE_MARGIN, top, CONTENT_WIDTH, height);
  assertPdfRenderableText(safe, true);
  setFont(document, "normal", 6.5);
  const lines = document.splitTextToSize(
    safe,
    CONTENT_WIDTH - 4,
  ) as string[];
  const lineHeight = 3;
  const capacity = Math.floor((height - 4) / lineHeight);
  if (lines.length > capacity) {
    throw new RdoWorkbookExportError(
      "RDO_EXPORT_PRINT_OVERFLOW",
      "O conteúdo de observações gerais não permanece legível no RDO (área fixa de observações do PDF); nenhum conteúdo foi truncado.",
    );
  }
  let baseline = top + 3.5;
  for (const line of lines) {
    drawText(
      document,
      line,
      PAGE_MARGIN + 2,
      baseline,
      "normal",
      6.5,
    );
    baseline += lineHeight;
  }
  return top + height + 2;
}

function drawSignatures(
  document: jsPDF,
  top: number,
  projection: RdoExportProjection,
): void {
  const gap = 4;
  const signatureWidth = (CONTENT_WIDTH - (2 * gap)) / 3;
  const lineY = top + 15;
  document.setDrawColor(0);
  document.setLineWidth(0.12);
  for (let index = 0; index < 3; index += 1) {
    const x = PAGE_MARGIN + (index * (signatureWidth + gap));
    document.line(x, lineY, x + signatureWidth, lineY);
  }
  const names = [
    userText(projection.apontadorName),
    userText(projection.snapshot.rdo.encarregadoObra),
    userText(projection.snapshot.rdo.fiscalizacaoCampo),
  ];
  const roles = ["APONTADOR", "ENCARREGADO", "FISCALIZAÇÃO"];
  for (let index = 0; index < names.length; index += 1) {
    const x = PAGE_MARGIN + (index * (signatureWidth + gap));
    drawFittedText(
      document,
      names[index],
      x,
      lineY + 5,
      signatureWidth,
      "normal",
      6.5,
    );
    drawText(
      document,
      roles[index],
      x + (signatureWidth / 2),
      lineY + 10,
      "bold",
      5.8,
      "center",
    );
  }
}

function renderFront(
  document: jsPDF,
  projection: RdoExportProjection,
): void {
  let y = drawHeading(
    document,
    "FRENTE",
    userText(projection.snapshot.rdo.numeroRdo),
  );
  y = drawFrontIdentity(document, y, projection);
  y = sectionBar(document, y, "CONDIÇÕES, INTERDIÇÃO E TURNO");
  y = drawConditions(document, y, projection);
  y = sectionBar(document, y, "MÃO DE OBRA");
  y = drawWorkforce(document, y, projection);
  y = sectionBar(document, y, "EQUIPAMENTOS E VEÍCULOS");
  y = drawEquipment(document, y, projection);
  y = sectionBar(document, y, "TRECHOS E SERVIÇOS");
  drawWorked(document, y, projection);
}

function renderBack(
  document: jsPDF,
  projection: RdoExportProjection,
): void {
  let y = drawHeading(
    document,
    "VERSO",
    userText(projection.snapshot.rdo.numeroRdo),
  );
  y = sectionBar(document, y, "MATERIAIS");
  y = drawMaterials(document, y, projection);
  /*
   * A seção de controle geométrico saiu do verso junto com a etapa que a
   * preenchia, e era duplicata: as mesmas medidas já aparecem na tabela de
   * trecho trabalhado da frente, agora também para os serviços. RDO anterior à
   * remoção continua mostrando o que registrou — na tabela da frente, uma vez
   * só. Espelha a remoção equivalente em RdoPdfFormRenderer.
   */
  y = sectionBar(document, y, "OBSERVAÇÕES");
  y = drawObservations(document, y, projection.observations);
  y = sectionBar(document, y, "ASSINATURAS");
  drawSignatures(document, y, projection);
}

export function buildRdoPdf(snapshot: RdoWorkbookSnapshot): jsPDF {
  validateOriginalPdfSources(snapshot);
  const projection = buildRdoExportProjection(snapshot);
  validateProjectionText(projection);
  const document = new jsPDF({
    orientation: "portrait",
    unit: "mm",
    format: "a4",
    compress: false,
  });
  renderFront(document, projection);
  document.addPage("a4", "portrait");
  renderBack(document, projection);
  if (document.getNumberOfPages() !== 2) {
    throw new Error("O PDF do RDO deve preservar exatamente duas faces.");
  }
  return document;
}
