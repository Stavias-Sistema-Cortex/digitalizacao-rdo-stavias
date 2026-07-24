import rdoOntologyFallback from "./rdoOntology.json";
import type {
  StaviaConsultaResponse,
  StaviaSnapshot,
  StaviaSnapshotOperationalEvent,
  StaviaSnapshotRdo,
} from "./stavia.types";

/**
 * Caminho genérico do motor local: a mesma ontologia declarativa usada pelo
 * backend decide entidade, atributos, operação e identidade, e a resposta é
 * calculada sobre as coleções que o snapshot offline já contém. Os textos
 * espelham os renderizadores do DeterministicStaviaResponseGenerator para que
 * online e offline respondam igual.
 */

export interface RdoOntologyAttributeJson {
  name: string;
  column: string;
  label: string;
  dataType: string;
  unit: string | null;
  unitColumn: string | null;
  aliases: string[];
  aggregable: boolean;
  identity: boolean;
}

export interface RdoOntologyEntityJson {
  name: string;
  table: string;
  evidenceType: string;
  source: string;
  header: boolean;
  snapshotCollection: string | null;
  countAttribute: string | null;
  filter: string | null;
  scope?: string | null;
  aliases: string[];
  attributes: RdoOntologyAttributeJson[];
}

export interface RdoOntologyJson {
  version: string;
  entities: RdoOntologyEntityJson[];
}

const LIST_WORDS = [
  "quais",
  "dados",
  "campos",
  "detalhes",
  "detalhe",
  "informacoes",
  "informações",
  "mostre",
  "mostrar",
  "lista",
  "listar",
  "liste",
  "registrados",
  "usados",
  "utilizados",
  "trabalhou",
  "trabalharam",
];

const FULL_RECORD_WORDS = [
  "dados",
  "campos",
  "detalhes",
  "detalhe",
  "informacoes",
  "informações",
  "tudo",
  "todo",
  "toda",
  "todos",
  "todas",
  "completo",
  "completa",
];

const AGGREGATE_WORDS = [
  "quanto",
  "quanta",
  "quantos",
  "quantas",
  "total",
  "soma",
  "somando",
  "media",
];

const RANKING_MAX_PHRASES = ["qual rdo teve mais", "qual dia teve mais"];
const RANKING_MIN_PHRASES = ["qual rdo teve menos", "qual dia teve menos"];

const COMPARE_MARKERS = [
  " vs ",
  "versus",
  "comparar",
  "comparacao",
  "diferenca entre",
];

const ORDINAL_WORDS: Record<string, number> = {
  primeiro: 1,
  primeira: 1,
  segundo: 2,
  segunda: 2,
  terceiro: 3,
  terceira: 3,
  quarto: 4,
  quarta: 4,
  quinto: 5,
  quinta: 5,
  sexto: 6,
  sexta: 6,
  setimo: 7,
  setima: 7,
  oitavo: 8,
  oitava: 8,
  nono: 9,
  nona: 9,
  decimo: 10,
  decima: 10,
};

const IDENTITY_STOPWORDS = new Set([
  "qual", "quais", "quanto", "quanta", "quantos", "quantas", "quando",
  "quem", "onde", "como", "foi", "foram", "e", "o", "a", "os", "as",
  "de", "da", "do", "das", "dos", "no", "na", "nos", "nas", "em",
  "um", "uma", "este", "esta", "esse", "essa", "neste", "nesta",
  "nesse", "nessa", "ultimo", "ultima", "rdo", "obra", "hoje",
  "ontem", "semana", "passada", "mais", "menos", "recente", "total",
  "soma", "media", "teve", "tem", "houve", "registrado",
  "registrados", "registrada", "registradas", "lista", "listar",
  "liste", "dados", "campos", "detalhes", "detalhe", "informacoes",
  "informações", "mostre", "mostrar", "tudo", "todo", "toda",
  "todos", "todas", "completo", "completa", "usados",
  "utilizados", "trabalhou", "trabalharam",
  "comparar", "comparacao", "vs", "versus", "diferenca", "entre",
  "tonelada", "toneladas", "t", "kg", "quilos", "litro", "litros",
  "metro", "metros", "mm", "cm", "m2", "m3", "executado",
  "executada", "executados", "executadas",
]);

const VALIDATED_STATUSES = new Set([
  "VALIDADO",
  "VALIDADA",
  "APROVADO",
  "APROVADA",
]);

const NOT_VALIDATED_CAVEAT =
  " Esse RDO ainda não consta como validado pela sala técnica; use a "
  + "informação como operacional, não como validação final.";

const MEMORY_ONLY_ATTRIBUTE_NAMES = new Set([
  "dataImportacao",
  "usuarioImportacao",
  "criadoEm",
  "enviadoEm",
  "aprovadoEm",
  "versaoLinha",
  "updatedAt",
  "createdAt",
  "removedAt",
]);

interface AttributeMatch {
  entity: RdoOntologyEntityJson;
  attribute: RdoOntologyAttributeJson;
  alias: string;
}

interface OntologyRow {
  rdo: StaviaSnapshotRdo;
  values: Record<string, unknown>;
  index: number;
}

export function loadRdoOntology(snapshot: StaviaSnapshot): RdoOntologyJson {
  const candidate = snapshot.ontology;

  if (
    candidate &&
    typeof candidate === "object" &&
    Array.isArray((candidate as RdoOntologyJson).entities)
  ) {
    return candidate as RdoOntologyJson;
  }

  return rdoOntologyFallback as RdoOntologyJson;
}

/**
 * Entidades com escopo "rdo" (padrão) participam do caminho genérico da
 * ontologia. Entidades com outro escopo (ex.: pdor, chaveada por obra) são
 * atendidas pelos caminhos dedicados, espelhando o comportamento do backend.
 */
function rdoScopedEntities(
  ontology: RdoOntologyJson,
): RdoOntologyEntityJson[] {
  return ontology.entities.filter(
    (entity) =>
      (entity.scope ?? "rdo") === "rdo" &&
      entity.name !== "operationalEvent",
  );
}

function mentionsOperationalEvent(normalizedQuestion: string): boolean {
  return /(?:^|\s)eventos?(?:\s|$)/.test(normalizedQuestion);
}

function mentionsOperationalHistory(normalizedQuestion: string): boolean {
  return [
    /\b(?:criad[oa]s?|enviad[oa]s?|aprovad[oa]s?|importad[oa]s?|atualizad[oa]s?|removid[oa]s?)\s+em\b/,
    /\b(?:data|usuario)\s+(?:de|da|do)?\s*(?:criacao|envio|aprovacao|importacao|atualizacao|remocao)\b/,
    /\bversao\s+(?:da|do)\s+(?:linha|rdo)\b/,
  ].some((pattern) => pattern.test(normalizedQuestion));
}

export function isMemoryOnlyOntologyAttribute(
  attribute: Pick<RdoOntologyAttributeJson, "name">,
): boolean {
  return MEMORY_ONLY_ATTRIBUTE_NAMES.has(attribute.name);
}

export function matchesOntologyAttribute(
  ontology: RdoOntologyJson,
  pergunta: string,
): boolean {
  const normalized = normalize(pergunta);

  if (
    mentionsOperationalEvent(normalized) ||
    mentionsOperationalHistory(normalized)
  ) {
    return false;
  }

  const entities = rdoScopedEntities(ontology);

  const attributeMatch = entities.some((entity) =>
    entity.attributes.some(
      (attribute) =>
        !isMemoryOnlyOntologyAttribute(attribute) &&
        attributeAliases(attribute).some((alias) =>
          matchesAlias(normalized, alias),
        ),
    ),
  );

  if (attributeMatch) {
    return true;
  }

  if (!LIST_WORDS.some((word) => containsWord(normalized, word))) {
    return false;
  }

  return entities.some((entity) =>
    entity.aliases.some((alias) => matchesAlias(normalized, alias)),
  );
}

export function answerWithRdoOntology({
  ontology,
  pergunta,
  rdos,
  operationalEvents = [],
}: {
  ontology: RdoOntologyJson;
  pergunta: string;
  rdos: StaviaSnapshotRdo[];
  operationalEvents?: StaviaSnapshotOperationalEvent[];
}): StaviaConsultaResponse | null {
  const normalized = normalize(pergunta);

  if (
    !normalized ||
    rdos.length === 0 ||
    mentionsOperationalEvent(normalized) ||
    mentionsOperationalHistory(normalized)
  ) {
    return null;
  }

  const matches = attributeMatches(ontology, normalized);
  const listWord = LIST_WORDS.some((word) => containsWord(normalized, word));
  const entity = targetEntity(ontology, matches, normalized, listWord);

  if (!entity) {
    return null;
  }

  const entityMatches = matches
    .filter((match) => match.entity.name === entity.name)
    .sort((a, b) => b.alias.length - a.alias.length);
  const best = entityMatches[0] ?? null;

  const rankingMax = RANKING_MAX_PHRASES.some((phrase) =>
    normalized.includes(phrase),
  );
  const rankingMin = RANKING_MIN_PHRASES.some((phrase) =>
    normalized.includes(phrase),
  );
  const compareMarker = COMPARE_MARKERS.some((marker) =>
    normalized.includes(marker),
  );
  const aggregateWord = AGGREGATE_WORDS.some((word) =>
    containsWord(normalized, word),
  );
  const aggregable = entityMatches.filter(
    (match) => match.attribute.aggregable,
  );
  const fullRecord = wantsFullRecord(normalized);
  const ordinal = rowOrdinal(normalized, entity);
  const ordinalRecord = ordinal !== null;

  let operation: "READ" | "LIST" | "AGGREGATE" | "RANKING" | "COMPARE";
  let attributes: RdoOntologyAttributeJson[];

  if (listWord && fullRecord) {
    operation = "LIST";
    attributes = listingAttributes(entity, normalized);
  } else if (
    !ordinalRecord &&
    best &&
    best.attribute.aggregable &&
    (rankingMax || rankingMin)
  ) {
    operation = "RANKING";
    attributes = [best.attribute];
  } else if (
    compareMarker &&
    distinctAttributes(aggregable).length >= 2
  ) {
    operation = "COMPARE";
    attributes = distinctAttributes(aggregable);
  } else if (
    !ordinalRecord &&
    best &&
    best.attribute.aggregable &&
    aggregateWord
  ) {
    operation = "AGGREGATE";
    attributes = [best.attribute];
  } else if (best) {
    operation = "READ";
    attributes = distinctAttributes(preferSpecificMatches(entityMatches));
  } else if (listWord) {
    operation = "LIST";
    attributes = listingAttributes(entity, normalized);
  } else {
    return null;
  }

  const period = parsePeriod(normalized);
  const identity = identityTerm(normalized, entity, entityMatches);

  let rows = entityRows(entity, rdos, operationalEvents).filter((row) =>
    matchesPeriod(row, period),
  );

  const allRowsInPeriod = rows;

  if (ordinal !== null) {
    rows = rows.filter((row) => row.index === ordinal);
  } else if (identity) {
    rows = rows.filter((row) => matchesIdentity(entity, row, identity));
  }

  if (operation === "AGGREGATE") {
    return aggregateAnswer(normalized, entity, attributes[0], rows, period);
  }

  if (operation === "RANKING") {
    return rankingAnswer(attributes[0], rows, rankingMin);
  }

  if (rows.length === 0) {
    return availableItemsAnswer(entity, allRowsInPeriod);
  }

  return recordsAnswer(entity, attributes, rows);
}

function recordsAnswer(
  entity: RdoOntologyEntityJson,
  attributes: RdoOntologyAttributeJson[],
  rows: OntologyRow[],
): StaviaConsultaResponse | null {
  const rowsWithValues = rows
    .map((row) => ({
      row,
      parts: attributes
        .map((attribute) => ({
          attribute,
          value: text(row.values[attribute.name]),
        }))
        .filter((part) => part.value !== null),
    }))
    .filter((entry) => entry.parts.length > 0)
    .sort(
      (a, b) =>
        (b.row.rdo.dataRdo ?? "").localeCompare(a.row.rdo.dataRdo ?? ""),
    );

  if (rowsWithValues.length === 0) {
    return availableItemsAnswer(entity, rows);
  }

  if (rowsWithValues.length === 1) {
    const entry = rowsWithValues[0];
    const item = itemLabel(entity, entry.row);
    const context = rdoContext(entry.row.rdo);
    let textAnswer: string;

    if (entry.parts.length === 1) {
      const part = entry.parts[0];
      textAnswer = `${attributeScopeLabel(entity, part.attribute, item)}: `
        + `${part.value}${unitSuffix(part.attribute, entry.row)}`;
    } else {
      const joined = entry.parts
        .map((part) =>
          `${part.attribute.label}: ${part.value}`
          + unitSuffix(part.attribute, entry.row),
        )
        .join("; ");
      textAnswer = `${item ?? "Registro"} — ${joined}`;
    }

    if (context) {
      textAnswer += ` (${context})`;
    }

    textAnswer += ".";

    const status = entry.row.rdo.status?.toUpperCase() ?? "";
    if (status && !VALIDATED_STATUSES.has(status)) {
      textAnswer += NOT_VALIDATED_CAVEAT;
    }

    return ontologyResponse(textAnswer);
  }

  const lines = rowsWithValues.map((entry) => {
    const item = itemLabel(entity, entry.row);
    const parts = entry.parts
      .filter((part) => part.value !== item)
      .map((part) =>
        `${part.attribute.label}: ${part.value}`
        + unitSuffix(part.attribute, entry.row),
      );
    const context = rdoContext(entry.row.rdo);

    let line = `- ${item ?? entry.parts[0].attribute.label}`;

    if (parts.length > 0) {
      line += ` — ${parts.join("; ")}`;
    }

    if (context) {
      line += ` (${context})`;
    }

    return line;
  });

  return ontologyResponse(`Registros encontrados:\n${lines.join("\n")}`);
}

function attributeScopeLabel(
  entity: RdoOntologyEntityJson,
  attribute: RdoOntologyAttributeJson,
  item: string | null,
): string {
  if (!item) {
    return attribute.label;
  }

  if (entity.name === "rdo") {
    if (attribute.label.toLowerCase().endsWith("por")) {
      return `${attribute.label} no ${item}`;
    }

    if (attribute.label.toLowerCase().endsWith(" do rdo")) {
      return `${attribute.label.replace(/\s+do RDO$/i, "")} do ${item}`;
    }

    return `${attribute.label} do ${item}`;
  }

  return `${attribute.label} de ${item}`;
}

function aggregateAnswer(
  normalized: string,
  entity: RdoOntologyEntityJson,
  attribute: RdoOntologyAttributeJson,
  rows: OntologyRow[],
  period: { start: string | null; end: string | null },
): StaviaConsultaResponse {
  const values = rows
    .map((row) => numeric(row.values[attribute.name]))
    .filter((value): value is number => value !== null);

  const isAverage = containsWord(normalized, "media");
  const isCount =
    (containsWord(normalized, "quantos")
      || containsWord(normalized, "quantas"))
    && entity.countAttribute === attribute.name;

  let value: number | null;

  if (isCount || !isAverage) {
    value = values.length === 0
      ? null
      : round(values.reduce((sum, current) => sum + current, 0));
  } else {
    value = values.length === 0
      ? null
      : round(
          values.reduce((sum, current) => sum + current, 0) / values.length,
        );
  }

  if (value === null) {
    return ontologyResponse(
      `Não encontrei registros para calcular ${attribute.label} no período.`,
      true,
    );
  }

  const opening = isAverage ? "Média de" : "Total de";
  const unit = attribute.unit ? ` ${attribute.unit}` : "";
  const count = rows.filter(
    (row) => numeric(row.values[attribute.name]) !== null,
  ).length;

  let detail = `${count}${count === 1 ? " registro" : " registros"}`;

  if (period.start && period.end) {
    detail += `, período ${formatDate(period.start)} a ${formatDate(period.end)}`;
  }

  return ontologyResponse(
    `${opening} ${attribute.label}: ${text(value)}${unit} (${detail}).`,
  );
}

function rankingAnswer(
  attribute: RdoOntologyAttributeJson,
  rows: OntologyRow[],
  minimum: boolean,
): StaviaConsultaResponse | null {
  const byRdo = new Map<string, { rdo: StaviaSnapshotRdo; value: number }>();

  for (const row of rows) {
    const value = numeric(row.values[attribute.name]);

    if (value === null) {
      continue;
    }

    const current = byRdo.get(row.rdo.id);

    if (
      !current ||
      (minimum ? value < current.value : value > current.value)
    ) {
      byRdo.set(row.rdo.id, { rdo: row.rdo, value });
    }
  }

  const ranked = Array.from(byRdo.values()).sort((a, b) =>
    minimum ? a.value - b.value : b.value - a.value,
  );

  if (ranked.length === 0) {
    return ontologyResponse(
      `Não encontrei registros para calcular ${attribute.label} no período.`,
      true,
    );
  }

  const unit = attribute.unit ? ` ${attribute.unit}` : "";
  const top = ranked[0];

  let answerText =
    `O RDO ${top.rdo.numeroRdo ?? top.rdo.id} de `
    + `${formatDate(top.rdo.dataRdo)} teve o `
    + `${minimum ? "menor" : "maior"} valor de ${attribute.label}: `
    + `${text(top.value)}${unit}.`;

  ranked.slice(1, 5).forEach((entry, index) => {
    answerText += `\n- ${index + 2}º: RDO ${entry.rdo.numeroRdo ?? entry.rdo.id}`
      + ` de ${formatDate(entry.rdo.dataRdo)} — ${text(entry.value)}${unit}`;
  });

  return ontologyResponse(answerText);
}

function availableItemsAnswer(
  entity: RdoOntologyEntityJson,
  rows: OntologyRow[],
): StaviaConsultaResponse {
  const names: string[] = [];

  for (const row of rows) {
    const item = itemLabel(entity, row);

    if (item && !names.includes(item)) {
      names.push(item);
    }

    if (names.length >= 5) {
      break;
    }
  }

  if (names.length === 0) {
    return ontologyResponse(
      "Não encontrei registros desse tipo para o contexto selecionado.",
      true,
    );
  }

  return ontologyResponse(
    "Não encontrei a informação solicitada para o termo pesquisado. "
      + `Registrados: ${names.join(", ")}.`,
    true,
  );
}

function ontologyResponse(
  answerText: string,
  insufficientData = false,
): StaviaConsultaResponse {
  return {
    answer: {
      answer: answerText,
      confidence: insufficientData ? "BAIXA" : "ALTA",
      answerType: insufficientData ? "INFORMACAO_INSUFICIENTE" : "FATO",
      sources: [],
      insufficientData,
      warnings: [],
      metadata: {
        origemResposta: "SNAPSHOT_LOCAL_STAVIA",
        caminho: "ONTOLOGIA_RDO",
      },
    },
    intent: "REGISTROS_RDO",
    consultedKnowledgeSources: {
      "snapshot-local-stavia": "STAVIA-OFFLINE-0.1.0",
    },
    knowledgeWarnings: [],
  };
}

function entityRows(
  entity: RdoOntologyEntityJson,
  rdos: StaviaSnapshotRdo[],
  operationalEvents: StaviaSnapshotOperationalEvent[],
): OntologyRow[] {
  if (entity.header) {
    return rdos.map((rdo) => ({
      rdo,
      values: rdo as unknown as Record<string, unknown>,
      index: 1,
    }));
  }

  const collection = entity.snapshotCollection;

  if (!collection) {
    return [];
  }

  if (collection === "operationalEvents") {
    return operationalEventRows(rdos, operationalEvents);
  }

  return rdos.flatMap((rdo) => {
    const items = (rdo as unknown as Record<string, unknown>)[collection];

    if (!Array.isArray(items)) {
      return [];
    }

    return items.map((item, index) => ({
      rdo,
      values: item as Record<string, unknown>,
      index: index + 1,
    }));
  });
}

function operationalEventRows(
  rdos: StaviaSnapshotRdo[],
  events: StaviaSnapshotOperationalEvent[],
): OntologyRow[] {
  const rdoById = new Map(rdos.map((rdo) => [rdo.id, rdo]));
  const fallbackRdoByObraId = new Map<string, StaviaSnapshotRdo>();

  for (const rdo of rdos) {
    if (!fallbackRdoByObraId.has(rdo.obraId)) {
      fallbackRdoByObraId.set(rdo.obraId, rdo);
    }
  }

  const indexesByScope = new Map<string, number>();

  return events
    .slice()
    .sort((left, right) =>
      (right.occurredAt ?? "").localeCompare(left.occurredAt ?? ""),
    )
    .flatMap((event) => {
      const rdo = event.rdoId
        ? rdoById.get(event.rdoId)
        : event.obraId
          ? fallbackRdoByObraId.get(event.obraId)
          : undefined;

      if (!rdo) {
        return [];
      }

      const scope = event.rdoId ?? event.obraId ?? "global";
      const index = (indexesByScope.get(scope) ?? 0) + 1;
      indexesByScope.set(scope, index);

      return [{
        rdo,
        values: event as unknown as Record<string, unknown>,
        index,
      }];
    });
}

function attributeMatches(
  ontology: RdoOntologyJson,
  normalized: string,
): AttributeMatch[] {
  const matches: AttributeMatch[] = [];

  for (const entity of rdoScopedEntities(ontology)) {
    for (const attribute of entity.attributes) {
      if (isMemoryOnlyOntologyAttribute(attribute)) {
        continue;
      }

      for (const alias of attributeAliases(attribute)) {
        if (matchesAlias(normalized, alias)) {
          matches.push({ entity, attribute, alias: normalize(alias) });
        }
      }
    }
  }

  return matches;
}

function targetEntity(
  ontology: RdoOntologyJson,
  matches: AttributeMatch[],
  normalized: string,
  allowHeaderAlias = false,
): RdoOntologyEntityJson | null {
  if (allowHeaderAlias && wantsFullRecord(normalized)) {
    const explicitEntity = entityByAlias(
      ontology,
      normalized,
      allowHeaderAlias,
    );

    if (explicitEntity) {
      return explicitEntity;
    }
  }

  let best: AttributeMatch | null = null;
  let bestScore = -1;

  for (const match of matches) {
    const score = match.alias.length + entityContextScore(match.entity, normalized);

    if (score > bestScore) {
      best = match;
      bestScore = score;
    }
  }

  if (best) {
    return best.entity;
  }

  return entityByAlias(ontology, normalized, allowHeaderAlias);
}

function entityByAlias(
  ontology: RdoOntologyJson,
  normalized: string,
  allowHeaderAlias: boolean,
): RdoOntologyEntityJson | null {
  let byAlias: RdoOntologyEntityJson | null = null;
  let bestLength = 0;

  for (const entity of rdoScopedEntities(ontology)) {
    for (const alias of entity.aliases) {
      const normalizedAlias = normalize(alias);

      if (
        matchesAlias(normalized, alias) &&
        normalizedAlias.length > bestLength
      ) {
        byAlias = entity;
        bestLength = normalizedAlias.length;
      }
    }
  }

  return byAlias && (!byAlias.header || allowHeaderAlias) ? byAlias : null;
}

function entityContextScore(
  entity: RdoOntologyEntityJson,
  normalized: string,
): number {
  const entityAliasLength = matchingEntityAliasLength(entity, normalized);
  const aliasScore =
    entityAliasLength > 0 ? 1_000 + entityAliasLength : 0;
  const ordinalScore = rowOrdinalContextScore(entity, normalized);

  return Math.max(aliasScore, ordinalScore);
}

function matchingEntityAliasLength(
  entity: RdoOntologyEntityJson,
  normalized: string,
): number {
  let length = 0;

  for (const alias of entity.aliases) {
    const normalizedAlias = normalize(alias);

    if (
      matchesAlias(normalized, alias) &&
      normalizedAlias.length > length
    ) {
      length = normalizedAlias.length;
    }
  }

  return length;
}

function rowOrdinalContextScore(
  entity: RdoOntologyEntityJson,
  normalized: string,
): number {
  const termLength = matchingRowOrdinalTermLength(entity, normalized);

  if (termLength === 0) {
    return 0;
  }

  let score = 2_000 + termLength;

  if (
    entity.name === "maoObra" &&
    mentionsLaborOrdinal(normalized) &&
    !mentionsAllocationContext(normalized)
  ) {
    score += 500;
  }

  if (
    entity.name === "alocacaoColaborador" &&
    mentionsAllocationContext(normalized)
  ) {
    score += 500;
  }

  return score;
}

function matchingRowOrdinalTermLength(
  entity: RdoOntologyEntityJson,
  normalized: string,
): number {
  if (entity.header) {
    return 0;
  }

  let length = 0;

  for (const term of rowOrdinalTerms(entity)) {
    if (matchesOrdinalTerm(normalized, term) && term.length > length) {
      length = term.length;
    }
  }

  return length;
}

function matchesOrdinalTerm(normalized: string, term: string): boolean {
  const escaped = escapeRegExp(term);

  if (
    new RegExp(`\\b${escaped}\\s+(?:n(?:umero)?\\s*)?(\\d+)\\b`).test(
      normalized,
    )
  ) {
    return true;
  }

  for (const word of Object.keys(ORDINAL_WORDS)) {
    if (
      new RegExp(`\\b${word}\\s+${escaped}\\b`).test(normalized) ||
      new RegExp(`\\b${escaped}\\s+${word}\\b`).test(normalized)
    ) {
      return true;
    }
  }

  return false;
}

function mentionsLaborOrdinal(normalized: string): boolean {
  return ["colaborador", "funcionario", "trabalhador", "pessoa"].some((word) =>
    containsWord(normalized, word),
  );
}

function mentionsAllocationContext(normalized: string): boolean {
  return ["alocacao", "alocacoes", "apontamento", "apontamentos"].some(
    (word) => containsWord(normalized, word),
  );
}

function identityTerm(
  normalized: string,
  entity: RdoOntologyEntityJson,
  matches: AttributeMatch[],
): string | null {
  const removable = new Set<string>();

  for (const match of matches) {
    for (const token of match.alias.split(/\s+/)) {
      removable.add(token);
    }
  }

  for (const alias of entity.aliases) {
    if (matchesAlias(normalized, alias)) {
      for (const token of normalize(alias).split(/\s+/)) {
        removable.add(token);
      }
    }
  }

  const kept: string[] = [];

  for (const rawToken of normalized.split(/\s+/)) {
    const token = rawToken.replace(
      /^[^\p{L}\p{Nd}]+|[^\p{L}\p{Nd}]+$/gu,
      "",
    );

    if (
      !token ||
      IDENTITY_STOPWORDS.has(token) ||
      removable.has(token) ||
      /^\d{2}\/\d{2}\/\d{4}$/.test(token) ||
      /^\d{4}-\d{2}-\d{2}$/.test(token)
    ) {
      continue;
    }

    kept.push(token);
  }

  const identity = kept.join(" ").trim();

  if (!identity) {
    return null;
  }

  for (const alias of entity.aliases) {
    if (normalize(alias) === identity) {
      return null;
    }
  }

  return identity;
}

function listingAttributes(
  entity: RdoOntologyEntityJson,
  normalized: string,
): RdoOntologyAttributeJson[] {
  if (wantsFullRecord(normalized)) {
    return entity.attributes.filter(
      (attribute) => !isMemoryOnlyOntologyAttribute(attribute),
    );
  }

  const names = new Set<string>();
  const attributes: RdoOntologyAttributeJson[] = [];

  for (const attribute of entity.attributes) {
    if (
      !isMemoryOnlyOntologyAttribute(attribute) &&
      (attribute.identity || attribute.aggregable) &&
      !names.has(attribute.name)
    ) {
      names.add(attribute.name);
      attributes.push(attribute);
    }
  }

  return attributes;
}

function wantsFullRecord(normalized: string): boolean {
  return FULL_RECORD_WORDS.some((word) => containsWord(normalized, word));
}

function distinctAttributes(
  matches: AttributeMatch[],
): RdoOntologyAttributeJson[] {
  const names = new Set<string>();
  const attributes: RdoOntologyAttributeJson[] = [];

  for (const match of matches) {
    if (
      !isMemoryOnlyOntologyAttribute(match.attribute) &&
      !names.has(match.attribute.name)
    ) {
      names.add(match.attribute.name);
      attributes.push(match.attribute);
    }
  }

  return attributes;
}

function preferSpecificMatches(
  matches: AttributeMatch[],
): AttributeMatch[] {
  if (matches.length <= 1) {
    return matches;
  }

  const entity = matches[0]?.entity;
  const contextAliases = entity
    ? new Set(rowOrdinalTerms(entity))
    : new Set<string>();
  const nonContextMatches = matches.filter(
    (match) =>
      !(match.attribute.identity && contextAliases.has(match.alias)),
  );
  const scopedMatches = nonContextMatches.length > 0
    ? nonContextMatches
    : matches;

  return scopedMatches.filter(
    (match) =>
      !scopedMatches.some(
        (other) =>
          other.attribute.name !== match.attribute.name &&
          other.alias.length > match.alias.length &&
          other.alias.includes(match.alias),
      ),
  );
}

function matchesIdentity(
  entity: RdoOntologyEntityJson,
  row: OntologyRow,
  identity: string,
): boolean {
  const term = normalize(identity);
  const ordinalLabel = normalize(rowOrdinalLabel(entity, row));

  if (ordinalLabel.includes(term)) {
    return true;
  }

  return entity.attributes
    .filter((attribute) => attribute.identity)
    .some((attribute) => {
      const value = text(row.values[attribute.name]);
      return value !== null && normalize(value).includes(term);
    });
}

function itemLabel(
  entity: RdoOntologyEntityJson,
  row: OntologyRow,
): string | null {
  if (entity.name === "rdo") {
    const numeroRdo = text(row.values.numeroRdo);

    if (numeroRdo !== null) {
      return `RDO ${numeroRdo}`;
    }
  }

  const ordinalLabel = rowOrdinalLabel(entity, row);

  for (const attribute of entity.attributes) {
    if (!attribute.identity) {
      continue;
    }

    const value = text(row.values[attribute.name]);

    if (value !== null) {
      if (ordinalLabel) {
        return `${ordinalLabel} (${value})`;
      }

      return value;
    }
  }

  return ordinalLabel;
}

function rowOrdinal(
  normalized: string,
  entity: RdoOntologyEntityJson,
): number | null {
  if (entity.header) {
    return null;
  }

  const candidates = rowOrdinalTerms(entity);

  for (const term of candidates) {
    const escaped = escapeRegExp(term);
    const numeric = normalized.match(
      new RegExp(`\\b${escaped}\\s+(?:n(?:umero)?\\s*)?(\\d+)\\b`),
    );

    if (numeric) {
      return Number(numeric[1]);
    }
  }

  for (const [word, index] of Object.entries(ORDINAL_WORDS)) {
    for (const term of candidates) {
      const escaped = escapeRegExp(term);

      if (
        new RegExp(`\\b${word}\\s+${escaped}\\b`).test(normalized) ||
        new RegExp(`\\b${escaped}\\s+${word}\\b`).test(normalized)
      ) {
        return index;
      }
    }
  }

  return null;
}

function rowOrdinalTerms(entity: RdoOntologyEntityJson): string[] {
  const terms = [
    "linha",
    "item",
    "registro",
    ...entity.aliases.map(normalize),
  ];

  if (entity.name === "controleGeometrico") {
    terms.push("trecho", "controle", "medicao", "medicao geometrica");
  }

  if (entity.name === "material") {
    terms.push("material", "insumo");
  }

  if (entity.name === "maoObra") {
    terms.push(
      "mao de obra",
      "colaborador",
      "trabalhador",
      "funcionario",
      "pessoa",
    );
  }

  if (entity.name === "equipamento") {
    terms.push("equipamento", "maquina", "ativo");
  }

  if (entity.name === "execucaoServico") {
    terms.push("servico", "atividade", "execucao", "producao");
  }

  if (entity.name === "attachment") {
    terms.push("foto", "anexo");
  }

  if (entity.name === "alocacaoColaborador") {
    terms.push("alocacao", "apontamento", "colaborador");
  }

  if (entity.name === "operationalEvent") {
    terms.push("evento", "evento operacional", "evento ontologico");
  }

  return Array.from(
    new Set(terms.filter((term) => term && !term.includes("/"))),
  ).sort((left, right) => right.length - left.length);
}

function rowOrdinalLabel(
  entity: RdoOntologyEntityJson,
  row: OntologyRow,
): string | null {
  if (entity.name === "controleGeometrico") {
    return `Trecho ${row.index}`;
  }

  if (entity.name === "attachment") {
    return `Foto ${row.index}`;
  }

  if (entity.name === "alocacaoColaborador") {
    return `Alocação ${row.index}`;
  }

  if (entity.name === "operationalEvent") {
    return `Evento ${row.index}`;
  }

  return null;
}

function unitSuffix(
  attribute: RdoOntologyAttributeJson,
  row: OntologyRow,
): string {
  if (attribute.unit) {
    return ` ${attribute.unit}`;
  }

  if (attribute.unitColumn) {
    const field =
      attribute.unitColumn === "unidade_medida"
        ? "unidade"
        : attribute.unitColumn;
    const value = text(row.values[field]);

    if (value !== null) {
      return ` ${value}`;
    }
  }

  return "";
}

function rdoContext(rdo: StaviaSnapshotRdo): string {
  if (!rdo.numeroRdo) {
    return "";
  }

  const date = formatDate(rdo.dataRdo);

  return `RDO ${rdo.numeroRdo}${date ? ` de ${date}` : ""}`;
}

function parsePeriod(normalized: string): {
  start: string | null;
  end: string | null;
} {
  const today = localDate(new Date());

  if (normalized.includes("hoje")) {
    return { start: today, end: today };
  }

  if (normalized.includes("ontem")) {
    const yesterday = shiftDays(today, -1);
    return { start: yesterday, end: yesterday };
  }

  if (
    normalized.includes("esta semana") ||
    normalized.includes("essa semana")
  ) {
    return { start: weekStart(today), end: today };
  }

  if (normalized.includes("semana passada")) {
    const thisWeekStart = weekStart(today);
    return {
      start: shiftDays(thisWeekStart, -7),
      end: shiftDays(thisWeekStart, -1),
    };
  }

  const brazilian = normalized.match(/(\d{2})\/(\d{2})\/(\d{4})/);

  if (brazilian) {
    const iso = `${brazilian[3]}-${brazilian[2]}-${brazilian[1]}`;
    return { start: iso, end: iso };
  }

  const iso = normalized.match(/(\d{4}-\d{2}-\d{2})/);

  if (iso) {
    return { start: iso[1], end: iso[1] };
  }

  return { start: null, end: null };
}

function matchesPeriod(
  row: OntologyRow,
  period: { start: string | null; end: string | null },
): boolean {
  const eventDate =
    typeof row.values.occurredAt === "string"
      ? row.values.occurredAt.slice(0, 10)
      : null;
  const date = eventDate || row.rdo.dataRdo;

  if (!date) {
    return true;
  }

  if (period.start && date < period.start) {
    return false;
  }

  return !period.end || date <= period.end;
}

function attributeAliases(
  attribute: RdoOntologyAttributeJson,
): string[] {
  return Array.from(new Set([attribute.label, ...attribute.aliases]));
}

function localDate(value: Date): string {
  const year = value.getFullYear();
  const month = String(value.getMonth() + 1).padStart(2, "0");
  const day = String(value.getDate()).padStart(2, "0");

  return `${year}-${month}-${day}`;
}

function shiftDays(isoDate: string, days: number): string {
  const date = new Date(`${isoDate}T12:00:00`);
  date.setDate(date.getDate() + days);

  return localDate(date);
}

function weekStart(isoDate: string): string {
  const date = new Date(`${isoDate}T12:00:00`);
  const weekday = date.getDay() === 0 ? 7 : date.getDay();

  return shiftDays(isoDate, -(weekday - 1));
}

function formatDate(value: string | null | undefined): string {
  if (!value || !/^\d{4}-\d{2}-\d{2}$/.test(value)) {
    return value ?? "";
  }

  const [year, month, day] = value.split("-");

  return `${day}/${month}/${year}`;
}

function matchesAlias(normalized: string, alias: string): boolean {
  const clean = normalize(alias);

  if (!clean) {
    return false;
  }

  if (clean.includes(" ")) {
    return normalized.includes(clean);
  }

  return containsWord(normalized, clean);
}

function containsWord(normalized: string, term: string): boolean {
  return new RegExp(`\\b${escapeRegExp(term)}`).test(normalized);
}

function escapeRegExp(value: string): string {
  return value.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
}

function normalize(value: string | null | undefined): string {
  return (value ?? "")
    .normalize("NFD")
    .replace(/\p{Diacritic}/gu, "")
    .toLowerCase()
    .replace(/[^a-z0-9\s/-]/g, " ")
    .replace(/\s+/g, " ")
    .trim();
}

function text(value: unknown): string | null {
  if (value === null || value === undefined) {
    return null;
  }

  if (typeof value === "number") {
    return String(round(value));
  }

  if (typeof value === "object") {
    return JSON.stringify(value);
  }

  const trimmed = String(value).trim();

  return trimmed.length === 0 ? null : trimmed;
}

function numeric(value: unknown): number | null {
  if (value === null || value === undefined || value === "") {
    return null;
  }

  const parsed = Number(value);

  return Number.isFinite(parsed) ? parsed : null;
}

function round(value: number): number {
  return Math.round(value * 1000) / 1000;
}
