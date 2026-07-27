import type { LocalRdoRecord, ObraLocalRecord } from "../../../lib/db/db.types";
import { calcularControleGeometrico } from "../rdoCalculations";
import { localRecordToDraft } from "../localRecordToDraft";
import {
  buildRdoExportProjection,
  RdoWorkbookExportError,
  rdoExportEquipmentOwnership,
  type RdoExportErrorCode,
  type RdoWorkbookSnapshot,
} from "./rdoExportProjection";

export {
  RdoWorkbookExportError,
  sanitizeRdoCellText,
  type RdoExportErrorCode,
  type RdoWorkbookSnapshot,
} from "./rdoExportProjection";

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

export interface RdoExportAvailability {
  ready: boolean;
  code: RdoExportErrorCode | null;
  message: string;
}

function error(code: RdoExportErrorCode, message: string): never {
  throw new RdoWorkbookExportError(code, message);
}

function text(value: unknown): string {
  return typeof value === "string" ? value.trim() : "";
}

function number(value: unknown): number | null {
  return typeof value === "number" && Number.isFinite(value) ? value : null;
}

function firstNonBlank(...values: unknown[]): string {
  for (const value of values) {
    const candidate = text(value);
    if (candidate) return candidate;
  }
  return "";
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

export function mapRdoWorkbook(snapshot: RdoWorkbookSnapshot): RdoWorkbookMapping {
  const projection = buildRdoExportProjection(snapshot);
  const { obra, rdo } = projection.snapshot;
  const {
    workforce,
    equipment,
    geometry,
    materials,
    worked,
    observations: observationText,
    apontadorName,
  } = projection;
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
    const ownership = rdoExportEquipmentOwnership(item.tipoVinculo);
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
  write(writes, "signatures", RDO_BACK_SHEET, "B69", { kind: "text", value: apontadorName });
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


export const RDO_OPERATIONAL_CLEAR_RANGES = {
  [RDO_FRONT_SHEET]: [
    "B6:AJ6", "D10:P12", "Q10:AJ12", "B16:AJ28", "M30:AA31",
    "B36:Q51", "T36:AJ51", "M53:AA54", "B60:AJ80",
  ],
  [RDO_BACK_SHEET]: [
    "AB4:AD4", "B8:J17", "L8:T17", "V8:AD17", "B26:AD61", "B63:AD69",
  ],
} as const;
