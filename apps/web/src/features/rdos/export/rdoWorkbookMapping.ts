import type {
  LocalRdoRecord,
  ObraLocalRecord,
} from "../../../lib/db/db.types";
import { calcularControleGeometrico } from "../rdoCalculations";
import { localRecordToDraft } from "../localRecordToDraft";
import type {
  ControleGeometricoDraft,
  EquipamentoDraft,
  MaoObraDraft,
  MaterialDraft,
  NumericInput,
  RdoDraft,
  ServicoExecutadoDraft,
} from "../rdo.types";

export const RDO_TEMPLATE_SHA256 =
  "2a97db997d939b738146bad7c39428e38e159a6160f23afdf3297500fb2b8f87";

export const RDO_FRONT_SHEET = "v.1 RDO frente";
export const RDO_BACK_SHEET = "v.1 RDO verso";

export const RDO_WORKBOOK_FIELDS = {
  "header.obra": { sheet: RDO_FRONT_SHEET, anchor: "B6" },
  "header.contract": { sheet: RDO_FRONT_SHEET, anchor: "Q6" },
  "header.rdoNumber": { sheet: RDO_FRONT_SHEET, anchor: "B1" },
  "header.date": { sheet: RDO_FRONT_SHEET, anchor: "AA6" },
  weather: { sheet: RDO_FRONT_SHEET, anchor: "D10" },
  closure: { sheet: RDO_FRONT_SHEET, anchor: "Q10" },
  "workforce.rows": { sheet: RDO_FRONT_SHEET, anchor: "B16" },
  "equipment.rows": { sheet: RDO_FRONT_SHEET, anchor: "B36" },
  "services.rows": { sheet: RDO_FRONT_SHEET, anchor: "B60" },
  "materials.rows": { sheet: RDO_BACK_SHEET, anchor: "B8" },
  "geometricControl.rows": { sheet: RDO_BACK_SHEET, anchor: "B26" },
  observations: { sheet: RDO_BACK_SHEET, anchor: "B63" },
  signatures: { sheet: RDO_BACK_SHEET, anchor: "B69" },
} as const;

export type RdoWorkbookField = keyof typeof RDO_WORKBOOK_FIELDS;

export interface RdoWorkbookSnapshot {
  obra?: Pick<ObraLocalRecord, "id" | "nome" | "codigoContrato">;
  rdo: RdoDraft;
}

export type RdoWorkbookCellValue =
  | { kind: "text"; value: string; presentation?: "default" | "wrapped" }
  | { kind: "number"; value: number }
  | { kind: "date"; value: string }
  | { kind: "time"; value: string };

export interface RdoWorkbookCellWrite {
  field: RdoWorkbookField;
  sheet: typeof RDO_FRONT_SHEET | typeof RDO_BACK_SHEET;
  address: string;
  cell: RdoWorkbookCellValue;
}

export interface RdoWorkbookMapping {
  writes: RdoWorkbookCellWrite[];
  merges: Record<typeof RDO_FRONT_SHEET | typeof RDO_BACK_SHEET, string[]>;
  rowCounts: {
    workforce: number;
    equipment: number;
    services: number;
    materials: number;
    geometricControl: number;
  };
}

export type RdoExportErrorCode =
  | "RDO_EXPORT_MISSING_OBRA"
  | "RDO_EXPORT_MISSING_RDO"
  | "RDO_EXPORT_MISSING_WORKFORCE"
  | "RDO_EXPORT_MISSING_EQUIPMENT"
  | "RDO_EXPORT_MISSING_SERVICES"
  | "RDO_EXPORT_MISSING_MATERIALS"
  | "RDO_EXPORT_MISSING_GEOMETRIC_CONTROL"
  | "RDO_EXPORT_INVALID_WEATHER"
  | "RDO_EXPORT_INVALID_EQUIPMENT_OWNERSHIP"
  | "RDO_EXPORT_INVALID_ROW"
  | "RDO_EXPORT_INVALID_WORKFORCE_ROW"
  | "RDO_EXPORT_INVALID_EQUIPMENT_ROW"
  | "RDO_EXPORT_INVALID_SERVICE_ROW"
  | "RDO_EXPORT_INVALID_MATERIAL_ROW"
  | "RDO_EXPORT_PRINT_OVERFLOW"
  | "RDO_EXPORT_OVERFLOW_WORKFORCE"
  | "RDO_EXPORT_OVERFLOW_EQUIPMENT"
  | "RDO_EXPORT_OVERFLOW_SERVICES"
  | "RDO_EXPORT_OVERFLOW_MATERIALS"
  | "RDO_EXPORT_OVERFLOW_GEOMETRIC_CONTROL"
  | "RDO_EXPORT_TEMPLATE_INVALID";

export class RdoWorkbookExportError extends Error {
  readonly code: RdoExportErrorCode;

  constructor(code: RdoExportErrorCode, message: string) {
    super(message);
    this.name = "RdoWorkbookExportError";
    this.code = code;
  }
}

export interface RdoExportAvailability {
  ready: boolean;
  code: RdoExportErrorCode | null;
  message: string;
}

const MAX_WORKFORCE_GROUPS = 26;
const MAX_EQUIPMENT = 32;
const MAX_WORKED_ROWS = 21;
const MAX_MATERIAL_ROWS = 30;
const MAX_GEOMETRY_ROWS = 36;

function error(code: RdoExportErrorCode, message: string): never {
  throw new RdoWorkbookExportError(code, message);
}

function text(value: unknown): string {
  return typeof value === "string" ? value.trim() : "";
}

function number(value: NumericInput): number | null {
  return typeof value === "number" && Number.isFinite(value) ? value : null;
}

function firstNonBlank(...values: unknown[]): string {
  for (const value of values) {
    const candidate = text(value);
    if (candidate) return candidate;
  }
  return "";
}

function normalize(value: unknown): string {
  return text(value).toLocaleUpperCase("pt-BR");
}

function isSubcontracted(value: string): boolean {
  const normalized = normalize(value);
  return normalized.includes("SUBCONTRAT") || normalized.includes("TERCEIR");
}

function assertEquipmentOwnership(value: string): "OWNED" | "NON_OWNED" {
  switch (normalize(value)) {
    case "PROPRIO":
      return "OWNED";
    case "LOCADO":
    case "TERCEIRIZADO":
      return "NON_OWNED";
    default:
      return error(
        "RDO_EXPORT_INVALID_EQUIPMENT_OWNERSHIP",
        `Vínculo de equipamento não reconhecido: ${value || "em branco"}.`,
      );
  }
}

function isBlankWorkforce(item: MaoObraDraft): boolean {
  return !firstNonBlank(
    item.origemItemId,
    item.sourceRdoId,
    item.colaboradorId,
    item.nomeColaborador,
    item.cargo,
    item.tipoVinculo,
    item.horaInicio,
    item.horaFim,
    item.observacoes,
  ) && number(item.quantidade) === null;
}

function nonEmptyWorkforce(item: MaoObraDraft): boolean {
  return item.selected && !isBlankWorkforce(item);
}

function isBlankEquipment(item: EquipamentoDraft): boolean {
  return !firstNonBlank(
    item.assetId,
    item.prefixo,
    item.descricao,
    item.tipoEquipamento,
    item.tipoVinculo,
    item.horaInicio,
    item.horaFim,
    item.observacoes,
  ) && number(item.quantidade) === null;
}

function nonEmptyEquipment(item: EquipamentoDraft): boolean {
  return !isBlankEquipment(item);
}

function isBlankMaterial(item: MaterialDraft): boolean {
  return !firstNonBlank(
    item.materialNome,
    item.unidade,
    item.notaFiscal,
    item.fornecedor,
    item.observacoes,
  ) && ![
    item.quantidadePrevista,
    item.quantidadeUsinada,
    item.quantidadeAplicada,
    item.quantidadeSobra,
  ].some((value) => number(value) !== null);
}

function nonEmptyMaterial(item: MaterialDraft): boolean {
  return !isBlankMaterial(item);
}

function nonEmptyGeometry(item: ControleGeometricoDraft): boolean {
  return Boolean(
    firstNonBlank(
      item.subtrecho,
      item.numero,
      item.estacaInicial,
      item.estacaFinal,
      item.kmInicial,
      item.kmFinal,
      item.pista,
      item.faixa,
      item.ordemServico,
      item.atividadeObservacoes,
      item.observacoes,
    ) ||
      [
        item.comprimentoM,
        item.larguraM,
        item.espessura1Cm,
        item.espessura2Cm,
        item.espessura3Cm,
        item.densidade,
      ].some((value) => number(value) !== null),
  );
}

function isBlankService(item: ServicoExecutadoDraft): boolean {
  return !firstNonBlank(
    item.servicoNome,
    item.itemContratualId,
    item.unidade,
    item.trechoInicial,
    item.trechoFinal,
    item.localizacao,
    item.turno,
    item.statusValidacao,
    item.observacoes,
  ) && number(item.quantidadeExecutada) === null &&
    !item.retrabalho && !item.producaoRejeitada;
}

function nonEmptyService(item: ServicoExecutadoDraft): boolean {
  return !isBlankService(item);
}

function validateOperationalRows(rdo: RdoDraft): void {
  for (const item of rdo.maoObra) {
    if (!item.selected || isBlankWorkforce(item)) continue;
    if (!firstNonBlank(item.cargo, item.nomeColaborador) || number(item.quantidade) === null) {
      error(
        "RDO_EXPORT_INVALID_WORKFORCE_ROW",
        "Há linha de mão de obra sem cargo/nome ou quantidade; nenhum valor foi inventado.",
      );
    }
  }
  for (const item of rdo.equipamentos) {
    if (isBlankEquipment(item)) continue;
    if (!text(item.descricao) || number(item.quantidade) === null) {
      error(
        "RDO_EXPORT_INVALID_EQUIPMENT_ROW",
        "Há linha de equipamento sem descrição ou quantidade; nenhum valor foi inventado.",
      );
    }
    assertEquipmentOwnership(item.tipoVinculo);
  }
  for (const item of rdo.servicosExecutados) {
    if (isBlankService(item)) continue;
    if (!text(item.servicoNome) || number(item.quantidadeExecutada) === null) {
      error(
        "RDO_EXPORT_INVALID_SERVICE_ROW",
        "Há linha de serviço sem nome ou quantidade; nenhum item foi omitido.",
      );
    }
  }
  for (const item of rdo.materiais) {
    if (isBlankMaterial(item)) continue;
    if (!text(item.materialNome)) {
      error(
        "RDO_EXPORT_INVALID_MATERIAL_ROW",
        "Há linha de material sem descrição; nenhum item foi omitido.",
      );
    }
  }
}

interface WorkforceGroup {
  role: string;
  subcontracted: boolean;
  quantity: number;
}

function groupWorkforce(items: MaoObraDraft[]): WorkforceGroup[] {
  const grouped = new Map<string, WorkforceGroup>();
  for (const item of items.filter(nonEmptyWorkforce)) {
    const role = firstNonBlank(item.cargo, item.nomeColaborador);
    if (!role) {
      error(
        "RDO_EXPORT_INVALID_MATERIAL_ROW",
        "Há mão de obra sem cargo ou nome; nenhum item foi truncado.",
      );
    }
    const subcontracted = isSubcontracted(item.tipoVinculo);
    const key = `${normalize(role)}|${subcontracted}`;
    const previous = grouped.get(key);
    const quantity = number(item.quantidade);
    if (quantity === null) {
      error("RDO_EXPORT_INVALID_WORKFORCE_ROW", "Quantidade da mão de obra ausente.");
    }
    grouped.set(key, {
      role: previous?.role ?? role,
      subcontracted,
      quantity: (previous?.quantity ?? 0) + quantity,
    });
  }
  return [...grouped.values()];
}

interface MaterialRow {
  description: string;
  quantity: number | null;
  unit: string;
  invoice: string;
}

function materialRows(materials: MaterialDraft[]): MaterialRow[] {
  const rows: MaterialRow[] = [];
  for (const material of materials.filter(nonEmptyMaterial)) {
    const name = text(material.materialNome);
    if (!name) {
      error(
        "RDO_EXPORT_INVALID_ROW",
        "Há material sem descrição; nenhum item foi truncado.",
      );
    }
    const before = rows.length;
    const measures = [
      ["U", material.quantidadeUsinada],
      ["A", material.quantidadeAplicada],
      ["S", material.quantidadeSobra],
    ] as const;
    for (const [suffix, rawQuantity] of measures) {
      const quantity = number(rawQuantity);
      if (quantity !== null) {
        rows.push({
          description: `${name} (${suffix})`,
          quantity,
          unit: text(material.unidade),
          invoice: text(material.notaFiscal),
        });
      }
    }
    if (rows.length === before) {
      const quantity = number(material.quantidadePrevista);
      if (quantity !== null) {
        rows.push({
          description: `${name} (P)`,
          quantity,
          unit: text(material.unidade),
          invoice: text(material.notaFiscal),
        });
      }
    }
    if (rows.length === before) {
      rows.push({
        description: name,
        quantity: null,
        unit: text(material.unidade),
        invoice: text(material.notaFiscal),
      });
    }
  }
  return rows;
}

function assertRows(
  count: number,
  capacity: number,
  code: RdoExportErrorCode,
  label: string,
): void {
  if (count > capacity) {
    error(
      code,
      `O template RDO v1 comporta ${capacity} ${label}, mas o RDO possui ${count}; nenhum item foi truncado.`,
    );
  }
}

function assertPrintable(label: string, value: string, limit: number): void {
  if (!value) return;
  if (/\r|\n/.test(value) || [...sanitizeRdoCellText(value)].length > limit) {
    error(
      "RDO_EXPORT_PRINT_OVERFLOW",
      `O conteúdo de ${label} não permanece legível no RDO (limite de ${limit} caracteres em uma linha); nenhum conteúdo foi truncado.`,
    );
  }
}

function assertObservationPrintable(value: string): void {
  const lines = value ? value.split(/\r?\n/) : [];
  if (lines.length > 6 || lines.some((line) => [...line].length > 100)) {
    error(
      "RDO_EXPORT_PRINT_OVERFLOW",
      "O conteúdo de observações gerais não permanece legível no RDO (limite de 6 linhas e 100 caracteres por linha); nenhum conteúdo foi truncado.",
    );
  }
}

function validateSnapshot(snapshot: RdoWorkbookSnapshot): void {
  const { obra, rdo } = snapshot;
  if (
    !obra ||
    !text(obra.id) ||
    obra.id !== rdo?.obraId ||
    !text(obra.nome) ||
    !text(obra.codigoContrato)
  ) {
    error(
      "RDO_EXPORT_MISSING_OBRA",
      "A obra canônica deste RDO não está completa no armazenamento offline.",
    );
  }
  if (!rdo || !text(rdo.id) || !text(rdo.numeroRdo) || !text(rdo.dataRdo)) {
    error(
      "RDO_EXPORT_MISSING_RDO",
      "A identificação local do RDO está incompleta.",
    );
  }
  const requiredArrays: Array<[
    unknown,
    RdoExportErrorCode,
    string,
  ]> = [
    [rdo.maoObra, "RDO_EXPORT_MISSING_WORKFORCE", "mão de obra"],
    [rdo.equipamentos, "RDO_EXPORT_MISSING_EQUIPMENT", "equipamentos"],
    [rdo.servicosExecutados, "RDO_EXPORT_MISSING_SERVICES", "serviços"],
    [rdo.materiais, "RDO_EXPORT_MISSING_MATERIALS", "materiais"],
    [
      rdo.controlesGeometricos,
      "RDO_EXPORT_MISSING_GEOMETRIC_CONTROL",
      "controle geométrico",
    ],
  ];
  for (const [value, code, label] of requiredArrays) {
    if (!Array.isArray(value)) {
      error(code, `O segmento local de ${label} está incompleto.`);
    }
  }
}

function validateWeather(value: string): void {
  if (!value) return;
  if (!["BOM", "NUBLADO", "CHUVA", "IMPOSSIBILITADO", "NAO_APLICAVEL"].includes(value)) {
    error(
      "RDO_EXPORT_INVALID_WEATHER",
      `Condição climática não reconhecida: ${value}.`,
    );
  }
}

function write(
  writes: RdoWorkbookCellWrite[],
  field: RdoWorkbookField,
  sheet: RdoWorkbookCellWrite["sheet"],
  address: string,
  cell: RdoWorkbookCellValue,
): void {
  if ((cell.kind === "text" || cell.kind === "time") && !cell.value) return;
  writes.push({ field, sheet, address, cell });
}

function weekday(date: string): string {
  const match = /^(\d{4})-(\d{2})-(\d{2})$/.exec(date);
  if (!match) return "";
  const value = new Date(Date.UTC(Number(match[1]), Number(match[2]) - 1, Number(match[3])));
  return new Intl.DateTimeFormat("pt-BR", {
    weekday: "long",
    timeZone: "UTC",
  }).format(value);
}

function observations(rdo: RdoDraft): string {
  const entries: string[] = [];
  const workforce = rdo.maoObra.filter(nonEmptyWorkforce);
  if (
    rdo.previousRdoId &&
    workforce.some((item) => text(item.origemItemId))
  ) {
    entries.push(
      `Continuidade da equipe: mão de obra importada do RDO ${sanitizeRdoCellText(rdo.previousRdoId)}`,
    );
  }
  const weather = [
    ["manhã", rdo.condicaoManha],
    ["tarde", rdo.condicaoTarde],
    ["noite", rdo.condicaoNoite],
  ] as const;
  for (const [period, condition] of weather) {
    if (condition === "NUBLADO") entries.push(`Clima ${period}: Nublado`);
  }
  const add = (label: string, value: string) => {
    if (text(value)) {
      entries.push(
        `${sanitizeRdoCellText(label)}: ${sanitizeRdoCellText(value)}`,
      );
    }
  };
  add("RDO", rdo.observacoes);
  for (const item of workforce) add(`Mão de obra ${item.cargo}`, item.observacoes);
  for (const item of rdo.equipamentos.filter(nonEmptyEquipment)) {
    add(`Equipamento ${item.descricao}`, item.observacoes);
  }
  for (const item of rdo.materiais.filter(nonEmptyMaterial)) {
    add(`Material ${item.materialNome}`, item.observacoes);
  }
  for (const item of rdo.controlesGeometricos.filter(nonEmptyGeometry)) {
    add(`Controle ${item.subtrecho}`, item.observacoes);
  }
  for (const item of rdo.servicosExecutados.filter(nonEmptyService)) {
    add(`Serviço ${item.servicoNome}`, item.observacoes);
  }
  return entries.join("\n");
}

function printableValidation(
  snapshot: RdoWorkbookSnapshot,
  workforce: WorkforceGroup[],
  equipment: EquipamentoDraft[],
  materials: MaterialRow[],
  geometry: ControleGeometricoDraft[],
  serviceRows: Array<{
    activity: string;
    start: string;
    end: string;
    itemNumber: string;
    roadway: string;
    lane: string;
    serviceOrder: string;
  }>,
  observationText: string,
): void {
  const { obra, rdo } = snapshot;
  assertPrintable("nome da obra", obra?.nome ?? "", 56);
  assertPrintable("código da obra", obra?.codigoContrato ?? "", 18);
  assertPrintable("número do RDO", firstNonBlank(rdo.numeroRdo, rdo.id), 20);
  assertPrintable("rodovia", rdo.rodovia, 18);
  assertPrintable("dia da semana", weekday(rdo.dataRdo), 16);
  assertPrintable("km inicial programado", rdo.kmInicialProgramado, 12);
  assertPrintable("km final programado", rdo.kmFinalProgramado, 12);
  assertPrintable("km inicial interditado", rdo.kmInicialInterditado, 12);
  assertPrintable("km final interditado", rdo.kmFinalInterditado, 12);
  assertPrintable("nome do apontador", selectedApontadorName(rdo), 40);
  assertPrintable("nome do encarregado", rdo.encarregadoObra, 40);
  assertPrintable("nome da fiscalização", rdo.fiscalizacaoCampo, 40);
  for (const group of workforce) assertPrintable("cargo", group.role, 18);
  for (const item of equipment) {
    assertPrintable("descrição do equipamento", item.descricao, 24);
    assertPrintable("prefixo do equipamento", item.prefixo, 8);
  }
  for (const item of rdo.materiais) {
    assertPrintable("material", item.materialNome, 24);
    assertPrintable("unidade do material", item.unidade, 5);
    assertPrintable("nota fiscal", item.notaFiscal, 24);
  }
  for (const row of materials) {
    assertPrintable("descrição da linha de material", row.description, 28);
  }
  for (const item of geometry) {
    assertPrintable("subtrecho do controle geométrico", item.subtrecho, 32);
  }
  for (const row of serviceRows) {
    assertPrintable("início do trecho", row.start, 20);
    assertPrintable("fim do trecho", row.end, 20);
    assertPrintable("número do trecho", row.itemNumber, 12);
    assertPrintable("pista", row.roadway, 16);
    assertPrintable("faixa", row.lane, 16);
    assertPrintable("ordem de serviço", row.serviceOrder, 30);
    assertPrintable("atividade executada", row.activity, 80);
  }
  assertObservationPrintable(observationText);
}

function selectedApontadorName(rdo: RdoDraft): string {
  if (text(rdo.apontadorRdo)) return text(rdo.apontadorRdo);
  return text(
    rdo.maoObra.find(
      (item) =>
        item.selected &&
        item.colaboradorId === rdo.apontadorColaboradorId,
    )?.nomeColaborador,
  );
}

export function mapRdoWorkbook(snapshot: RdoWorkbookSnapshot): RdoWorkbookMapping {
  validateSnapshot(snapshot);
  const { obra, rdo } = snapshot;
  validateOperationalRows(rdo);
  const workforce = groupWorkforce(rdo.maoObra);
  const equipment = rdo.equipamentos.filter(nonEmptyEquipment);
  const geometry = rdo.controlesGeometricos.filter(nonEmptyGeometry);
  const services = rdo.servicosExecutados.filter(nonEmptyService);
  const materials = materialRows(rdo.materiais);
  const worked = [
    ...geometry.map((item) => {
      const calculation = calcularControleGeometrico(item);
      return {
        start: firstNonBlank(item.estacaInicial, item.kmInicial, item.subtrecho),
        end: firstNonBlank(item.estacaFinal, item.kmFinal),
        itemNumber: text(item.numero),
        length: number(item.comprimentoM),
        width: number(item.larguraM),
        thicknessMeters:
          calculation.espessuraMediaCm === null
            ? null
            : calculation.espessuraMediaCm / 100,
        roadway: text(item.pista),
        lane: text(item.faixa),
        serviceOrder: text(item.ordemServico),
        activity: firstNonBlank(item.atividadeObservacoes, item.observacoes),
      };
    }),
    ...services.map((item) => {
      const quantity = number(item.quantidadeExecutada);
      const quantityText = quantity === null
        ? ""
        : `${quantity}${text(item.unidade) ? ` ${text(item.unidade)}` : ""}`;
      return {
        start: text(item.trechoInicial),
        end: text(item.trechoFinal),
        itemNumber: "",
        length: null,
        width: null,
        thicknessMeters: null,
        roadway: text(item.localizacao),
        lane: "",
        serviceOrder: "",
        activity: [
          text(item.servicoNome),
          quantityText ? `Quantidade: ${quantityText}` : "",
        ].filter(Boolean).join(" | "),
      };
    }),
  ];

  assertRows(workforce.length, MAX_WORKFORCE_GROUPS, "RDO_EXPORT_OVERFLOW_WORKFORCE", "grupos de mão de obra");
  assertRows(equipment.length, MAX_EQUIPMENT, "RDO_EXPORT_OVERFLOW_EQUIPMENT", "equipamentos/veículos");
  assertRows(worked.length, MAX_WORKED_ROWS, "RDO_EXPORT_OVERFLOW_SERVICES", "trechos/serviços");
  assertRows(materials.length, MAX_MATERIAL_ROWS, "RDO_EXPORT_OVERFLOW_MATERIALS", "linhas de materiais");
  assertRows(geometry.length, MAX_GEOMETRY_ROWS, "RDO_EXPORT_OVERFLOW_GEOMETRIC_CONTROL", "controles geométricos");
  validateWeather(rdo.condicaoManha);
  validateWeather(rdo.condicaoTarde);
  validateWeather(rdo.condicaoNoite);
  for (const item of equipment) {
    if (!text(item.descricao)) {
      error("RDO_EXPORT_INVALID_ROW", "Há equipamento/veículo sem descrição; nenhum item foi truncado.");
    }
    assertEquipmentOwnership(item.tipoVinculo);
  }

  const observationText = observations(rdo);
  printableValidation(snapshot, workforce, equipment, materials, geometry, worked, observationText);
  const writes: RdoWorkbookCellWrite[] = [];
  const merges: RdoWorkbookMapping["merges"] = {
    [RDO_FRONT_SHEET]: ["B6:P6", "Q6:U6", "V6:Z6", "AA6:AE6", "AF6:AJ6"],
    [RDO_BACK_SHEET]: ["AB4:AD4", "B63:AD68", "B69:J69", "L69:T69", "V69:AD69"],
  };

  write(writes, "header.rdoNumber", RDO_FRONT_SHEET, "B1", {
    kind: "text",
    value: `RELATÓRIO DIÁRIO DE OBRA (RDO) — ${firstNonBlank(rdo.numeroRdo, rdo.id)}`,
  });
  write(writes, "header.rdoNumber", RDO_BACK_SHEET, "B2", {
    kind: "text",
    value: "RELATÓRIO DIÁRIO DE OBRA (RDO)",
  });
  write(writes, "header.obra", RDO_FRONT_SHEET, "B6", { kind: "text", value: obra?.nome ?? "" });
  write(writes, "header.contract", RDO_FRONT_SHEET, "Q6", { kind: "text", value: obra?.codigoContrato ?? "" });
  write(writes, "header.contract", RDO_FRONT_SHEET, "V6", { kind: "text", value: rdo.rodovia });
  write(writes, "header.date", RDO_FRONT_SHEET, "AA6", { kind: "date", value: rdo.dataRdo });
  write(writes, "header.date", RDO_FRONT_SHEET, "AF6", { kind: "text", value: weekday(rdo.dataRdo) });
  write(writes, "header.date", RDO_BACK_SHEET, "AB4", { kind: "date", value: rdo.dataRdo });

  const weatherRows = [rdo.condicaoManha, rdo.condicaoTarde, rdo.condicaoNoite];
  weatherRows.forEach((condition, index) => {
    const column = condition === "BOM" ? "D" : condition === "CHUVA" ? "G" : condition === "IMPOSSIBILITADO" ? "J" : "";
    if (column) write(writes, "weather", RDO_FRONT_SHEET, `${column}${10 + index}`, { kind: "text", value: "X" });
  });
  const rainfall = number(rdo.pluviometriaMm);
  if (rainfall !== null) write(writes, "weather", RDO_FRONT_SHEET, "M10", { kind: "number", value: rainfall });
  write(writes, "closure", RDO_FRONT_SHEET, "Q10", { kind: "text", value: rdo.kmInicialProgramado });
  write(writes, "closure", RDO_FRONT_SHEET, "Q12", { kind: "text", value: rdo.kmFinalProgramado });
  write(writes, "closure", RDO_FRONT_SHEET, "V10", { kind: "text", value: rdo.kmInicialInterditado });
  write(writes, "closure", RDO_FRONT_SHEET, "V12", { kind: "text", value: rdo.kmFinalInterditado });
  const nighttime = rdo.turno === "NOTURNO";
  write(writes, "closure", RDO_FRONT_SHEET, nighttime ? "AF10" : "AA10", { kind: "time", value: rdo.horaInicio });
  write(writes, "closure", RDO_FRONT_SHEET, nighttime ? "AF12" : "AA12", { kind: "time", value: rdo.horaFim });

  let ownWorkforce = 0;
  let subcontractedWorkforce = 0;
  workforce.forEach((group, index) => {
    const right = index % 2 === 1;
    const row = 16 + Math.floor(index / 2);
    write(writes, "workforce.rows", RDO_FRONT_SHEET, `${right ? "M" : "B"}${row}`, { kind: "text", value: group.role });
    const quantityColumn = group.subcontracted ? (right ? "U" : "J") : (right ? "R" : "G");
    write(writes, "workforce.rows", RDO_FRONT_SHEET, `${quantityColumn}${row}`, { kind: "number", value: group.quantity });
    if (group.subcontracted) subcontractedWorkforce += group.quantity;
    else ownWorkforce += group.quantity;
  });
  write(writes, "workforce.rows", RDO_FRONT_SHEET, "M30", { kind: "number", value: ownWorkforce });
  write(writes, "workforce.rows", RDO_FRONT_SHEET, "M31", { kind: "number", value: subcontractedWorkforce });

  let ownEquipment = 0;
  let nonOwnedEquipment = 0;
  equipment.forEach((item, index) => {
    const right = index % 2 === 1;
    const row = 36 + Math.floor(index / 2);
    const ownership = assertEquipmentOwnership(item.tipoVinculo);
    const quantity = number(item.quantidade);
    if (quantity === null) {
      error("RDO_EXPORT_INVALID_EQUIPMENT_ROW", "Quantidade do equipamento ausente.");
    }
    write(writes, "equipment.rows", RDO_FRONT_SHEET, `${right ? "T" : "B"}${row}`, { kind: "text", value: item.descricao });
    write(writes, "equipment.rows", RDO_FRONT_SHEET, `${right ? "AH" : "O"}${row}`, { kind: "text", value: item.prefixo });
    const quantityColumn = ownership === "NON_OWNED" ? (right ? "AE" : "L") : (right ? "AB" : "I");
    write(writes, "equipment.rows", RDO_FRONT_SHEET, `${quantityColumn}${row}`, { kind: "number", value: quantity });
    if (ownership === "OWNED") ownEquipment += quantity;
    else nonOwnedEquipment += quantity;
  });
  write(writes, "equipment.rows", RDO_FRONT_SHEET, "M53", { kind: "number", value: ownEquipment });
  write(writes, "equipment.rows", RDO_FRONT_SHEET, "M54", { kind: "number", value: nonOwnedEquipment });

  worked.forEach((rowValue, index) => {
    const row = 60 + index;
    merges[RDO_FRONT_SHEET].push(
      `B${row}:D${row}`, `E${row}:G${row}`, `H${row}:I${row}`,
      `J${row}:K${row}`, `L${row}:M${row}`, `N${row}:O${row}`,
      `P${row}:Q${row}`, `R${row}:S${row}`, `T${row}:W${row}`,
      `X${row}:AJ${row}`,
    );
    const values: Array<[string, RdoWorkbookCellValue]> = [
      [`B${row}`, { kind: "text", value: rowValue.start }],
      [`E${row}`, { kind: "text", value: rowValue.end }],
      [`H${row}`, { kind: "text", value: rowValue.itemNumber }],
      [`P${row}`, { kind: "text", value: rowValue.roadway }],
      [`R${row}`, { kind: "text", value: rowValue.lane }],
      [`T${row}`, { kind: "text", value: rowValue.serviceOrder }],
      [`X${row}`, { kind: "text", value: rowValue.activity }],
    ];
    if (rowValue.length !== null) values.push([`J${row}`, { kind: "number", value: rowValue.length }]);
    if (rowValue.width !== null) values.push([`L${row}`, { kind: "number", value: rowValue.width }]);
    if (rowValue.thicknessMeters !== null) values.push([`N${row}`, { kind: "number", value: rowValue.thicknessMeters }]);
    values.forEach(([address, cell]) => write(writes, "services.rows", RDO_FRONT_SHEET, address, cell));
  });

  materials.forEach((item, index) => {
    const block = Math.floor(index / 10);
    const row = 8 + (index % 10);
    const columns = [
      ["B", "D", "E", "F", "G", "H", "J"],
      ["L", "N", "O", "P", "Q", "R", "T"],
      ["V", "X", "Y", "Z", "AA", "AB", "AD"],
    ][block];
    if (!columns) return;
    const [description, descriptionEnd, quantity, quantityEnd, unit, invoice, invoiceEnd] = columns;
    merges[RDO_BACK_SHEET].push(
      `${description}${row}:${descriptionEnd}${row}`,
      `${quantity}${row}:${quantityEnd}${row}`,
      `${invoice}${row}:${invoiceEnd}${row}`,
    );
    write(writes, "materials.rows", RDO_BACK_SHEET, `${description}${row}`, { kind: "text", value: item.description });
    if (item.quantity !== null) write(writes, "materials.rows", RDO_BACK_SHEET, `${quantity}${row}`, { kind: "number", value: item.quantity });
    write(writes, "materials.rows", RDO_BACK_SHEET, `${unit}${row}`, { kind: "text", value: item.unit });
    write(writes, "materials.rows", RDO_BACK_SHEET, `${invoice}${row}`, { kind: "text", value: item.invoice });
  });

  geometry.forEach((item, index) => {
    const row = 26 + index;
    merges[RDO_BACK_SHEET].push(
      `B${row}:E${row}`, `F${row}:H${row}`, `I${row}:K${row}`,
      `L${row}:N${row}`, `O${row}:Q${row}`, `R${row}:T${row}`,
      `U${row}:W${row}`, `X${row}:Z${row}`, `AA${row}:AD${row}`,
    );
    const calculation = calcularControleGeometrico(item);
    write(writes, "geometricControl.rows", RDO_BACK_SHEET, `B${row}`, { kind: "text", value: item.subtrecho });
    const numericValues: Array<[string, number | null]> = [
      [`F${row}`, number(item.comprimentoM)],
      [`I${row}`, number(item.larguraM)],
      [`L${row}`, number(item.espessura1Cm) === null ? null : number(item.espessura1Cm)! / 100],
      [`O${row}`, number(item.espessura2Cm) === null ? null : number(item.espessura2Cm)! / 100],
      [`R${row}`, number(item.espessura3Cm) === null ? null : number(item.espessura3Cm)! / 100],
      [`U${row}`, calculation.espessuraMediaCm === null ? null : calculation.espessuraMediaCm / 100],
      [`X${row}`, calculation.volumeM3],
      [`AA${row}`, calculation.massaTonelada],
    ];
    numericValues.forEach(([address, value]) => {
      if (value !== null) write(writes, "geometricControl.rows", RDO_BACK_SHEET, address, { kind: "number", value });
    });
  });

  write(writes, "observations", RDO_BACK_SHEET, "B63", {
    kind: "text",
    value: observationText,
    presentation: "wrapped",
  });
  write(writes, "signatures", RDO_BACK_SHEET, "B69", { kind: "text", value: selectedApontadorName(rdo) });
  write(writes, "signatures", RDO_BACK_SHEET, "L69", { kind: "text", value: rdo.encarregadoObra });
  write(writes, "signatures", RDO_BACK_SHEET, "V69", { kind: "text", value: rdo.fiscalizacaoCampo });

  return {
    writes,
    merges,
    rowCounts: {
      workforce: workforce.length,
      equipment: equipment.length,
      services: worked.length,
      materials: materials.length,
      geometricControl: geometry.length,
    },
  };
}

export function rdoExportAvailability(
  snapshot: RdoWorkbookSnapshot,
): RdoExportAvailability {
  try {
    mapRdoWorkbook(snapshot);
    return {
      ready: true,
      code: null,
      message: "Disponível offline",
    };
  } catch (caught) {
    if (caught instanceof RdoWorkbookExportError) {
      return {
        ready: false,
        code: caught.code,
        message: caught.message,
      };
    }
    throw caught;
  }
}

export function rdoWorkbookSnapshotFromLocalRecord(
  record: LocalRdoRecord,
  obra?: Pick<ObraLocalRecord, "id" | "nome" | "codigoContrato">,
): RdoWorkbookSnapshot {
  const segments: Array<[
    string,
    RdoExportErrorCode,
    string,
  ]> = [
    ["maoObra", "RDO_EXPORT_MISSING_WORKFORCE", "mão de obra"],
    ["equipamentos", "RDO_EXPORT_MISSING_EQUIPMENT", "equipamentos"],
    ["servicosExecutados", "RDO_EXPORT_MISSING_SERVICES", "serviços"],
    ["materiais", "RDO_EXPORT_MISSING_MATERIALS", "materiais"],
    [
      "controlesGeometricos",
      "RDO_EXPORT_MISSING_GEOMETRIC_CONTROL",
      "controle geométrico",
    ],
  ];
  for (const [key, code, label] of segments) {
    if (!Array.isArray(record.payload[key])) {
      error(
        code,
        `O segmento canônico local de ${label} não foi persistido neste RDO.`,
      );
    }
  }
  return {
    obra,
    rdo: localRecordToDraft(record),
  };
}

export function localRdoExportAvailability(
  record: LocalRdoRecord,
  obra?: Pick<ObraLocalRecord, "id" | "nome" | "codigoContrato">,
): RdoExportAvailability {
  try {
    return rdoExportAvailability(
      rdoWorkbookSnapshotFromLocalRecord(record, obra),
    );
  } catch (caught) {
    if (caught instanceof RdoWorkbookExportError) {
      return {
        ready: false,
        code: caught.code,
        message: caught.message,
      };
    }
    throw caught;
  }
}

const PRIVATE_KEY_BLOCK =
  /-----BEGIN[ \t]+((?:[A-Z0-9]+(?:[ \t]+[A-Z0-9]+){0,7}[ \t]+)?PRIVATE KEY)-----[\s\S]*?-----END[ \t]+\1-----/gi;
const UNBOUNDED_PRIVATE_KEY =
  /-----BEGIN[ \t]+(?:[A-Z0-9]+(?:[ \t]+[A-Z0-9]+){0,7}[ \t]+)?PRIVATE KEY-----[\s\S]*/gi;
const EMAIL = /\b[A-Z0-9._%+-]+@[A-Z0-9.-]+\.[A-Z]{2,}\b/gi;
const CPF = /\b\d{3}\.?\d{3}\.?\d{3}-?\d{2}\b/g;
const PRIVATE_KEY_MARKER = /-{2,}\s*(?:BEGIN|END)(?: [A-Z0-9]+)* PRIVATE KEY\s*-{2,}/gi;
const SECRET_ASSIGNMENT = /\b(?:api[_-]?key|secret|token|password|senha|chave|aws_access_key_id|aws_secret_access_key)\s*[:=]\s*(?:"[^"\r\n]*"|'[^'\r\n]*'|[^\s,;]+)/gi;
const BEARER_TOKEN = /\bBearer\s+[A-Za-z0-9._~+/=-]{8,}/gi;
const AWS_ACCESS_KEY = /\b(?:AKIA|ASIA)[A-Z0-9]{16}\b/g;

function removeDisallowedControls(value: string): string {
  return [...value].filter((character) => {
    const code = character.codePointAt(0) ?? 0;
    return !(
      code <= 8 ||
      code === 11 ||
      code === 12 ||
      (code >= 14 && code <= 31) ||
      code === 127
    );
  }).join("");
}

export function sanitizeRdoCellText(value: string): string {
  let sanitized = removeDisallowedControls(value)
    .replace(PRIVATE_KEY_BLOCK, "[bloco de chave privada removido]")
    .replace(UNBOUNDED_PRIVATE_KEY, "[bloco de chave privada inválido removido]")
    .replace(EMAIL, "[email removido]")
    .replace(CPF, "[CPF removido]")
    .replace(PRIVATE_KEY_MARKER, "[chave privada removida]")
    .replace(BEARER_TOKEN, "Bearer [segredo removido]")
    .replace(AWS_ACCESS_KEY, "[credencial AWS removida]")
    .replace(SECRET_ASSIGNMENT, "[segredo removido]");
  const firstVisible = sanitized.search(/\S/);
  if (firstVisible >= 0 && /^[=+\-@]$/.test(sanitized[firstVisible])) {
    sanitized = `'${sanitized}`;
  }
  return sanitized;
}

export const RDO_OPERATIONAL_CLEAR_RANGES = {
  [RDO_FRONT_SHEET]: [
    "B6:AJ6", "D10:P12", "Q10:AJ12", "B16:AJ28", "M30:AA31",
    "B36:Q51", "T36:AJ51", "M53:AA54", "B60:AJ80",
  ],
  [RDO_BACK_SHEET]: [
    "AB4:AD4", "B8:J17", "L8:T17", "V8:AD17", "B26:AD61", "B63:AD69",
  ],
} as const;
