import * as XLSX from "@e965/xlsx";
import { unzipSync, zipSync } from "fflate";

import { apiFetch } from "../../../lib/api/apiClient";
import templateUrl from "./RDO-v1.xlsx?url";
import {
  RDO_BACK_SHEET,
  RDO_FRONT_SHEET,
  RDO_OPERATIONAL_CLEAR_RANGES,
  RDO_TEMPLATE_SHA256,
  RdoWorkbookExportError,
  mapRdoWorkbook,
  sanitizeRdoCellText,
  type RdoWorkbookCellValue,
  type RdoWorkbookMapping,
  type RdoWorkbookSnapshot,
} from "./rdoWorkbookMapping";

export const RDO_BUNDLED_TEMPLATE_URL = templateUrl;
export const RDO_XLSX_MEDIA_TYPE =
  "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

export interface ExportRdoWorkbookOptions {
  templateBytes?: Uint8Array;
}

const encoder = new TextEncoder();
const decoder = new TextDecoder();

function templateError(message: string): never {
  throw new RdoWorkbookExportError("RDO_EXPORT_TEMPLATE_INVALID", message);
}

async function sha256(bytes: Uint8Array): Promise<string> {
  const stableBytes = new Uint8Array(bytes);
  const digest = await crypto.subtle.digest("SHA-256", stableBytes.buffer);
  return Array.from(new Uint8Array(digest), (byte) =>
    byte.toString(16).padStart(2, "0"),
  ).join("");
}

async function loadTemplate(): Promise<Uint8Array> {
  const response = await fetch(RDO_BUNDLED_TEMPLATE_URL, {
    cache: "force-cache",
    credentials: "same-origin",
  });
  if (!response.ok) {
    return templateError(
      `O template offline do RDO não pôde ser carregado (${response.status}).`,
    );
  }
  return new Uint8Array(await response.arrayBuffer());
}

function validateTemplate(bytes: Uint8Array): void {
  const workbook = XLSX.read(bytes, {
    type: "array",
    cellStyles: true,
    bookVBA: true,
  });
  if (
    workbook.SheetNames.length !== 2 ||
    workbook.SheetNames[0] !== RDO_FRONT_SHEET ||
    workbook.SheetNames[1] !== RDO_BACK_SHEET ||
    workbook.vbaraw
  ) {
    templateError(
      "O template RDO v1 não preserva as duas faces esperadas ou contém macro.",
    );
  }
  const names = workbook.Workbook?.Names ?? [];
  if (
    names.length !== 1 ||
    names[0].Name !== "_xlnm.Print_Area" ||
    names[0].Sheet !== 1 ||
    names[0].Ref !== "'v.1 RDO verso'!$A$2:$AH$70"
  ) {
    templateError("A área de impressão do template RDO v1 divergiu.");
  }
}

function xml(bytes: Uint8Array | undefined, path: string): string {
  if (!bytes) templateError(`Parte obrigatória ausente no template: ${path}.`);
  return decoder.decode(bytes);
}

function setXml(
  files: Record<string, Uint8Array>,
  path: string,
  value: string,
): void {
  files[path] = encoder.encode(value);
}

function escapeXml(value: string): string {
  return value
    .replace(/&/g, "&amp;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;")
    .replace(/"/g, "&quot;")
    .replace(/'/g, "&apos;");
}

function escapeRegExp(value: string): string {
  return value.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
}

function cellPattern(address: string): RegExp {
  return new RegExp(
    `<c\\b(?=[^>]*\\br="${escapeRegExp(address)}")(?:[^>]*?/>|[^>]*>[\\s\\S]*?</c>)`,
  );
}

function cellAttributes(sheetXml: string, address: string): string {
  const match = cellPattern(address).exec(sheetXml);
  if (!match) templateError(`A célula ${address} não existe no template RDO v1.`);
  const opening = /^<c\b([^>]*?)(?:\/?>)/.exec(match[0]);
  if (!opening) templateError(`A célula ${address} é inválida no template RDO v1.`);
  return opening[1].replace(/\/$/, "").trimEnd();
}

function baseStyleId(sheetXml: string, address: string): number {
  const match = /\bs="(\d+)"/.exec(cellAttributes(sheetXml, address));
  return match ? Number(match[1]) : 0;
}

type StyleVariant = "text" | "wrapped" | "number" | "date" | "time";

function cellVariant(cell: RdoWorkbookCellValue): StyleVariant {
  if (cell.kind === "text") {
    return cell.presentation === "wrapped" ? "wrapped" : "text";
  }
  return cell.kind;
}

function replaceAttribute(
  attributes: string,
  name: string,
  value: string,
): string {
  const pattern = new RegExp(`\\s${name}="[^"]*"`, "g");
  return pattern.test(attributes)
    ? attributes.replace(pattern, ` ${name}="${value}"`)
    : `${attributes} ${name}="${value}"`;
}

function setAlignmentAttributes(
  content: string,
  attributes: Record<string, string>,
): string {
  const existing = /<alignment\b([^>]*?)(?:\/>|>[\s\S]*?<\/alignment>)/.exec(content);
  let alignmentAttributes = existing?.[1] ?? "";
  for (const [name, value] of Object.entries(attributes)) {
    alignmentAttributes = replaceAttribute(alignmentAttributes, name, value);
  }
  const alignment = `<alignment${alignmentAttributes}/>`;
  return existing
    ? content.slice(0, existing.index) + alignment +
        content.slice(existing.index + existing[0].length)
    : content + alignment;
}

function styleVariantXml(base: string, variant: StyleVariant): string {
  const open = /^<xf\b([^>]*?)(\/?)>/.exec(base);
  if (!open) templateError("Estilo de célula inválido no template RDO v1.");
  let attributes = open[1];
  let content = open[2] === "/"
    ? ""
    : base.slice(open[0].length, base.lastIndexOf("</xf>"));

  if (variant === "date" || variant === "time" || variant === "number") {
    const numFmtId = variant === "date" ? "164" : variant === "time" ? "165" : "166";
    attributes = replaceAttribute(attributes, "numFmtId", numFmtId);
    attributes = replaceAttribute(attributes, "applyNumberFormat", "1");
  }
  if (variant === "text" || variant === "wrapped") {
    attributes = replaceAttribute(attributes, "applyAlignment", "1");
    content = setAlignmentAttributes(
      content,
      variant === "wrapped"
        ? { vertical: "top", wrapText: "1", shrinkToFit: "0" }
        : { wrapText: "0", shrinkToFit: "1" },
    );
  }
  return `<xf${attributes}>${content}</xf>`;
}

function patchStyles(
  files: Record<string, Uint8Array>,
  sheetXmlByName: Record<string, string>,
  mapping: RdoWorkbookMapping,
): Map<string, number> {
  const path = "xl/styles.xml";
  let styles = xml(files[path], path);
  const cellXfsMatch = /<cellXfs\b[^>]*count="(\d+)"[^>]*>([\s\S]*?)<\/cellXfs>/.exec(styles);
  if (!cellXfsMatch) templateError("Estilos de célula ausentes no template RDO v1.");
  const baseStyles = cellXfsMatch[2].match(/<xf\b(?:[^>]*?\/>|[^>]*>[\s\S]*?<\/xf>)/g) ?? [];
  if (baseStyles.length !== Number(cellXfsMatch[1])) {
    templateError("A tabela de estilos do template RDO v1 está inconsistente.");
  }
  if (!/<numFmts\b/.test(styles)) {
    styles = styles.replace(
      /(<styleSheet\b[^>]*>)/,
      '$1<numFmts count="3"><numFmt numFmtId="164" formatCode="dd/mm/yy"/><numFmt numFmtId="165" formatCode="hh:mm"/><numFmt numFmtId="166" formatCode="0.##"/></numFmts>',
    );
  } else {
    templateError("O contrato de formatos numéricos do template RDO v1 divergiu.");
  }

  const variants = new Map<string, number>();
  const appended: string[] = [];
  for (const write of mapping.writes) {
    const sheetXml = sheetXmlByName[write.sheet];
    const base = baseStyleId(sheetXml, write.address);
    const variant = cellVariant(write.cell);
    const key = `${base}:${variant}`;
    if (variants.has(key)) continue;
    const baseXml = baseStyles[base];
    if (!baseXml) templateError(`Estilo ${base} ausente no template RDO v1.`);
    variants.set(key, baseStyles.length + appended.length);
    appended.push(styleVariantXml(baseXml, variant));
  }

  styles = styles.replace(
    /<cellXfs\b([^>]*)count="\d+"([^>]*)>([\s\S]*?)<\/cellXfs>/,
    `<cellXfs$1count="${baseStyles.length + appended.length}"$2>$3${appended.join("")}</cellXfs>`,
  );
  setXml(files, path, styles);
  return variants;
}

function replaceCell(
  sheetXml: string,
  address: string,
  innerXml: string,
  type: string | null,
  styleId?: number,
): string {
  const pattern = cellPattern(address);
  const match = pattern.exec(sheetXml);
  if (!match) templateError(`A célula ${address} não existe no template RDO v1.`);
  let attributes = cellAttributes(sheetXml, address)
    .replace(/\/$/, "")
    .replace(/\st="[^"]*"/g, "")
    .trimEnd();
  if (styleId !== undefined) {
    attributes = attributes.replace(/\ss="[^"]*"/g, "");
    attributes += ` s="${styleId}"`;
  }
  if (type) attributes += ` t="${type}"`;
  const replacement = innerXml
    ? `<c${attributes}>${innerXml}</c>`
    : `<c${attributes}/>`;
  return sheetXml.slice(0, match.index) + replacement + sheetXml.slice(match.index + match[0].length);
}

function clearRange(sheetXml: string, rangeAddress: string): string {
  const range = XLSX.utils.decode_range(rangeAddress);
  let result = sheetXml;
  for (let row = range.s.r; row <= range.e.r; row += 1) {
    for (let column = range.s.c; column <= range.e.c; column += 1) {
      const address = XLSX.utils.encode_cell({ r: row, c: column });
      if (cellPattern(address).test(result)) {
        result = replaceCell(result, address, "", null);
      }
    }
  }
  return result;
}

function excelDateSerial(value: string): number {
  const match = /^(\d{4})-(\d{2})-(\d{2})$/.exec(value);
  if (!match) templateError(`Data inválida no snapshot local do RDO: ${value}.`);
  const year = Number(match[1]);
  const month = Number(match[2]);
  const day = Number(match[3]);
  const utc = Date.UTC(year, month - 1, day);
  const date = new Date(utc);
  if (
    date.getUTCFullYear() !== year ||
    date.getUTCMonth() !== month - 1 ||
    date.getUTCDate() !== day
  ) {
    templateError(`Data inválida no snapshot local do RDO: ${value}.`);
  }
  return utc / 86_400_000 + 25_569;
}

function excelTimeSerial(value: string): number {
  const match = /^(\d{2}):(\d{2})(?::(\d{2}))?$/.exec(value);
  if (!match) templateError(`Horário inválido no snapshot local do RDO: ${value}.`);
  const hour = Number(match[1]);
  const minute = Number(match[2]);
  const second = Number(match[3] ?? 0);
  if (hour > 23 || minute > 59 || second > 59) {
    templateError(`Horário inválido no snapshot local do RDO: ${value}.`);
  }
  return (hour * 3600 + minute * 60 + second) / 86_400;
}

function cellInner(cell: RdoWorkbookCellValue): { type: string | null; inner: string } {
  if (cell.kind === "text") {
    const value = escapeXml(sanitizeRdoCellText(cell.value));
    return {
      type: "inlineStr",
      inner: `<is><t xml:space="preserve">${value}</t></is>`,
    };
  }
  const value = cell.kind === "date"
    ? excelDateSerial(cell.value)
    : cell.kind === "time"
      ? excelTimeSerial(cell.value)
      : cell.value;
  if (!Number.isFinite(value)) templateError("Valor numérico inválido no snapshot local do RDO.");
  return { type: null, inner: `<v>${value}</v>` };
}

function addMerge(sheetXml: string, range: string): string {
  if (new RegExp(`<mergeCell\\s+ref="${escapeRegExp(range)}"\\s*/>`).test(sheetXml)) {
    return sheetXml;
  }
  const match = /<mergeCells\b([^>]*)count="(\d+)"([^>]*)>([\s\S]*?)<\/mergeCells>/.exec(sheetXml);
  if (!match) templateError("Regiões mescladas ausentes no template RDO v1.");
  const replacement = `<mergeCells${match[1]}count="${Number(match[2]) + 1}"${match[3]}>${match[4]}<mergeCell ref="${range}"/></mergeCells>`;
  return sheetXml.slice(0, match.index) + replacement + sheetXml.slice(match.index + match[0].length);
}

function removeExecutableSheetContent(sheetXml: string): string {
  let result = sheetXml.replace(
    /<c\b([^>]*)>([\s\S]*?<f\b[\s\S]*?)<\/c>/g,
    (_full, attributes: string) => `<c${attributes.replace(/\st="[^"]*"/g, "")}/>` ,
  );
  result = result.replace(/<hyperlinks\b[^>]*>[\s\S]*?<\/hyperlinks>/g, "");
  return result;
}

function stripPackageMetadata(files: Record<string, Uint8Array>): void {
  for (const path of Object.keys(files)) {
    if (
      path.startsWith("customXml/") ||
      path.startsWith("xl/externalLinks/") ||
      /vbaProject\.bin$/i.test(path) ||
      path === "docProps/custom.xml"
    ) {
      delete files[path];
    }
  }

  const workbookPath = "xl/workbook.xml";
  let workbookXml = xml(files[workbookPath], workbookPath);
  workbookXml = workbookXml.replace(
    /<mc:AlternateContent\b[^>]*>[\s\S]*?<\/mc:AlternateContent>/g,
    "",
  );
  setXml(files, workbookPath, workbookXml);

  for (const path of ["xl/_rels/workbook.xml.rels", "_rels/.rels"]) {
    let relationships = xml(files[path], path);
    relationships = relationships.replace(
      /<Relationship\b(?=[^>]*(?:customXml|externalLink|vbaProject|custom-properties))[^>]*\/>/gi,
      "",
    );
    setXml(files, path, relationships);
  }

  const contentTypesPath = "[Content_Types].xml";
  let contentTypes = xml(files[contentTypesPath], contentTypesPath);
  contentTypes = contentTypes.replace(
    /<Override\b(?=[^>]*PartName="\/(?:customXml|xl\/externalLinks|docProps\/custom\.xml|xl\/vbaProject\.bin))[^>]*\/>/gi,
    "",
  );
  setXml(files, contentTypesPath, contentTypes);

  setXml(
    files,
    "docProps/core.xml",
    '<?xml version="1.0" encoding="UTF-8" standalone="yes"?><cp:coreProperties xmlns:cp="http://schemas.openxmlformats.org/package/2006/metadata/core-properties" xmlns:dc="http://purl.org/dc/elements/1.1/"/>',
  );
  setXml(
    files,
    "docProps/app.xml",
    '<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Properties xmlns="http://schemas.openxmlformats.org/officeDocument/2006/extended-properties"/>',
  );
}

function addFrontPrintArea(files: Record<string, Uint8Array>): void {
  const path = "xl/workbook.xml";
  let workbookXml = xml(files[path], path);
  const definedNames = /<definedNames>([\s\S]*?)<\/definedNames>/.exec(workbookXml);
  if (!definedNames) templateError("Nomes de impressão ausentes no template RDO v1.");
  if (/localSheetId="0"/.test(definedNames[1])) {
    templateError("A área de impressão frontal já existe fora do contrato de entrada.");
  }
  const front = '<definedName name="_xlnm.Print_Area" localSheetId="0">&apos;v.1 RDO frente&apos;!$A$1:$AJ$80</definedName>';
  workbookXml = workbookXml.replace(
    "<definedNames>",
    `<definedNames>${front}`,
  );
  setXml(files, path, workbookXml);
}

function buildWorkbookPackage(
  templateBytes: Uint8Array,
  mapping: RdoWorkbookMapping,
): Uint8Array {
  const files = unzipSync(templateBytes);
  stripPackageMetadata(files);
  addFrontPrintArea(files);
  const sheetPaths = {
    [RDO_FRONT_SHEET]: "xl/worksheets/sheet1.xml",
    [RDO_BACK_SHEET]: "xl/worksheets/sheet2.xml",
  } as const;
  const sheetXmlByName: Record<string, string> = {
    [RDO_FRONT_SHEET]: removeExecutableSheetContent(
      xml(files[sheetPaths[RDO_FRONT_SHEET]], sheetPaths[RDO_FRONT_SHEET]),
    ),
    [RDO_BACK_SHEET]: removeExecutableSheetContent(
      xml(files[sheetPaths[RDO_BACK_SHEET]], sheetPaths[RDO_BACK_SHEET]),
    ),
  };

  for (const sheet of [RDO_FRONT_SHEET, RDO_BACK_SHEET] as const) {
    for (const range of RDO_OPERATIONAL_CLEAR_RANGES[sheet]) {
      sheetXmlByName[sheet] = clearRange(sheetXmlByName[sheet], range);
    }
  }

  const styles = patchStyles(files, sheetXmlByName, mapping);
  for (const write of mapping.writes) {
    const sheetXml = sheetXmlByName[write.sheet];
    const base = baseStyleId(sheetXml, write.address);
    const variant = cellVariant(write.cell);
    const styleId = styles.get(`${base}:${variant}`);
    if (styleId === undefined) templateError("Estilo dinâmico do RDO não foi gerado.");
    const serialized = cellInner(write.cell);
    sheetXmlByName[write.sheet] = replaceCell(
      sheetXml,
      write.address,
      serialized.inner,
      serialized.type,
      styleId,
    );
  }
  for (const sheet of [RDO_FRONT_SHEET, RDO_BACK_SHEET] as const) {
    for (const merge of mapping.merges[sheet]) {
      sheetXmlByName[sheet] = addMerge(sheetXmlByName[sheet], merge);
    }
    setXml(files, sheetPaths[sheet], sheetXmlByName[sheet]);
  }
  return zipSync(files, { level: 6 });
}

function assertSafeOutput(bytes: Uint8Array, mapping: RdoWorkbookMapping): void {
  const workbook = XLSX.read(bytes, {
    type: "array",
    cellStyles: true,
    bookVBA: true,
  });
  if (
    workbook.SheetNames[0] !== RDO_FRONT_SHEET ||
    workbook.SheetNames[1] !== RDO_BACK_SHEET ||
    workbook.SheetNames.length !== 2 ||
    workbook.vbaraw
  ) {
    templateError("A exportação offline alterou o contrato de planilhas do RDO.");
  }
  for (const sheetName of workbook.SheetNames) {
    const sheet = workbook.Sheets[sheetName];
    for (const [address, candidate] of Object.entries(sheet)) {
      if (address.startsWith("!")) continue;
      const cell = candidate as XLSX.CellObject;
      if (cell.f || cell.l) {
        templateError("A exportação offline não permite fórmulas ou links.");
      }
    }
  }
  for (const sheet of [RDO_FRONT_SHEET, RDO_BACK_SHEET] as const) {
    const actual = new Set(
      (workbook.Sheets[sheet]["!merges"] ?? []).map(XLSX.utils.encode_range),
    );
    for (const expected of mapping.merges[sheet]) {
      if (!actual.has(expected)) templateError(`A mesclagem ${expected} não foi preservada.`);
    }
  }
  const files = unzipSync(bytes);
  const forbiddenPath = Object.keys(files).find((path) =>
    path.startsWith("customXml/") ||
    path.startsWith("xl/externalLinks/") ||
    /vbaProject\.bin$/i.test(path),
  );
  if (forbiddenPath) templateError(`Conteúdo executável ou metadado proibido: ${forbiddenPath}.`);
  const packageText = [
    files["xl/workbook.xml"],
    files["docProps/core.xml"],
    files["xl/_rels/workbook.xml.rels"],
  ].map((part) => decoder.decode(part)).join("\n");
  if (/absPath|TargetMode="External"|Hugo Florêncio|<f[ >]/i.test(packageText)) {
    templateError("A exportação offline reteve metadados ou conteúdo externo proibido.");
  }
}

export async function exportRdoWorkbook(
  snapshot: RdoWorkbookSnapshot,
  options: ExportRdoWorkbookOptions = {},
): Promise<Uint8Array> {
  const mapping = mapRdoWorkbook(snapshot);
  const templateBytes = options.templateBytes ?? await loadTemplate();
  if (await sha256(templateBytes) !== RDO_TEMPLATE_SHA256) {
    templateError("O template RDO v1 divergiu do contrato revisado.");
  }
  validateTemplate(templateBytes);
  const output = buildWorkbookPackage(templateBytes, mapping);
  assertSafeOutput(output, mapping);
  return output;
}

export function rdoWorkbookFilename(snapshot: RdoWorkbookSnapshot): string {
  const raw = snapshot.rdo.numeroRdo.trim() || snapshot.rdo.id.trim() || "rdo";
  let candidate = raw
    .normalize("NFKD")
    .replace(/\p{M}+/gu, "")
    .replace(/[^A-Za-z0-9._-]+/g, "-")
    .replace(/-{2,}/g, "-")
    .replace(/^[.\-_]+|[.\-_]+$/g, "");
  if (!candidate) candidate = "rdo";
  candidate = candidate.slice(0, 64);
  return `rdo-${candidate}.xlsx`;
}

export async function downloadRdoWorkbook(
  snapshot: RdoWorkbookSnapshot,
): Promise<void> {
  const bytes = await exportRdoWorkbook(snapshot);
  const blob = new Blob([bytes.slice().buffer], {
    type: RDO_XLSX_MEDIA_TYPE,
  });
  downloadBlob(blob, rdoWorkbookFilename(snapshot));
}

function downloadBlob(blob: Blob, filename: string): void {
  const url = URL.createObjectURL(blob);
  try {
    const anchor = document.createElement("a");
    anchor.href = url;
    anchor.download = filename;
    anchor.rel = "noopener";
    anchor.click();
  } finally {
    URL.revokeObjectURL(url);
  }
}

export async function downloadAuthoritativeRdoWorkbook(
  snapshot: RdoWorkbookSnapshot,
): Promise<void> {
  const response = await apiFetch(
    `/rdos/${encodeURIComponent(snapshot.rdo.id)}/export.xlsx`,
    {
      method: "GET",
      timeoutMs: 30_000,
      connectionErrorMessage:
        "Não foi possível acessar a exportação autorizada do RDO no servidor.",
      timeoutErrorMessage:
        "A exportação autorizada do RDO excedeu o tempo limite.",
    },
  );
  if (!response.ok) {
    throw new Error(
      `O servidor recusou a exportação do RDO (${response.status}).`,
    );
  }
  const mediaType = response.headers
    .get("Content-Type")
    ?.split(";", 1)[0]
    .trim()
    .toLowerCase();
  if (mediaType !== RDO_XLSX_MEDIA_TYPE) {
    throw new Error(
      "O servidor respondeu sem um arquivo XLSX válido; o download foi bloqueado.",
    );
  }
  downloadBlob(await response.blob(), rdoWorkbookFilename(snapshot));
}
