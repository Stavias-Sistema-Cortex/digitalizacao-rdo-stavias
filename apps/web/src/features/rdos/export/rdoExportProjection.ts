import type { ObraLocalRecord } from "../../../lib/db/db.types";
import { calcularControleGeometrico, medidasDoServico } from "../rdoCalculations";
import type {
  ControleGeometricoDraft,
  EquipamentoDraft,
  MaoObraDraft,
  MaterialDraft,
  NumericInput,
  RdoDraft,
  ServicoExecutadoDraft,
} from "../rdo.types";

export interface RdoWorkbookSnapshot {
  obra?: Pick<ObraLocalRecord, "id" | "nome" | "codigoContrato">;
  rdo: RdoDraft;
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
  | "RDO_EXPORT_UNSUPPORTED_PDF_GLYPH"
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

export interface RdoExportWorkforceGroup {
  role: string;
  subcontracted: boolean;
  quantity: number;
}

export interface RdoExportMaterialRow {
  description: string;
  quantity: number | null;
  unit: string;
  invoice: string;
}

export interface RdoExportWorkedRow {
  activity: string;
  start: string;
  end: string;
  itemNumber: string;
  length: number | null;
  width: number | null;
  thicknessMeters: number | null;
  roadway: string;
  lane: string;
  serviceOrder: string;
}

export interface RdoExportProjection {
  snapshot: RdoWorkbookSnapshot;
  workforce: RdoExportWorkforceGroup[];
  equipment: EquipamentoDraft[];
  worked: RdoExportWorkedRow[];
  materials: RdoExportMaterialRow[];
  geometry: ControleGeometricoDraft[];
  observations: string;
  apontadorName: string;
}

const MAX_WORKFORCE_GROUPS = 26;
const MAX_EQUIPMENT = 32;
const MAX_WORKED_ROWS = 21;
const MAX_MATERIAL_ROWS = 30;
const MAX_GEOMETRY_ROWS = 36;
const UUID_TEXT = /^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$/i;
const PRIVATE_KEY_BLOCK =
  /-----BEGIN[ \t]+((?:[A-Z0-9]+(?:[ \t]+[A-Z0-9]+){0,7}[ \t]+)?PRIVATE KEY)-----[\s\S]*?-----END[ \t]+\1-----/gi;
const UNBOUNDED_PRIVATE_KEY =
  /-----BEGIN[ \t]+(?:[A-Z0-9]+(?:[ \t]+[A-Z0-9]+){0,7}[ \t]+)?PRIVATE KEY-----[\s\S]*/gi;
const EMAIL = /\b[A-Z0-9._%+-]+@[A-Z0-9.-]+\.[A-Z]{2,}\b/gi;
const CPF = /\b\d{3}\.?\d{3}\.?\d{3}-?\d{2}\b/g;
const PRIVATE_KEY_MARKER = /-{2,}\s*(?:BEGIN|END)(?: [A-Z0-9]+)* PRIVATE KEY\s*-{2,}/gi;
const SECRET_ASSIGNMENT = /\b(?:api[_-]?key|secret|token|password|senha|chave|aws_access_key_id|aws_secret_access_key)\s*[:=]\s*(?:"[^"\r\n]*"|'[^'\r\n]*'|[^\s,;]+)/gi;
const BEARER_TOKEN = /\bBearer\s+[A-Za-z0-9._~+/=-]{8,}/gi;
const BASIC_OR_DIGEST_AUTHORIZATION_HEADER = /\b((?:Proxy-)?Authorization[\t \u00A0]*:)[\t \u00A0]*(?:Basic|Digest)\b[^\r\n]*(?:\r?\n[\t ]+[^\r\n]*)*/gi;
const COOKIE_HEADER = /\b((?:Set-)?Cookie[\t \u00A0]*:)[\t \u00A0]*(?:[^\r\n]+(?:\r?\n[\t ]+[^\r\n]*)*|\r?\n[\t ]+[^\r\n]*(?:\r?\n[\t ]+[^\r\n]*)*)/gi;
const AWS_ACCESS_KEY = /\b(?:AKIA|ASIA)[A-Z0-9]{16}\b/g;

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

export function rdoExportEquipmentOwnership(value: string): "OWNED" | "NON_OWNED" {
  switch (normalize(value)) {
    case "PROPRIO": return "OWNED";
    case "LOCADO":
    case "TERCEIRIZADO": return "NON_OWNED";
    default:
      return error("RDO_EXPORT_INVALID_EQUIPMENT_OWNERSHIP", `Vínculo de equipamento não reconhecido: ${value || "em branco"}.`);
  }
}

/*
 * A linha que se identifica sozinha vale um.
 *
 * <p>A escolha em lista dupla põe uma pessoa por linha e uma máquina por
 * linha, e não pede quantidade — nesse modelo o número não foi esquecido, ele
 * é um. Exigir o dígito ali barrava a exportação de todo RDO montado pela
 * tela, que é como todos são montados hoje.
 *
 * <p>A tolerância para na linha que não identifica ninguém: "Rasteleiro" sem
 * nome e sem quantidade não diz quantos rasteleiros foram, e supor um seria
 * inventar. Essa continua exigindo o número.
 */
function resolvedWorkforceQuantity(item: MaoObraDraft): number | null {
  const declared = number(item.quantidade);
  if (declared !== null) return declared;
  return firstNonBlank(item.colaboradorId, item.nomeColaborador) ? 1 : null;
}

/*
 * A descrição também identifica uma máquina. A entrada de terceiro pede
 * prefixo ou descrição — a betoneira do empreiteiro costuma ter nome e não ter
 * placa —, e exigir aqui um dos dois campos que a tela não exige recusava a
 * exportação inteira por um equipamento que a tela deu por completo.
 */
function resolvedEquipmentQuantity(item: EquipamentoDraft): number | null {
  const declared = number(item.quantidade);
  if (declared !== null) return declared;
  return firstNonBlank(item.assetId, item.prefixo, item.descricao) ? 1 : null;
}

/*
 * O parque é o que a obra tem, e o formulário só oferece duas colunas —
 * própria ou de terceiro. Máquina escolhida do parque entra como própria, que
 * é o que o cadastro afirma ao listá-la ali; o campo Vínculo segue aberto para
 * quem precise corrigir. A máquina digitada à mão, sem cadastro atrás, não tem
 * o que a situe: essa continua exigindo o vínculo declarado.
 */
function resolvedEquipmentLinkType(item: EquipamentoDraft): string {
  return text(item.tipoVinculo) || (text(item.assetId) ? "PROPRIO" : "");
}

function resolvedEquipmentRow(item: EquipamentoDraft): EquipamentoDraft {
  return {
    ...item,
    tipoVinculo: resolvedEquipmentLinkType(item),
    quantidade: resolvedEquipmentQuantity(item) ?? item.quantidade,
  };
}

function isBlankWorkforce(item: MaoObraDraft): boolean {
  return !firstNonBlank(item.origemItemId, item.sourceRdoId, item.colaboradorId, item.nomeColaborador, item.cargo, item.tipoVinculo, item.horaInicio, item.horaFim, item.observacoes) && number(item.quantidade) === null;
}

function nonEmptyWorkforce(item: MaoObraDraft): boolean {
  return item.selected && !isBlankWorkforce(item);
}

function isBlankEquipment(item: EquipamentoDraft): boolean {
  return !firstNonBlank(item.assetId, item.prefixo, item.descricao, item.tipoEquipamento, item.tipoVinculo, item.horaInicio, item.horaFim, item.observacoes) && number(item.quantidade) === null;
}

function nonEmptyEquipment(item: EquipamentoDraft): boolean {
  return !isBlankEquipment(item);
}

function isBlankMaterial(item: MaterialDraft): boolean {
  return !firstNonBlank(item.materialNome, item.unidade, item.notaFiscal, item.fornecedor, item.observacoes) && ![item.quantidadePrevista, item.quantidadeUsinada, item.quantidadeAplicada, item.quantidadeSobra].some((value) => number(value) !== null);
}

function nonEmptyMaterial(item: MaterialDraft): boolean {
  return !isBlankMaterial(item);
}

function nonEmptyGeometry(item: ControleGeometricoDraft): boolean {
  return Boolean(firstNonBlank(item.subtrecho, item.numero, item.estacaInicial, item.estacaFinal, item.kmInicial, item.kmFinal, item.pista, item.faixa, item.ordemServico, item.atividadeObservacoes, item.observacoes) || [item.comprimentoM, item.larguraM, item.espessura1Cm, item.espessura2Cm, item.espessura3Cm, item.densidade].some((value) => number(value) !== null));
}

function isBlankService(item: ServicoExecutadoDraft): boolean {
  return !firstNonBlank(item.servicoNome, item.itemContratualId, item.unidade, item.trechoInicial, item.trechoFinal, item.localizacao, item.turno, item.statusValidacao, item.observacoes) && number(item.quantidadeExecutada) === null && !item.retrabalho && !item.producaoRejeitada;
}

function nonEmptyService(item: ServicoExecutadoDraft): boolean {
  return !isBlankService(item);
}

function validateOperationalRows(rdo: RdoDraft): void {
  for (const item of rdo.maoObra) {
    if (!item.selected || isBlankWorkforce(item)) continue;
    if (!firstNonBlank(item.cargo, item.nomeColaborador) || resolvedWorkforceQuantity(item) === null) error("RDO_EXPORT_INVALID_WORKFORCE_ROW", "Há linha de mão de obra sem cargo/nome ou quantidade; nenhum valor foi inventado.");
  }
  for (const item of rdo.equipamentos) {
    if (isBlankEquipment(item)) continue;
    if (!text(item.descricao) || resolvedEquipmentQuantity(item) === null) error("RDO_EXPORT_INVALID_EQUIPMENT_ROW", "Há linha de equipamento sem descrição ou quantidade; nenhum valor foi inventado.");
    rdoExportEquipmentOwnership(resolvedEquipmentLinkType(item));
  }
  for (const item of rdo.servicosExecutados) {
    if (isBlankService(item)) continue;
    // A quantidade deixou de ser digitada: ela é o que o trecho, a largura e a
    // espessura afirmam, na dimensão que a unidade do serviço nomeia. Serviço
    // medido em tonelada ou em hora não tem medida a derivar do trecho, e
    // exigi-la aqui recusaria a exportação do dia inteiro por uma linha que o
    // formulário deu por completa. A linha sai impressa sem o número — que é a
    // verdade sobre ela — em vez de derrubar o relatório.
    if (!text(item.servicoNome)) error("RDO_EXPORT_INVALID_SERVICE_ROW", "Há linha de serviço sem nome; nenhum item foi omitido.");
  }
  for (const item of rdo.materiais) {
    if (isBlankMaterial(item)) continue;
    if (!text(item.materialNome)) error("RDO_EXPORT_INVALID_MATERIAL_ROW", "Há linha de material sem descrição; nenhum item foi omitido.");
  }
}

function groupWorkforce(items: MaoObraDraft[]): RdoExportWorkforceGroup[] {
  const grouped = new Map<string, RdoExportWorkforceGroup>();
  for (const item of items.filter(nonEmptyWorkforce)) {
    const role = firstNonBlank(item.cargo, item.nomeColaborador);
    if (!role) error("RDO_EXPORT_INVALID_MATERIAL_ROW", "Há mão de obra sem cargo ou nome; nenhum item foi truncado.");
    const subcontracted = isSubcontracted(item.tipoVinculo);
    const key = `${normalize(role)}|${subcontracted}`;
    const previous = grouped.get(key);
    const quantity = resolvedWorkforceQuantity(item);
    if (quantity === null) error("RDO_EXPORT_INVALID_WORKFORCE_ROW", "Quantidade da mão de obra ausente.");
    grouped.set(key, { role: previous?.role ?? role, subcontracted, quantity: (previous?.quantity ?? 0) + quantity });
  }
  return [...grouped.values()];
}

function materialRows(materials: MaterialDraft[]): RdoExportMaterialRow[] {
  const rows: RdoExportMaterialRow[] = [];
  for (const material of materials.filter(nonEmptyMaterial)) {
    const name = text(material.materialNome);
    if (!name) error("RDO_EXPORT_INVALID_ROW", "Há material sem descrição; nenhum item foi truncado.");
    const before = rows.length;
    for (const [suffix, rawQuantity] of [["U", material.quantidadeUsinada], ["A", material.quantidadeAplicada], ["S", material.quantidadeSobra]] as const) {
      const quantity = number(rawQuantity);
      if (quantity !== null) rows.push({ description: `${name} (${suffix})`, quantity, unit: text(material.unidade), invoice: text(material.notaFiscal) });
    }
    if (rows.length === before) {
      const quantity = number(material.quantidadePrevista);
      if (quantity !== null) rows.push({ description: `${name} (P)`, quantity, unit: text(material.unidade), invoice: text(material.notaFiscal) });
    }
    if (rows.length === before) rows.push({ description: name, quantity: null, unit: text(material.unidade), invoice: text(material.notaFiscal) });
  }
  return rows;
}

function assertRows(count: number, capacity: number, code: RdoExportErrorCode, label: string): void {
  if (count > capacity) error(code, `O template RDO v1 comporta ${capacity} ${label}, mas o RDO possui ${count}; nenhum item foi truncado.`);
}

function assertPrintable(label: string, value: string, limit: number): void {
  if (!value) return;
  if (/\r|\n/.test(value) || [...sanitizeRdoCellText(value)].length > limit) error("RDO_EXPORT_PRINT_OVERFLOW", `O conteúdo de ${label} não permanece legível no RDO (limite de ${limit} caracteres em uma linha); nenhum conteúdo foi truncado.`);
}

// Quantas linhas desenhadas uma linha digitada ocupa: quebra por palavra, e
// corta seco só quando a palavra sozinha não cabe — a mesma regra que o
// renderizador do PDF aplica em wrapObservation.
function wrappedLineCount(line: string, width: number): number {
  let remaining = [...line];
  let count = 0;
  for (;;) {
    count += 1;
    if (remaining.length <= width) return count;
    let end = width;
    const lastSpace = remaining.lastIndexOf(" ", end);
    if (lastSpace > 0) end = lastSpace;
    remaining = remaining.slice(end);
    while (remaining[0] === " ") remaining = remaining.slice(1);
  }
}

// A caixa de observações é a célula mesclada B63:AD68 — colunas B..AD somando
// 246 unidades de largura, linhas 63..68 somando 230 pontos, em Myriad Pro 20 —
// e sai com quebra automática, igual à área do PDF, que é maior ainda. Nenhuma
// das duas pede que o apontador aperte Enter a cada tantos caracteres, então
// medir a linha digitada em vez da desenhada recusava o parágrafo que cabe.
// Oito por cento e vinte fica abaixo do medido de propósito: Myriad Pro costuma
// faltar em quem abre a planilha, e a substituta pode vir mais larga.
const OBSERVATION_LINES = 8;
const OBSERVATION_LINE_WIDTH = 120;

function observationLineCount(value: string): number {
  if (!value) return 0;
  let used = 0;
  for (const line of value.split(/\r?\n/)) used += wrappedLineCount(line, OBSERVATION_LINE_WIDTH);
  return used;
}

function assertObservationPrintable(value: string): void {
  if (observationLineCount(value) > OBSERVATION_LINES) error("RDO_EXPORT_PRINT_OVERFLOW", `O conteúdo de observações gerais não permanece legível no RDO (limite de ${OBSERVATION_LINES} linhas de ${OBSERVATION_LINE_WIDTH} caracteres); nenhum conteúdo foi truncado.`);
}

export interface RdoObservationBudget {
  readonly used: number;
  readonly capacity: number;
  readonly fits: boolean;
}

/**
 * Quanto da caixa de observações o RDO já ocupa, para a tela poder dizer antes.
 *
 * Mede o mesmo agregado que vai para o arquivo — as linhas automáticas de
 * continuidade, praticabilidade e clima, mais as observações de cada item — com
 * a mesma quebra que a exportação aplica. É de propósito a mesma função: um
 * contador próprio acabaria discordando do servidor, que foi justamente o que
 * fazia a tela prometer "XLSX e PDF disponíveis" e a exportação recusar em
 * seguida.
 */
export function rdoObservationBudget(rdo: RdoDraft): RdoObservationBudget {
  const used = observationLineCount(observations(rdo));
  return { used, capacity: OBSERVATION_LINES, fits: used <= OBSERVATION_LINES };
}

function validateSnapshot(snapshot: RdoWorkbookSnapshot): void {
  const { obra, rdo } = snapshot;
  if (!obra || !text(obra.id) || obra.id !== rdo?.obraId || !text(obra.nome) || !text(obra.codigoContrato)) error("RDO_EXPORT_MISSING_OBRA", "A obra canônica deste RDO não está completa no armazenamento offline.");
  if (!rdo || !text(rdo.id) || !text(rdo.numeroRdo) || !text(rdo.dataRdo)) error("RDO_EXPORT_MISSING_RDO", "A identificação local do RDO está incompleta.");
  const requiredArrays: Array<[unknown, RdoExportErrorCode, string]> = [
    [rdo.maoObra, "RDO_EXPORT_MISSING_WORKFORCE", "mão de obra"],
    [rdo.equipamentos, "RDO_EXPORT_MISSING_EQUIPMENT", "equipamentos"],
    [rdo.servicosExecutados, "RDO_EXPORT_MISSING_SERVICES", "serviços"],
    [rdo.materiais, "RDO_EXPORT_MISSING_MATERIALS", "materiais"],
    [rdo.controlesGeometricos, "RDO_EXPORT_MISSING_GEOMETRIC_CONTROL", "controle geométrico"],
  ];
  for (const [value, code, label] of requiredArrays) {
    if (!Array.isArray(value)) error(code, `O segmento local de ${label} está incompleto.`);
  }
}

function validateWeather(value: string): void {
  if (value && !["BOM", "NUBLADO", "CHUVA", "IMPOSSIBILITADO", "NAO_APLICAVEL"].includes(value)) error("RDO_EXPORT_INVALID_WEATHER", `Condição climática não reconhecida: ${value}.`);
}

function weekday(date: string): string {
  const match = /^(\d{4})-(\d{2})-(\d{2})$/.exec(date);
  if (!match) return "";
  return new Intl.DateTimeFormat("pt-BR", { weekday: "long", timeZone: "UTC" }).format(new Date(Date.UTC(Number(match[1]), Number(match[2]) - 1, Number(match[3]))));
}

function selectedApontadorName(rdo: RdoDraft): string {
  if (text(rdo.apontadorRdo)) return text(rdo.apontadorRdo);
  return text(rdo.maoObra.find((item) => item.selected && item.colaboradorId === rdo.apontadorColaboradorId)?.nomeColaborador);
}

function observations(rdo: RdoDraft): string {
  const entries: string[] = [];
  const workforce = rdo.maoObra.filter(nonEmptyWorkforce);
  const candidate = text(rdo.previousRdoNumber);
  const previousRdoNumber = candidate === text(rdo.previousRdoId) || UUID_TEXT.test(candidate) ? "" : candidate;
  if (rdo.previousRdoId && previousRdoNumber && workforce.some((item) => text(item.origemItemId))) entries.push(`Continuidade da equipe: mão de obra importada do RDO ${sanitizeRdoCellText(previousRdoNumber)}`);
  // A praticabilidade encabeça as observações porque responde a pergunta que o
  // relatório existe para responder — deu ou não deu para trabalhar. Vai aqui,
  // e não numa célula própria, porque o template é validado por SHA-256 e
  // inventar campo exigiria versão nova do arquivo. Ausência não vira
  // "praticável": quem não declarou fica sem a linha, o que é diferente de
  // afirmar que deu. Espelha addPracticabilityObservation do servidor.
  if (rdo.condicaoTrabalho === "PRATICAVEL") entries.push("Condição do dia: praticável");
  if (rdo.condicaoTrabalho === "IMPRATICAVEL") entries.push("Condição do dia: impraticável");
  for (const [period, condition] of [["manhã", rdo.condicaoManha], ["tarde", rdo.condicaoTarde], ["noite", rdo.condicaoNoite]] as const) if (condition === "NUBLADO") entries.push(`Clima ${period}: Nublado`);
  const add = (label: string, value: string) => { if (text(value)) entries.push(`${sanitizeRdoCellText(label)}: ${sanitizeRdoCellText(value)}`); };
  add("RDO", rdo.observacoes);
  for (const item of workforce) add(`Mão de obra ${item.cargo}`, item.observacoes);
  for (const item of rdo.equipamentos.filter(nonEmptyEquipment)) add(`Equipamento ${item.descricao}`, item.observacoes);
  for (const item of rdo.materiais.filter(nonEmptyMaterial)) add(`Material ${item.materialNome}`, item.observacoes);
  for (const item of rdo.controlesGeometricos.filter(nonEmptyGeometry)) add(`Controle ${item.subtrecho}`, item.observacoes);
  for (const item of rdo.servicosExecutados.filter(nonEmptyService)) add(`Serviço ${item.servicoNome}`, item.observacoes);
  return entries.join("\n");
}

function printableValidation(projection: RdoExportProjection): void {
  const { obra, rdo } = projection.snapshot;
  assertPrintable("nome da obra", obra?.nome ?? "", 56);
  assertPrintable("código da obra", obra?.codigoContrato ?? "", 18);
  assertPrintable("número do RDO", firstNonBlank(rdo.numeroRdo, rdo.id), 20);
  assertPrintable("rodovia", rdo.rodovia, 18);
  assertPrintable("dia da semana", weekday(rdo.dataRdo), 16);
  assertPrintable("km inicial programado", rdo.kmInicialProgramado, 12);
  assertPrintable("km final programado", rdo.kmFinalProgramado, 12);
  assertPrintable("km inicial interditado", rdo.kmInicialInterditado, 12);
  assertPrintable("km final interditado", rdo.kmFinalInterditado, 12);
  assertPrintable("nome do apontador", projection.apontadorName, 40);
  assertPrintable("nome do encarregado", rdo.encarregadoObra, 40);
  assertPrintable("nome da fiscalização", rdo.fiscalizacaoCampo, 40);
  /*
   * 40 é a célula do cargo medida, não o chute de 18 que recusava cargos
   * reais do Academy ("OPERADOR DE ROLO COMPACTADOR" tem 28). Os dois PDFs
   * encaixam quarenta "W" acima do piso fail-closed de 4pt, e o XLSX escreve
   * o cargo em fonte 16 com quebra — duas linhas de ~30 na altura que a
   * linha do template já tem. Espelha WORKFORCE_ROLE_LIMIT do servidor, com
   * a mesma mensagem: contador divergente é o que fazia a tela prometer e o
   * servidor recusar.
   */
  for (const group of projection.workforce) assertPrintable("cargo", group.role, 40);
  for (const item of projection.equipment) { assertPrintable("descrição do equipamento", item.descricao, 24); assertPrintable("prefixo do equipamento", item.prefixo, 8); }
  /*
   * 10 é a unidade medida depois de a coluna caber nela. O 5 recusava unidade
   * por extenso e recusava até o que caberia — "LITROS" ocupa 3,61 em, folgado
   * na coluna antiga —, enquanto cinco "W" (4,72 em) já não cabiam nos 3,97 em
   * que os 7 mm davam aqui. O aperto era a largura, não a contagem: a coluna
   * subiu para 10 mm (e 30 pontos no servidor), e o mais apertado dos dois
   * passa a oferecer 6,09 em, onde "TONELADA" (5,39) e "UNIDADE" (4,50) cabem.
   * Fica acima do pior caso em "W" de propósito, como a nota fiscal já fica:
   * unidade real é estreita, e apertar para 6 recusaria "TONELADA", que cabe.
   * Espelha MATERIAL_UNIT_LIMIT do servidor, com a mesma mensagem.
   */
  for (const item of rdo.materiais) { assertPrintable("material", item.materialNome, 24); assertPrintable("unidade do material", item.unidade, 10); assertPrintable("nota fiscal", item.notaFiscal, 24); }
  for (const row of projection.materials) assertPrintable("descrição da linha de material", row.description, 28);
  for (const item of projection.geometry) assertPrintable("subtrecho do controle geométrico", item.subtrecho, 32);
  for (const row of projection.worked) { assertPrintable("início do trecho", row.start, 20); assertPrintable("fim do trecho", row.end, 20); assertPrintable("número do trecho", row.itemNumber, 12); assertPrintable("pista", row.roadway, 16); assertPrintable("faixa", row.lane, 16); assertPrintable("ordem de serviço", row.serviceOrder, 30); assertPrintable("atividade executada", row.activity, 80); }
  assertObservationPrintable(projection.observations);
}

export function buildRdoExportProjection(snapshot: RdoWorkbookSnapshot): RdoExportProjection {
  validateSnapshot(snapshot);
  const { rdo } = snapshot;
  validateOperationalRows(rdo);
  const workforce = groupWorkforce(rdo.maoObra);
  const equipment = rdo.equipamentos.filter(nonEmptyEquipment).map(resolvedEquipmentRow);
  const geometry = rdo.controlesGeometricos.filter(nonEmptyGeometry);
  const services = rdo.servicosExecutados.filter(nonEmptyService);
  const materials = materialRows(rdo.materiais);
  const worked = [...geometry.map((item) => {
    const calculation = calcularControleGeometrico(item);
    return { start: firstNonBlank(item.estacaInicial, item.kmInicial, item.subtrecho), end: firstNonBlank(item.estacaFinal, item.kmFinal), itemNumber: text(item.numero), length: number(item.comprimentoM), width: number(item.larguraM), thicknessMeters: calculation.espessuraMediaCm === null ? null : calculation.espessuraMediaCm / 100, roadway: text(item.pista), lane: text(item.faixa), serviceOrder: text(item.ordemServico), activity: firstNonBlank(item.atividadeObservacoes, item.observacoes) };
  }), ...services.map((item) => {
    const quantity = number(item.quantidadeExecutada);
    const quantityText = quantity === null ? "" : `${quantity}${text(item.unidade) ? ` ${text(item.unidade)}` : ""}`;
    // As colunas LARG. e Espessura já existiam no template e nos dois PDFs;
    // o que faltava era o valor chegar. Comprimento é conta, não campo — o
    // trecho já diz a extensão, e guardá-la ao lado das parcelas criaria duas
    // versões da mesma verdade. Espessura vai em metros, que é o que a
    // planilha e o PDF esperam e agora também o que se captura.
    const medidas = medidasDoServico(item);
    return { start: text(item.trechoInicial), end: text(item.trechoFinal), itemNumber: "", length: medidas.comprimentoM, width: number(item.larguraM), thicknessMeters: number(item.espessuraM), roadway: firstNonBlank(item.pista, item.localizacao), lane: text(item.faixa), serviceOrder: "", activity: [text(item.servicoNome), quantityText ? `Quantidade: ${quantityText}` : ""].filter(Boolean).join(" | ") };
  })];
  assertRows(workforce.length, MAX_WORKFORCE_GROUPS, "RDO_EXPORT_OVERFLOW_WORKFORCE", "grupos de mão de obra");
  assertRows(equipment.length, MAX_EQUIPMENT, "RDO_EXPORT_OVERFLOW_EQUIPMENT", "equipamentos/veículos");
  assertRows(worked.length, MAX_WORKED_ROWS, "RDO_EXPORT_OVERFLOW_SERVICES", "trechos/serviços");
  assertRows(materials.length, MAX_MATERIAL_ROWS, "RDO_EXPORT_OVERFLOW_MATERIALS", "linhas de materiais");
  assertRows(geometry.length, MAX_GEOMETRY_ROWS, "RDO_EXPORT_OVERFLOW_GEOMETRIC_CONTROL", "controles geométricos");
  validateWeather(rdo.condicaoManha);
  validateWeather(rdo.condicaoTarde);
  validateWeather(rdo.condicaoNoite);
  for (const item of equipment) { if (!text(item.descricao)) error("RDO_EXPORT_INVALID_ROW", "Há equipamento/veículo sem descrição; nenhum item foi truncado."); rdoExportEquipmentOwnership(item.tipoVinculo); }
  const projection = { snapshot, workforce, equipment, worked, materials, geometry, observations: observations(rdo), apontadorName: selectedApontadorName(rdo) };
  printableValidation(projection);
  return projection;
}

function removeDisallowedControls(value: string): string {
  return [...value].filter((character) => { const code = character.codePointAt(0) ?? 0; return !(code <= 8 || code === 11 || code === 12 || (code >= 14 && code <= 31) || code === 127); }).join("");
}

export function sanitizeRdoCellText(value: string): string {
  let sanitized = removeDisallowedControls(value).replace(PRIVATE_KEY_BLOCK, "[bloco de chave privada removido]").replace(UNBOUNDED_PRIVATE_KEY, "[bloco de chave privada inválido removido]").replace(EMAIL, "[email removido]").replace(CPF, "[CPF removido]").replace(PRIVATE_KEY_MARKER, "[chave privada removida]").replace(BASIC_OR_DIGEST_AUTHORIZATION_HEADER, "$1 [segredo removido]").replace(COOKIE_HEADER, "$1 [segredo removido]").replace(BEARER_TOKEN, "Bearer [segredo removido]").replace(AWS_ACCESS_KEY, "[credencial AWS removida]").replace(SECRET_ASSIGNMENT, "[segredo removido]");
  const firstVisible = sanitized.search(/\S/);
  if (firstVisible >= 0 && /^[=+\-@]$/.test(sanitized[firstVisible])) sanitized = `'${sanitized}`;
  return sanitized;
}
