import type {
  StaviaAnswer,
  StaviaConfidence,
  StaviaConsultaResponse,
  StaviaEvidence,
  StaviaSnapshot,
  StaviaSnapshotObra,
  StaviaSnapshotPdor,
  StaviaSnapshotProgramacao,
  StaviaSnapshotRdo,
} from "./stavia.types";
import {
  answerWithRdoOntology,
  loadRdoOntology,
  matchesOntologyAttribute,
} from "./staviaRdoOntology";

export interface StaviaLocalContext {
  activeObraId?: string | null;
  activeRdoId?: string | null;
  lastObraId?: string | null;
  lastRdoId?: string | null;
}

type LocalIntent =
  | "SERVICOS_DA_OBRA"
  | "COLABORADORES_DA_OBRA"
  | "TURNO"
  | "EQUIPAMENTOS"
  | "MATERIAIS"
  | "OBSERVACOES_SERVICO"
  | "LOCALIZACAO_OBRA"
  | "STATUS_SINCRONIZACAO"
  | "PDOR"
  | "OBRAS_POR_CIDADE"
  | "CONTAGEM_RDOS_OBRA"
  | "DETALHE_RDO"
  | "RESUMO_RDO"
  | "OCORRENCIAS"
  | "TIMELINE_OPERACIONAL"
  | "FOTOS_RDO"
  | "CONSULTA_COMPOSTA"
  | "EVIDENCIAS"
  | "DESCONHECIDA";

type CompositeTopic =
  | "SERVICOS_DA_OBRA"
  | "LOCALIZACAO_OBRA"
  | "TURNO"
  | "COLABORADORES_DA_OBRA"
  | "EQUIPAMENTOS"
  | "MATERIAIS"
  | "CONTAGEM_RDOS_OBRA"
  | "PDOR"
  | "OCORRENCIAS";

interface ResolvedContext {
  obra: StaviaSnapshotObra | null;
  rdo: StaviaSnapshotRdo | null;
  rdos: StaviaSnapshotRdo[];
  ambiguousLabels: string[];
}

const STOPWORDS = new Set([
  "a",
  "as",
  "o",
  "os",
  "de",
  "da",
  "das",
  "do",
  "dos",
  "em",
  "no",
  "na",
  "nos",
  "nas",
  "essa",
  "esse",
  "dessa",
  "desse",
  "nesse",
  "nesta",
  "neste",
  "obra",
  "rdo",
  "quais",
  "qual",
  "quem",
  "quantos",
  "quantas",
  "foram",
  "foi",
  "estao",
  "esta",
  "tem",
  "tenho",
  "acontecendo",
  "aqui",
]);

function normalizeText(value: string | null | undefined): string {
  return (value ?? "")
    .normalize("NFD")
    .replace(/\p{Diacritic}/gu, "")
    .toLowerCase()
    .replace(/[^a-z0-9\s/-]/g, " ")
    .replace(/\s+/g, " ")
    .trim();
}

function hasAny(value: string, terms: string[]): boolean {
  return terms.some((term) =>
    value.includes(normalizeText(term)),
  );
}

function text(value: string | null | undefined): string {
  return value?.trim() ?? "";
}

function timeRange(
  start: string | null | undefined,
  end: string | null | undefined,
): string {
  const startText = text(start);
  const endText = text(end);

  if (startText && endText) {
    return `${startText}-${endText}`;
  }

  return startText || endText;
}

function unique(values: Array<string | null | undefined>): string[] {
  const seen = new Set<string>();
  const result: string[] = [];

  for (const value of values) {
    const clean = text(value);
    if (!clean) {
      continue;
    }

    const key = normalizeText(clean);
    if (seen.has(key)) {
      continue;
    }

    seen.add(key);
    result.push(clean);
  }

  return result;
}

function formatDateTime(value: string | null | undefined): string {
  if (!value) {
    return "data não informada";
  }

  const date = new Date(value);
  if (Number.isNaN(date.getTime())) {
    return value;
  }

  return new Intl.DateTimeFormat("pt-BR", {
    dateStyle: "short",
    timeStyle: "short",
  }).format(date);
}

function formatDateOnly(value: string | null | undefined): string {
  if (!value) {
    return "";
  }

  const date = /^\d{4}-\d{2}-\d{2}$/.test(value)
    ? new Date(`${value}T00:00:00`)
    : new Date(value);
  if (Number.isNaN(date.getTime())) {
    return value;
  }

  return new Intl.DateTimeFormat("pt-BR", {
    dateStyle: "short",
  }).format(date);
}

function readableStatus(value: string | null | undefined): string {
  const normalized = normalizeText(value);

  if (normalized === "diurno") {
    return "diurno";
  }

  if (normalized === "noturno") {
    return "noturno";
  }

  if (!value) {
    return "";
  }

  return value
    .toLowerCase()
    .replace(/_/g, " ")
    .replace(/^./, (first) => first.toUpperCase());
}

function requestedCompositeTopics(
  normalizedQuestion: string,
): CompositeTopic[] {
  const topics: CompositeTopic[] = [];

  if (
    hasAny(normalizedQuestion, [
      "servico",
      "servicos",
      "tipo de servico",
      "tipos de servico",
      "servico feito",
      "servico executado",
      "o que foi feito",
      "o que executou",
      "atividade executada",
      "atividades executadas",
      "fresagem",
      "recomposicao",
      "revestimento",
      "drenagem",
      "sinalizacao",
      "encarregado responsavel",
      "responsavel pelo servico",
    ])
  ) {
    topics.push("SERVICOS_DA_OBRA");
  }

  if (
    hasAny(normalizedQuestion, [
      "onde foi",
      "qual cidade",
      "cidade",
      "municipio",
      "município",
      "qual trecho",
      "trecho",
      "qual contrato",
      "contrato",
      "qual cw",
      "cw",
      "localizacao",
      "localização",
      "rodovia",
    ])
  ) {
    topics.push("LOCALIZACAO_OBRA");
  }

  if (
    hasAny(normalizedQuestion, [
      "turno",
      "foi de dia",
      "foi de noite",
      "periodo trabalharam",
      "periodo de trabalho",
    ])
  ) {
    topics.push("TURNO");
  }

  if (
    hasAny(normalizedQuestion, [
      "colaboradores",
      "colaborador",
      "funcionarios",
      "funcionario",
      "quem esta trabalhando",
      "quem trabalhou",
      "qual a equipe",
      "equipe",
      "pessoal da obra",
      "mao de obra",
      "trabalhadores",
      "pessoas trabalharam",
      "operador",
      "operadores",
      "apontador",
      "apontadores",
      "encarregado",
      "encarregados",
    ])
  ) {
    topics.push("COLABORADORES_DA_OBRA");
  }

  if (
    hasAny(normalizedQuestion, [
      "equipamentos",
      "equipamento",
      "maquinas",
      "maquina",
      "maquinario",
      "ativos usados",
      "prefixo",
    ])
  ) {
    topics.push("EQUIPAMENTOS");
  }

  if (
    hasAny(normalizedQuestion, [
      "materiais",
      "material usado",
      "insumos",
      "o que foi aplicado",
      "aplicados",
      "aplicado",
    ])
  ) {
    topics.push("MATERIAIS");
  }

  if (
    hasAny(normalizedQuestion, [
      "quantos rdos",
      "quantidade de rdos",
      "total de rdos",
      "numero de rdos",
      "número de rdos",
      "qtd de rdos",
      "rdos da obra",
      "rdos dessa obra",
      "rdos tenho",
    ])
  ) {
    topics.push("CONTAGEM_RDOS_OBRA");
  }

  if (
    hasAny(normalizedQuestion, [
      "pdor",
      "previsao de receita",
      "receita prevista",
      "receita final",
      "captura de receita",
      "shortfall",
      "bater o contrato",
      "atingir o contrato",
      "probabilidade de nao atingir",
    ])
  ) {
    topics.push("PDOR");
  }

  if (
    hasAny(normalizedQuestion, [
      "ocorrencia",
      "ocorrencias",
      "problema",
      "incidente",
      "parada",
    ])
  ) {
    topics.push("OCORRENCIAS");
  }

  return topics;
}

function isRdoDetailQuestion(normalizedQuestion: string): boolean {
  return hasAny(normalizedQuestion, [
    "qual a data",
    "qual data",
    "data do rdo",
    "data deste rdo",
    "data desse rdo",
    "data operacional",
    "qual o dia",
    "qual dia",
    "dia do rdo",
    "quando foi o rdo",
    "quando foi esse rdo",
    "quando foi este rdo",
    "trecho programado",
    "programado inicial",
    "programado final",
    "km inicial programado",
    "km final programado",
    "trecho inicial",
    "trecho final",
    "km inicial",
    "km final",
    "preenchido por",
    "quem preencheu",
    "apontador",
    "encarregado",
    "encarregado da obra",
    "encarregado obra",
    "fiscalizacao",
    "fiscalização",
    "programacao diaria",
    "programação diária",
    "programacao prevista",
    "programação prevista",
    "programacao semanal",
    "programação semanal",
    "periodo",
    "período",
    "area",
    "área",
    "volume",
    "extensao",
    "extensão",
    "largura",
    "espessura",
    "tonelada",
    "massa",
    "cap",
  ]);
}

function isServiceObservationQuestion(
  normalizedQuestion: string,
): boolean {
  return hasAny(normalizedQuestion, [
    "observacao",
    "observacoes",
    "obs",
  ]);
}

function isObservationRollupQuestion(question: string): boolean {
  const normalized = normalizeText(question);

  if (
    hasAny(normalized, [
      "material",
      "mao de obra",
      "mão de obra",
      "equipamento",
      "servico",
      "serviço",
      "trecho",
      "controle",
      "alocacao",
      "alocação",
      "foto",
      "evento",
    ])
  ) {
    return false;
  }

  return hasAny(normalized, [
    "quais observacoes",
    "quais observações",
    "todas observacoes",
    "todas observações",
    "observacoes existem",
    "observações existem",
    "observacoes ha",
    "observações há",
    "observacoes neste rdo",
    "observações neste rdo",
    "observacoes deste rdo",
    "observações deste rdo",
    "observacoes nesse rdo",
    "observações nesse rdo",
  ]);
}

function detectIntent(question: string): LocalIntent {
  const normalized = normalizeText(question);

  if (
    hasAny(normalized, [
      "evidencia",
      "evidencias",
      "fontes",
      "fonte",
      "debug",
      "detalhes",
      "mostre detalhes",
      "explique",
    ])
  ) {
    return "EVIDENCIAS";
  }

  if (
    hasAny(normalized, [
      "atualizado",
      "atualizada",
      "ultima atualizacao",
      "ultima sincronizacao",
      "ate quando voce sabe",
      "dados sao de quando",
      "isso esta atualizado",
    ])
  ) {
    return "STATUS_SINCRONIZACAO";
  }

  if (
    hasAny(normalized, [
      "timeline",
      "historico operacional",
      "histórico operacional",
      "linha do tempo",
      "ultimas atualizacoes operacionais",
      "últimas atualizações operacionais",
      "alteracoes offline",
      "alterações offline",
      "eventos ontologicos",
      "eventos ontológicos",
      "o que aconteceu",
      "movimentou",
    ])
  ) {
    return "TIMELINE_OPERACIONAL";
  }

  if (
    hasAny(normalized, [
      "foto",
      "fotos",
      "imagem",
      "imagens",
      "anexos pendentes",
      "fotos pendentes",
      "fotos sem sincronizar",
    ])
  ) {
    return "FOTOS_RDO";
  }

  if (isRdoDetailQuestion(normalized)) {
    return "DETALHE_RDO";
  }

  if (isServiceObservationQuestion(normalized)) {
    return "OBSERVACOES_SERVICO";
  }

  if (requestedCompositeTopics(normalized).length > 1) {
    return "CONSULTA_COMPOSTA";
  }

  if (
    hasAny(normalized, [
      "pdor",
      "previsao de receita",
      "receita prevista",
      "receita final",
      "captura de receita",
      "shortfall",
      "bater o contrato",
      "atingir o contrato",
      "probabilidade de nao atingir",
    ])
  ) {
    return "PDOR";
  }

  if (
    hasAny(normalized, [
      "quantos rdos",
      "quantidade de rdos",
      "total de rdos",
      "numero de rdos",
      "número de rdos",
      "qtd de rdos",
      "rdos da obra",
      "rdos dessa obra",
      "rdos tenho",
    ])
  ) {
    return "CONTAGEM_RDOS_OBRA";
  }

  if (
    hasAny(normalized, [
      "qual obra esta acontecendo",
      "qual obra está acontecendo",
      "quais obras estao acontecendo",
      "quais obras estão acontecendo",
      "obra esta acontecendo na cidade",
      "obra está acontecendo na cidade",
      "obras na cidade",
      "obra na cidade",
      "obras em",
    ])
  ) {
    return "OBRAS_POR_CIDADE";
  }

  if (
    hasAny(normalized, [
      "servico",
      "servicos",
      "tipo de servico",
      "tipos de servico",
      "servico feito",
      "servico executado",
      "o que foi feito",
      "o que executou",
      "atividade executada",
      "atividades executadas",
      "fresagem",
      "recomposicao",
      "revestimento",
      "drenagem",
      "sinalizacao",
      "encarregado responsavel",
      "responsavel pelo servico",
    ])
  ) {
    return "SERVICOS_DA_OBRA";
  }

  if (
    hasAny(normalized, [
      "colaboradores",
      "colaborador",
      "funcionarios",
      "funcionario",
      "quem esta trabalhando",
      "quem trabalhou",
      "qual a equipe",
      "equipe",
      "pessoal da obra",
      "mao de obra",
      "trabalhadores",
      "pessoas trabalharam",
      "operador",
      "operadores",
      "apontador",
      "apontadores",
      "encarregado",
      "encarregados",
    ])
  ) {
    return "COLABORADORES_DA_OBRA";
  }

  if (
    hasAny(normalized, [
      "turno",
      "foi de dia",
      "foi de noite",
      "periodo trabalharam",
      "periodo de trabalho",
    ])
  ) {
    return "TURNO";
  }

  if (
    hasAny(normalized, [
      "equipamentos",
      "equipamento",
      "maquinas",
      "maquina",
      "maquinario",
      "ativos usados",
      "prefixo",
    ])
  ) {
    return "EQUIPAMENTOS";
  }

  if (
    hasAny(normalized, [
      "materiais",
      "material usado",
      "insumos",
      "o que foi aplicado",
      "aplicados",
      "aplicado",
    ])
  ) {
    return "MATERIAIS";
  }

  if (
    hasAny(normalized, [
      "onde foi",
      "qual cidade",
      "qual trecho",
      "qual contrato",
      "qual cw",
      "localizacao",
      "cidade",
      "trecho",
      "contrato",
      "cw",
    ])
  ) {
    return "LOCALIZACAO_OBRA";
  }

  if (
    hasAny(normalized, [
      "resumo",
      "resuma",
      "visao geral",
      "mostre um resumo",
    ])
  ) {
    return "RESUMO_RDO";
  }

  if (
    hasAny(normalized, [
      "ocorrencia",
      "ocorrencias",
      "problema",
      "incidente",
      "parada",
    ])
  ) {
    return "OCORRENCIAS";
  }

  return "DESCONHECIDA";
}

function fieldScore(
  normalizedQuestion: string,
  value: string | null | undefined,
  weight: number,
): number {
  const normalizedValue = normalizeText(value);
  if (normalizedValue.length < 3) {
    return 0;
  }

  return normalizedQuestion.includes(normalizedValue)
    ? weight
    : 0;
}

function terms(question: string): string[] {
  return normalizeText(question)
    .split(/\s+/)
    .filter(
      (term) =>
        term.length >= 3 &&
        !STOPWORDS.has(term) &&
        !/^\d+$/.test(term),
    );
}

function scoreObra(
  obra: StaviaSnapshotObra,
  question: string,
  tokenList: string[],
): number {
  const normalized = normalizeText(question);
  const fields = [
    obra.codigoContrato,
    obra.codigoCw,
    obra.codigoInterno,
    obra.nome,
    obra.cidade,
    obra.rodovia,
  ];

  let score =
    fieldScore(normalized, obra.id, 12) +
    fieldScore(normalized, obra.codigoCw, 9) +
    fieldScore(normalized, obra.codigoContrato, 8) +
    fieldScore(normalized, obra.codigoInterno, 8) +
    fieldScore(normalized, obra.nome, 6) +
    fieldScore(normalized, obra.cidade, 5) +
    fieldScore(normalized, obra.rodovia, 4);

  const joined = normalizeText(fields.filter(Boolean).join(" "));
  for (const term of tokenList) {
    if (joined.includes(term)) {
      score += 1;
    }
  }

  return score;
}

function scoreRdo(
  rdo: StaviaSnapshotRdo,
  obra: StaviaSnapshotObra | undefined,
  question: string,
  tokenList: string[],
): number {
  const normalized = normalizeText(question);
  const numberMatch = normalized.match(/\brdo\s*([a-z0-9/-]+)/);
  let score =
    fieldScore(normalized, rdo.id, 14) +
    fieldScore(normalized, rdo.numeroRdo, 9) +
    fieldScore(normalized, rdo.dataRdo, 7) +
    fieldScore(normalized, rdo.cidade, 5) +
    fieldScore(normalized, rdo.contrato, 5) +
    fieldScore(normalized, rdo.rodovia, 4);

  if (
    numberMatch?.[1] &&
    normalizeText(rdo.numeroRdo) === numberMatch[1]
  ) {
    score += 8;
  }

  if (obra) {
    score += Math.max(
      0,
      scoreObra(obra, question, tokenList) - 2,
    );
  }

  const joined = normalizeText(
    [
      rdo.numeroRdo,
      rdo.dataRdo,
      rdo.cidade,
      rdo.contrato,
      rdo.rodovia,
      obra?.nome,
      obra?.cidade,
      obra?.codigoContrato,
      obra?.codigoCw,
    ]
      .filter(Boolean)
      .join(" "),
  );

  for (const term of tokenList) {
    if (joined.includes(term)) {
      score += 1;
    }
  }

  return score;
}

function latestRdoForObra(
  snapshot: StaviaSnapshot,
  obraId: string,
): StaviaSnapshotRdo | null {
  return (
    snapshot.rdos
      .filter((rdo) => rdo.obraId === obraId)
      .sort((left, right) =>
        (right.dataRdo ?? "").localeCompare(
          left.dataRdo ?? "",
        ),
      )[0] ?? null
  );
}

function findById<T extends { id: string }>(
  values: T[],
  id: string | null | undefined,
): T | null {
  if (!id) {
    return null;
  }

  return values.find((value) => value.id === id) ?? null;
}

function resolveContext(
  snapshot: StaviaSnapshot,
  question: string,
  context: StaviaLocalContext,
): ResolvedContext {
  const obraById = new Map(
    snapshot.obras.map((obra) => [obra.id, obra]),
  );

  const activeRdo =
    findById(snapshot.rdos, context.activeRdoId) ??
    findById(snapshot.rdos, context.lastRdoId);
  if (activeRdo) {
    const obra = obraById.get(activeRdo.obraId) ?? null;
    return {
      obra,
      rdo: activeRdo,
      rdos: snapshot.rdos.filter(
        (rdo) => rdo.obraId === activeRdo.obraId,
      ),
      ambiguousLabels: [],
    };
  }

  const activeObra =
    findById(snapshot.obras, context.activeObraId) ??
    findById(snapshot.obras, context.lastObraId);
  if (activeObra) {
    return {
      obra: activeObra,
      rdo: latestRdoForObra(snapshot, activeObra.id),
      rdos: snapshot.rdos.filter(
        (rdo) => rdo.obraId === activeObra.id,
      ),
      ambiguousLabels: [],
    };
  }

  if (snapshot.rdos.length === 1) {
    const rdo = snapshot.rdos[0];
    return {
      obra: obraById.get(rdo.obraId) ?? null,
      rdo,
      rdos: [rdo],
      ambiguousLabels: [],
    };
  }

  if (snapshot.obras.length === 1) {
    const obra = snapshot.obras[0];
    return {
      obra,
      rdo: latestRdoForObra(snapshot, obra.id),
      rdos: snapshot.rdos.filter(
        (rdo) => rdo.obraId === obra.id,
      ),
      ambiguousLabels: [],
    };
  }

  const tokenList = terms(question);
  const scoredRdos = snapshot.rdos
    .map((rdo) => ({
      rdo,
      obra: obraById.get(rdo.obraId),
      score: scoreRdo(
        rdo,
        obraById.get(rdo.obraId),
        question,
        tokenList,
      ),
    }))
    .filter((item) => item.score > 0)
    .sort((left, right) => right.score - left.score);

  const bestRdo = scoredRdos[0];
  const secondRdo = scoredRdos[1];
  if (bestRdo && bestRdo.score >= 5) {
    if (
      secondRdo &&
      secondRdo.score >= bestRdo.score - 1
    ) {
      return {
        obra: null,
        rdo: null,
        rdos: [],
        ambiguousLabels: scoredRdos.slice(0, 4).map(
          (item) =>
            `RDO ${text(item.rdo.numeroRdo) || item.rdo.id}${
              item.rdo.cidade
                ? ` · ${item.rdo.cidade}`
                : ""
            }`,
        ),
      };
    }

    return {
      obra: bestRdo.obra ?? null,
      rdo: bestRdo.rdo,
      rdos: snapshot.rdos.filter(
        (rdo) => rdo.obraId === bestRdo.rdo.obraId,
      ),
      ambiguousLabels: [],
    };
  }

  const scoredObras = snapshot.obras
    .map((obra) => ({
      obra,
      score: scoreObra(obra, question, tokenList),
    }))
    .filter((item) => item.score > 0)
    .sort((left, right) => right.score - left.score);

  const bestObra = scoredObras[0];
  const secondObra = scoredObras[1];
  if (bestObra && bestObra.score >= 4) {
    if (
      secondObra &&
      secondObra.score >= bestObra.score - 1
    ) {
      return {
        obra: null,
        rdo: null,
        rdos: [],
        ambiguousLabels: scoredObras.slice(0, 4).map(
          (item) =>
            text(item.obra.nome) ||
            text(item.obra.codigoContrato) ||
            item.obra.id,
        ),
      };
    }

    return {
      obra: bestObra.obra,
      rdo: latestRdoForObra(snapshot, bestObra.obra.id),
      rdos: snapshot.rdos.filter(
        (rdo) => rdo.obraId === bestObra.obra.id,
      ),
      ambiguousLabels: [],
    };
  }

  return {
    obra: null,
    rdo: null,
    rdos: [],
    ambiguousLabels: [],
  };
}

function resolveWorksiteContext(
  snapshot: StaviaSnapshot,
  question: string,
  context: StaviaLocalContext,
): ResolvedContext {
  const obraById = new Map(
    snapshot.obras.map((obra) => [obra.id, obra]),
  );
  const activeRdo =
    findById(snapshot.rdos, context.activeRdoId) ??
    findById(snapshot.rdos, context.lastRdoId);

  if (activeRdo) {
    const obra = obraById.get(activeRdo.obraId) ?? null;
    return {
      obra,
      rdo: activeRdo,
      rdos: snapshot.rdos.filter(
        (rdo) => rdo.obraId === activeRdo.obraId,
      ),
      ambiguousLabels: [],
    };
  }

  const activeObra =
    findById(snapshot.obras, context.activeObraId) ??
    findById(snapshot.obras, context.lastObraId);

  if (activeObra) {
    return {
      obra: activeObra,
      rdo: latestRdoForObra(snapshot, activeObra.id),
      rdos: snapshot.rdos.filter(
        (rdo) => rdo.obraId === activeObra.id,
      ),
      ambiguousLabels: [],
    };
  }

  if (snapshot.obras.length === 1) {
    const obra = snapshot.obras[0];
    return {
      obra,
      rdo: latestRdoForObra(snapshot, obra.id),
      rdos: snapshot.rdos.filter(
        (rdo) => rdo.obraId === obra.id,
      ),
      ambiguousLabels: [],
    };
  }

  const tokenList = terms(question);
  const scoredObras = snapshot.obras
    .map((obra) => ({
      obra,
      score: scoreObra(obra, question, tokenList),
    }))
    .filter((item) => item.score > 0)
    .sort((left, right) => right.score - left.score);

  const bestObra = scoredObras[0];
  const secondObra = scoredObras[1];

  if (!bestObra || bestObra.score < 4) {
    return {
      obra: null,
      rdo: null,
      rdos: [],
      ambiguousLabels: [],
    };
  }

  if (secondObra && secondObra.score >= bestObra.score - 1) {
    return {
      obra: null,
      rdo: null,
      rdos: [],
      ambiguousLabels: scoredObras.slice(0, 4).map(
        (item) =>
          text(item.obra.nome) ||
          text(item.obra.codigoContrato) ||
          item.obra.id,
      ),
    };
  }

  return {
    obra: bestObra.obra,
    rdo: latestRdoForObra(snapshot, bestObra.obra.id),
    rdos: snapshot.rdos.filter(
      (rdo) => rdo.obraId === bestObra.obra.id,
    ),
    ambiguousLabels: [],
  };
}

function answer(
  answerText: string,
  intent: LocalIntent,
  options?: {
    confidence?: StaviaConfidence;
    sources?: StaviaEvidence[];
    warnings?: string[];
    insufficientData?: boolean;
    metadata?: Record<string, unknown>;
  },
): StaviaConsultaResponse {
  const staviaAnswer: StaviaAnswer = {
    answer: answerText,
    confidence: options?.confidence ?? "ALTA",
    answerType: options?.insufficientData
      ? "INFORMACAO_INSUFICIENTE"
      : "FATO",
    sources: options?.sources ?? [],
    insufficientData: options?.insufficientData ?? false,
    warnings: options?.warnings ?? [],
    metadata: options?.metadata ?? {
      origemResposta: "SNAPSHOT_LOCAL_STAVIA",
    },
  };

  return {
    answer: staviaAnswer,
    intent,
    consultedKnowledgeSources: {
      "snapshot-local-stavia": "STAVIA-OFFLINE-0.1.0",
    },
    knowledgeWarnings: [],
  };
}

function contextMissingAnswer(
  resolved: ResolvedContext,
): StaviaConsultaResponse {
  if (resolved.ambiguousLabels.length > 0) {
    return answer(
      `Encontrei mais de um contexto possível. Qual deles você quer consultar?\n\n${resolved.ambiguousLabels
        .map((label) => `- ${label}`)
        .join("\n")}`,
      "DESCONHECIDA",
      {
        confidence: "MEDIA",
        insufficientData: true,
      },
    );
  }

  return answer(
    "Não encontrei contexto suficiente para identificar a obra ou o RDO. Informe o número do RDO, cidade, contrato/CW ou nome da obra.",
    "DESCONHECIDA",
    {
      confidence: "INDETERMINADA",
      insufficientData: true,
    },
  );
}

function sourceForRdo(rdo: StaviaSnapshotRdo): StaviaEvidence {
  return {
    type: "RDO_LOCAL",
    id: rdo.id,
    summary: `RDO ${text(rdo.numeroRdo) || rdo.id}`,
    updatedAt: rdo.updatedAt,
    validated: rdo.status === "ENVIADO",
    attributes: {
      numeroRdo: rdo.numeroRdo,
      obraId: rdo.obraId,
      dataRdo: rdo.dataRdo,
      cidade: rdo.cidade,
      contrato: rdo.contrato,
      rodovia: rdo.rodovia,
      cliente: rdo.cliente,
      kmInicialProgramado: rdo.kmInicialProgramado,
      kmFinalProgramado: rdo.kmFinalProgramado,
      kmInicialInterditado: rdo.kmInicialInterditado,
      kmFinalInterditado: rdo.kmFinalInterditado,
      turno: rdo.turno,
      horaInicio: rdo.horaInicio,
      horaFim: rdo.horaFim,
      preenchidoPor: rdo.preenchidoPor,
      apontadorRdo: rdo.apontadorRdo,
      encarregadoObra: rdo.encarregadoObra,
      fiscalizacaoCampo: rdo.fiscalizacaoCampo,
      status: rdo.status,
    },
  };
}

function sourceForPdor(pdor: StaviaSnapshotPdor): StaviaEvidence {
  return {
    type: "PDOR_LOCAL",
    id: pdor.snapshotId ?? `PDOR:${pdor.obraId}`,
    summary: "Snapshot PDOR salvo no dispositivo",
    updatedAt: pdor.dataExecucao,
    validated: pdor.statusExecucao === "SUCCESS",
    attributes: { ...pdor },
  };
}

function selectedRdos(
  resolved: ResolvedContext,
): StaviaSnapshotRdo[] {
  if (resolved.rdo) {
    return [resolved.rdo];
  }

  return resolved.rdos;
}

function obraLabel(
  obra: StaviaSnapshotObra | null | undefined,
  fallbackRdo?: StaviaSnapshotRdo,
): string {
  return (
    text(obra?.nome) ||
    text(obra?.codigoCw) ||
    text(obra?.codigoContrato) ||
    text(obra?.codigoInterno) ||
    text(fallbackRdo?.contrato) ||
    text(fallbackRdo?.cidade) ||
    "Obra sem nome"
  );
}

function rdoCity(
  rdo: StaviaSnapshotRdo,
  obra: StaviaSnapshotObra | undefined,
): string {
  return text(rdo.cidade) || text(obra?.cidade);
}

function cityMatches(
  snapshot: StaviaSnapshot,
  question: string,
): string[] {
  const normalizedQuestion = normalizeText(question);
  const obraById = new Map(
    snapshot.obras.map((obra) => [obra.id, obra]),
  );
  const cities = unique(
    snapshot.rdos.map((rdo) =>
      rdoCity(rdo, obraById.get(rdo.obraId)),
    ),
  );

  return cities.filter((city) =>
    normalizedQuestion.includes(normalizeText(city)),
  );
}

function uniqueRdosById(
  rdos: StaviaSnapshotRdo[],
): StaviaSnapshotRdo[] {
  const byId = new Map<string, StaviaSnapshotRdo>();

  for (const rdo of rdos) {
    byId.set(rdo.id, rdo);
  }

  return Array.from(byId.values());
}

function bulletList(values: string[]): string {
  return values.map((value) => `- ${value}`).join("\n");
}

function limitedBulletList(
  values: string[],
  maxItems = 20,
): string {
  const displayed = values.slice(0, maxItems);
  const remaining = values.length - displayed.length;

  return [
    ...displayed.map((value) => `- ${value}`),
    remaining > 0
      ? `- ...mais ${remaining} registro${remaining === 1 ? "" : "s"}`
      : "",
  ]
    .filter(Boolean)
    .join("\n");
}

function collaboratorNames(
  rdos: StaviaSnapshotRdo[],
): string[] {
  return unique(
    rdos.flatMap((rdo) => [
      rdo.encarregadoObra,
      rdo.apontadorRdo,
      rdo.preenchidoPor,
      rdo.fiscalizacaoCampo,
      ...rdo.maoObra.map((item) => item.nomeColaborador),
      ...rdo.alocacoesColaboradores.map((item) => item.equipe),
    ]),
  );
}

function rdoResponsibleEntries(rdo: StaviaSnapshotRdo): string[] {
  return [
    [rdo.encarregadoObra, "Encarregado da obra"],
    [rdo.apontadorRdo, "Apontador do RDO"],
    [rdo.preenchidoPor, "Preenchido por"],
    [rdo.fiscalizacaoCampo, "Fiscalização de campo"],
  ]
    .map(([name, role]) =>
      [text(name), role].filter(Boolean).join(" · "),
    )
    .filter(Boolean);
}

function collaboratorEntries(
  rdos: StaviaSnapshotRdo[],
): string[] {
  return unique(
    rdos.flatMap((rdo) => [
      ...rdoResponsibleEntries(rdo),
      ...rdo.maoObra.map((item) =>
        [
          text(item.nomeColaborador),
          text(item.cargo),
          text(item.tipoVinculo),
          item.quantidade ? `qtd. ${item.quantidade}` : "",
          timeRange(item.horaInicio, item.horaFim)
            ? `horário ${timeRange(item.horaInicio, item.horaFim)}`
            : "",
          text(item.observacoes)
            ? `obs. ${text(item.observacoes)}`
            : "",
        ]
          .filter(Boolean)
          .join(" · "),
      ),
      ...rdo.alocacoesColaboradores.map((item) =>
        [
          text(item.nomeColaborador),
          text(item.equipe),
          text(item.funcao),
          text(item.servicoNome),
          text(item.turno)
            ? `turno ${readableStatus(item.turno)}`
            : "",
          timeRange(item.horaInicio, item.horaFim)
            ? `horário ${timeRange(item.horaInicio, item.horaFim)}`
            : "",
          item.percentualDia
            ? `dia ${item.percentualDia}`
            : "",
          text(item.observacoes)
            ? `obs. ${text(item.observacoes)}`
            : "",
        ]
          .filter(Boolean)
          .join(" · "),
      ),
    ]),
  );
}

function programacaoCollaboratorEntries(
  programacoes: StaviaSnapshotProgramacao[],
): string[] {
  return unique(
    programacoes.flatMap((programacao) =>
      [
        [
          programacao.encarregado,
          "Encarregado da programação",
          programacao.dataProgramacao,
        ],
        [
          programacao.engenheiro,
          "Engenheiro",
          programacao.dataProgramacao,
        ],
      ].map(([name, role, date]) =>
        [text(name), role, date ? `data ${date}` : ""]
          .filter(Boolean)
          .join(" · "),
      ),
    ),
  );
}

function equipmentNames(
  rdos: StaviaSnapshotRdo[],
): string[] {
  return unique(
    rdos.flatMap((rdo) =>
      rdo.equipamentos.map((item) =>
        [
          text(item.prefixo),
          text(item.descricao) || text(item.tipoEquipamento),
          text(item.tipoVinculo),
          item.quantidade ? `qtd. ${item.quantidade}` : "",
          timeRange(item.horaInicio, item.horaFim)
            ? `horário ${timeRange(item.horaInicio, item.horaFim)}`
            : "",
          text(item.observacoes)
            ? `obs. ${text(item.observacoes)}`
            : "",
        ]
          .filter(Boolean)
          .join(" · "),
      ),
    ),
  );
}

function materialNames(
  rdos: StaviaSnapshotRdo[],
): string[] {
  return unique(
    rdos.flatMap((rdo) =>
      rdo.materiais.map((item) =>
        [
          text(item.materialNome),
          item.quantidadeAplicada
            ? `${item.quantidadeAplicada}${
                item.unidade ? ` ${item.unidade}` : ""
              }`
            : "",
        ]
          .filter(Boolean)
          .join(" · "),
      ),
    ),
  );
}

function isResponsibleRole(
  value: string | null | undefined,
): boolean {
  const normalized = normalizeText(value);
  return (
    normalized.includes("encarregado") ||
    normalized.includes("responsavel") ||
    normalized.includes("supervisor") ||
    normalized.includes("lider")
  );
}

function sameServiceName(
  left: string | null | undefined,
  right: string | null | undefined,
): boolean {
  const normalizedLeft = normalizeText(left);
  const normalizedRight = normalizeText(right);

  return (
    normalizedLeft.length > 0 &&
    normalizedRight.length > 0 &&
    (normalizedLeft === normalizedRight ||
      normalizedLeft.includes(normalizedRight) ||
      normalizedRight.includes(normalizedLeft))
  );
}

function serviceQueryTerms(question: string): string[] {
  const genericTerms = new Set([
    "servico",
    "servicos",
    "tipo",
    "tipos",
    "feito",
    "feita",
    "feitos",
    "feitas",
    "executado",
    "executada",
    "executados",
    "executadas",
    "encarregado",
    "responsavel",
    "responsaveis",
    "obra",
  ]);

  return terms(question).filter((term) => !genericTerms.has(term));
}

function responsibleLabelsForService(
  rdo: StaviaSnapshotRdo,
  serviceName: string,
): string[] {
  const related = rdo.alocacoesColaboradores.filter((item) =>
    sameServiceName(item.servicoNome, serviceName),
  );
  const responsible = related.filter((item) =>
    isResponsibleRole(item.funcao),
  );
  const source = responsible.length > 0 ? responsible : related;

  return unique(
    source.map((item) =>
      [
        text(item.nomeColaborador) ||
          text(item.equipe) ||
          text(item.colaboradorId),
        text(item.funcao),
      ]
        .filter(Boolean)
        .join(" - "),
    ),
  );
}

function serviceEntries(
  rdos: StaviaSnapshotRdo[],
  question = "",
): string[] {
  const filterTerms = serviceQueryTerms(question);
  const entries: Array<{ searchText: string; label: string }> = [];

  for (const rdo of uniqueRdosById(rdos)) {
    const rdoLabel = [
      rdo.numeroRdo ? `RDO ${rdo.numeroRdo}` : "RDO sem número",
      rdo.dataRdo,
    ]
      .filter(Boolean)
      .join(" · ");

    const services =
      (rdo.servicosExecutados ?? []).length > 0
        ? rdo.servicosExecutados ?? []
        : unique(
            rdo.alocacoesColaboradores.map(
              (item) => item.servicoNome,
            ),
          ).map((servicoNome) => ({
            servicoNome,
            quantidadeExecutada: null,
            unidade: null,
            trechoInicial: null,
            trechoFinal: null,
            localizacao: null,
            turno: null,
            statusValidacao: null,
            observacoes: null,
          }));

    for (const service of services) {
      const serviceName = text(service.servicoNome);
      if (!serviceName) {
        continue;
      }

      const responsaveis = responsibleLabelsForService(
        rdo,
        serviceName,
      );
      const quantidade = service.quantidadeExecutada
        ? `${service.quantidadeExecutada}${
            service.unidade ? ` ${service.unidade}` : ""
          }`
        : "";
      const trecho =
        service.trechoInicial || service.trechoFinal
          ? [service.trechoInicial, service.trechoFinal]
              .filter(Boolean)
              .join(" a ")
          : text(service.localizacao);

      const label = [
        rdoLabel,
        serviceName,
        quantidade,
        trecho ? `trecho ${trecho}` : "",
        service.turno
          ? `turno ${readableStatus(service.turno)}`
          : "",
        service.statusValidacao
          ? readableStatus(service.statusValidacao)
          : "",
        responsaveis.length > 0
          ? `responsável: ${responsaveis.join(", ")}`
          : "",
      ]
        .filter(Boolean)
        .join(" · ");

      entries.push({
        searchText: normalizeText(
          [serviceName, label, responsaveis.join(" ")].join(" "),
        ),
        label,
      });
    }
  }

  const filtered =
    filterTerms.length === 0
      ? entries
      : entries.filter((entry) =>
          filterTerms.every((term) =>
            entry.searchText.includes(term),
          ),
        );

  return unique(filtered.map((entry) => entry.label));
}

function serviceObservationEntries(
  snapshot: StaviaSnapshot,
  resolved: ResolvedContext,
): string[] {
  const rdos = selectedRdos(resolved);
  const rdoLabel = (rdo: StaviaSnapshotRdo) =>
    rdo.numeroRdo ? `RDO ${rdo.numeroRdo}` : "RDO sem número";

  const serviceObservations = rdos.flatMap((rdo) =>
    (rdo.servicosExecutados ?? [])
      .map((service) => {
        const observation = text(service.observacoes);
        if (!observation) {
          return "";
        }

        const serviceName =
          text(service.servicoNome) || "Serviço sem nome";
        const trecho =
          service.trechoInicial || service.trechoFinal
            ? [service.trechoInicial, service.trechoFinal]
                .filter(Boolean)
                .join(" a ")
            : text(service.localizacao);
        return [
          rdoLabel(rdo),
          serviceName,
          trecho ? `trecho ${trecho}` : "",
          observation,
        ]
          .filter(Boolean)
          .join(" · ");
      })
      .filter(Boolean),
  );

  const activityObservations = rdos.flatMap((rdo) =>
    (rdo.controlesGeometricos ?? [])
      .map((control) => {
        const observation = text(control.atividadeObservacoes);
        if (!observation) {
          return "";
        }

        const trecho =
          control.kmInicial || control.kmFinal
            ? [control.kmInicial, control.kmFinal]
                .filter(Boolean)
                .join(" a ")
            : text(control.subtrecho);

        return [
          rdoLabel(rdo),
          trecho ? `trecho ${trecho}` : "",
          observation,
        ]
          .filter(Boolean)
          .join(" · ");
      })
      .filter(Boolean),
  );

  const materialObservations = rdos.flatMap((rdo) =>
    (rdo.materiais ?? [])
      .map((material) => {
        const observation = text(material.observacoes);
        if (!observation) {
          return "";
        }

        return [
          rdoLabel(rdo),
          text(material.materialNome) || "Material sem nome",
          observation,
        ]
          .filter(Boolean)
          .join(" · ");
      })
      .filter(Boolean),
  );

  const collaboratorObservations = rdos.flatMap((rdo) =>
    (rdo.maoObra ?? [])
      .map((item) => {
        const observation = text(item.observacoes);
        if (!observation) {
          return "";
        }

        return [
          rdoLabel(rdo),
          "Mão de obra",
          text(item.nomeColaborador) || text(item.cargo),
          timeRange(item.horaInicio, item.horaFim)
            ? `horário ${timeRange(item.horaInicio, item.horaFim)}`
            : "",
          observation,
        ]
          .filter(Boolean)
          .join(" · ");
      })
      .filter(Boolean),
  );

  const equipmentObservations = rdos.flatMap((rdo) =>
    (rdo.equipamentos ?? [])
      .map((item) => {
        const observation = text(item.observacoes);
        if (!observation) {
          return "";
        }

        return [
          rdoLabel(rdo),
          "Equipamento",
          text(item.prefixo) ||
            text(item.descricao) ||
            text(item.tipoEquipamento),
          timeRange(item.horaInicio, item.horaFim)
            ? `horário ${timeRange(item.horaInicio, item.horaFim)}`
            : "",
          observation,
        ]
          .filter(Boolean)
          .join(" · ");
      })
      .filter(Boolean),
  );

  const allocationObservations = rdos.flatMap((rdo) =>
    (rdo.alocacoesColaboradores ?? [])
      .map((item) => {
        const observation = text(item.observacoes);
        if (!observation) {
          return "";
        }

        return [
          rdoLabel(rdo),
          "Rateio de colaborador",
          text(item.nomeColaborador) ||
            text(item.equipe) ||
            text(item.funcao),
          timeRange(item.horaInicio, item.horaFim)
            ? `horário ${timeRange(item.horaInicio, item.horaFim)}`
            : "",
          observation,
        ]
          .filter(Boolean)
          .join(" · ");
      })
      .filter(Boolean),
  );

  const rdoObservations = rdos
    .map((rdo) =>
      [rdoLabel(rdo), text(rdo.observacoes)]
        .filter(Boolean)
        .join(" · "),
    )
    .filter(Boolean);

  const programacaoObservations = programacoesForResolved(
    snapshot,
    resolved,
  )
    .map((programacao) =>
      [
        programacao.dataProgramacao
          ? `Programação ${programacao.dataProgramacao}`
          : "Programação",
        text(programacao.observacoes),
      ]
        .filter(Boolean)
        .join(" · "),
    )
    .filter(Boolean);

  return unique([
    ...serviceObservations,
    ...activityObservations,
    ...materialObservations,
    ...collaboratorObservations,
    ...equipmentObservations,
    ...allocationObservations,
    ...rdoObservations,
    ...programacaoObservations,
  ]);
}

function answerServices(
  resolved: ResolvedContext,
  question: string,
): StaviaConsultaResponse {
  const values = serviceEntries(selectedRdos(resolved), question);

  if (values.length === 0) {
    return answer(
      "Não encontrei serviços executados para este contexto.",
      "SERVICOS_DA_OBRA",
      {
        confidence: "MEDIA",
        insufficientData: true,
      },
    );
  }

  return answer(
    `Serviços encontrados:\n\n${limitedBulletList(values)}`,
    "SERVICOS_DA_OBRA",
  );
}

function answerServiceObservations(
  snapshot: StaviaSnapshot,
  resolved: ResolvedContext,
): StaviaConsultaResponse {
  const values = serviceObservationEntries(snapshot, resolved);

  if (values.length === 0) {
    return answer(
      "Não encontrei observações registradas para este contexto.",
      "OBSERVACOES_SERVICO",
      {
        confidence: "MEDIA",
        insufficientData: true,
        sources: selectedRdos(resolved).map(sourceForRdo),
      },
    );
  }

  return answer(
    `Observações encontradas:\n\n${limitedBulletList(values)}`,
    "OBSERVACOES_SERVICO",
    {
      confidence: "ALTA",
      sources: selectedRdos(resolved).map(sourceForRdo),
    },
  );
}

function answerCollaborators(
  snapshot: StaviaSnapshot,
  resolved: ResolvedContext,
): StaviaConsultaResponse {
  const rdos = selectedRdos(resolved);
  const names = unique([
    ...collaboratorEntries(rdos),
    ...programacaoCollaboratorEntries(
      programacoesForResolved(snapshot, resolved),
    ),
  ]);

  if (names.length === 0) {
    return answer(
      "Não encontrei colaboradores registrados para este contexto.",
      "COLABORADORES_DA_OBRA",
      {
        confidence: "MEDIA",
        insufficientData: true,
      },
    );
  }

  return answer(
    `${resolved.rdo ? "Equipe/responsáveis encontrados neste RDO" : "Equipe/responsáveis encontrados nesta obra"}:\n\n${limitedBulletList(names)}`,
    "COLABORADORES_DA_OBRA",
  );
}

function answerEquipment(
  resolved: ResolvedContext,
): StaviaConsultaResponse {
  const values = equipmentNames(selectedRdos(resolved));

  if (values.length === 0) {
    return answer(
      "Não encontrei equipamentos registrados para este contexto.",
      "EQUIPAMENTOS",
      {
        confidence: "MEDIA",
        insufficientData: true,
      },
    );
  }

  return answer(
    `Equipamentos encontrados:\n\n${limitedBulletList(values)}`,
    "EQUIPAMENTOS",
  );
}

function answerMaterials(
  resolved: ResolvedContext,
): StaviaConsultaResponse {
  const values = materialNames(selectedRdos(resolved));

  if (values.length === 0) {
    return answer(
      "Não encontrei materiais registrados para este contexto.",
      "MATERIAIS",
      {
        confidence: "MEDIA",
        insufficientData: true,
      },
    );
  }

  return answer(
    `Materiais encontrados:\n\n${limitedBulletList(values)}`,
    "MATERIAIS",
  );
}

function answerShift(
  resolved: ResolvedContext,
): StaviaConsultaResponse {
  const rdo = resolved.rdo ?? resolved.rdos[0];
  const shift = readableStatus(rdo?.turno);

  if (!shift) {
    return answer(
      "Não encontrei turno registrado para este contexto.",
      "TURNO",
      {
        confidence: "MEDIA",
        insufficientData: true,
      },
    );
  }

  return answer(`Turno registrado: ${shift}.`, "TURNO");
}

function locationParts(
  resolved: ResolvedContext,
): string[] {
  const obra = resolved.obra;
  const rdo = resolved.rdo ?? resolved.rdos[0];

  return [
    rdo?.cidade || obra?.cidade
      ? `Cidade: ${rdo?.cidade ?? obra?.cidade}`
      : "",
    rdo?.uf || obra?.uf ? `UF: ${rdo?.uf ?? obra?.uf}` : "",
    rdo?.rodovia || obra?.rodovia
      ? `Rodovia: ${rdo?.rodovia ?? obra?.rodovia}`
      : "",
    rdo?.contrato || obra?.codigoContrato
      ? `Contrato: ${rdo?.contrato ?? obra?.codigoContrato}`
      : "",
    obra?.codigoCw ? `CW: ${obra.codigoCw}` : "",
  ].filter(Boolean);
}

function answerLocation(
  resolved: ResolvedContext,
): StaviaConsultaResponse {
  const parts = locationParts(resolved);

  if (parts.length === 0) {
    return answer(
      "Não encontrei localização estruturada para este contexto.",
      "LOCALIZACAO_OBRA",
      {
        confidence: "MEDIA",
        insufficientData: true,
      },
    );
  }

  return answer(parts.join("\n"), "LOCALIZACAO_OBRA");
}

function formatValue(
  value: number | string | null | undefined,
  unit?: string,
): string {
  if (value === null || value === undefined || value === "") {
    return "";
  }

  const textValue =
    typeof value === "number"
      ? new Intl.NumberFormat("pt-BR", {
          maximumFractionDigits: 3,
        }).format(value)
      : String(value).trim();

  return unit ? `${textValue} ${unit}` : textValue;
}

function sumValues(
  values: Array<number | string | null | undefined>,
): number | null {
  let total = 0;
  let found = false;

  for (const value of values) {
    if (value === null || value === undefined || value === "") {
      continue;
    }

    const parsed =
      typeof value === "number"
        ? value
        : Number(String(value).replace(",", "."));

    if (Number.isNaN(parsed)) {
      continue;
    }

    total += parsed;
    found = true;
  }

  return found ? Number(total.toFixed(3)) : null;
}

function programacoesForResolved(
  snapshot: StaviaSnapshot,
  resolved: ResolvedContext,
): StaviaSnapshotProgramacao[] {
  const rdos = selectedRdos(resolved);
  const rdoIds = new Set(rdos.map((rdo) => rdo.id));
  const programacaoIds = new Set(
    rdos
      .map((rdo) => rdo.programacaoId)
      .filter((id): id is string => Boolean(id)),
  );
  const obraId = resolved.obra?.id ?? resolved.rdo?.obraId;
  const dates = new Set(
    rdos
      .map((rdo) => rdo.dataRdo)
      .filter((date): date is string => Boolean(date)),
  );

  return (snapshot.programacoes ?? [])
    .filter((programacao) => {
      if (programacaoIds.has(programacao.id)) {
        return true;
      }

      if (
        programacao.rdoId &&
        rdoIds.has(programacao.rdoId)
      ) {
        return true;
      }

      const dataProgramacao = programacao.dataProgramacao;

      return (
        Boolean(obraId) &&
        programacao.obraId === obraId &&
        dataProgramacao !== null &&
        dates.has(dataProgramacao)
      );
    })
    .sort((left, right) =>
      (right.dataProgramacao ?? "").localeCompare(
        left.dataProgramacao ?? "",
      ),
    );
}

function firstProgramacaoValue(
  programacoes: StaviaSnapshotProgramacao[],
  picker: (
    programacao: StaviaSnapshotProgramacao,
  ) => number | string | null | undefined,
): number | string | null {
  for (const programacao of programacoes) {
    const value = picker(programacao);
    if (value !== null && value !== undefined && value !== "") {
      return value;
    }
  }

  return null;
}

function answerSingleRdoValue(
  label: string,
  value: number | string | null | undefined,
  resolved: ResolvedContext,
  unit?: string,
): StaviaConsultaResponse {
  const formatted = formatValue(value, unit);

  if (!formatted) {
    return answer(
      `${label}: não encontrei esse campo preenchido no contexto selecionado.`,
      "DETALHE_RDO",
      {
        confidence: "MEDIA",
        insufficientData: true,
        sources: selectedRdos(resolved).map(sourceForRdo),
      },
    );
  }

  return answer(`${label}: ${formatted}.`, "DETALHE_RDO", {
    confidence: "ALTA",
    sources: selectedRdos(resolved).map(sourceForRdo),
  });
}

function programacaoLine(
  programacao: StaviaSnapshotProgramacao,
): string {
  const trecho =
    programacao.kmInicial || programacao.kmFinal
      ? `km ${[programacao.kmInicial, programacao.kmFinal]
          .filter(Boolean)
          .join(" a ")}`
      : "";

  return [
    programacao.dataProgramacao,
    text(programacao.servico) || "serviço não informado",
    trecho,
    programacao.periodo
      ? `período ${readableStatus(programacao.periodo)}`
      : "",
    programacao.encarregado
      ? `encarregado: ${programacao.encarregado}`
      : "",
    programacao.areaM2
      ? `área ${formatValue(programacao.areaM2, "m²")}`
      : "",
    programacao.volumeM3
      ? `volume ${formatValue(programacao.volumeM3, "m³")}`
      : "",
  ]
    .filter(Boolean)
    .join(" · ");
}

function answerRdoDetail(
  snapshot: StaviaSnapshot,
  resolved: ResolvedContext,
  question: string,
): StaviaConsultaResponse {
  const rdo = resolved.rdo ?? resolved.rdos[0];
  if (!rdo) {
    return contextMissingAnswer(resolved);
  }

  const normalized = normalizeText(question);
  const programacoes = programacoesForResolved(snapshot, resolved);
  const rdos = selectedRdos(resolved);

  if (
    hasAny(normalized, [
      "qual a data",
      "qual data",
      "data do rdo",
      "data deste rdo",
      "data desse rdo",
      "data operacional",
      "qual o dia",
      "qual dia",
      "dia do rdo",
      "quando foi o rdo",
      "quando foi esse rdo",
      "quando foi este rdo",
    ])
  ) {
    return answerSingleRdoValue(
      "Data do RDO",
      formatDateOnly(rdo.dataRdo),
      resolved,
    );
  }

  if (
    hasAny(normalized, ["trecho programado"]) ||
    (hasAny(normalized, ["programado"]) &&
      hasAny(normalized, ["trecho", "km"]))
  ) {
    if (hasAny(normalized, ["final", "fim"])) {
      return answerSingleRdoValue(
        "Trecho programado final",
        rdo.kmFinalProgramado ??
          firstProgramacaoValue(
            programacoes,
            (programacao) => programacao.kmFinal,
          ),
        resolved,
      );
    }

    if (hasAny(normalized, ["inicial", "inicio", "início"])) {
      return answerSingleRdoValue(
        "Trecho programado inicial",
        rdo.kmInicialProgramado ??
          firstProgramacaoValue(
            programacoes,
            (programacao) => programacao.kmInicial,
          ),
        resolved,
      );
    }

    const start =
      rdo.kmInicialProgramado ??
      firstProgramacaoValue(
        programacoes,
        (programacao) => programacao.kmInicial,
      );
    const end =
      rdo.kmFinalProgramado ??
      firstProgramacaoValue(
        programacoes,
        (programacao) => programacao.kmFinal,
      );

    return answerSingleRdoValue(
      "Trecho programado",
      [start, end].filter(Boolean).join(" a "),
      resolved,
    );
  }

  if (hasAny(normalized, ["preenchido por", "quem preencheu"])) {
    return answerSingleRdoValue(
      "Preenchido por",
      rdo.preenchidoPor,
      resolved,
    );
  }

  if (hasAny(normalized, ["apontador"])) {
    return answerSingleRdoValue(
      "Apontador do RDO",
      rdo.apontadorRdo ?? rdo.preenchidoPor,
      resolved,
    );
  }

  if (hasAny(normalized, ["encarregado"])) {
    return answerSingleRdoValue(
      "Encarregado",
      rdo.encarregadoObra ??
        firstProgramacaoValue(
          programacoes,
          (programacao) => programacao.encarregado,
        ),
      resolved,
    );
  }

  if (hasAny(normalized, ["fiscalizacao", "fiscalização", "fiscal"])) {
    return answerSingleRdoValue(
      "Fiscalização de campo",
      rdo.fiscalizacaoCampo,
      resolved,
    );
  }

  if (hasAny(normalized, ["periodo", "período", "turno"])) {
    return answerSingleRdoValue(
      "Período/turno",
      firstProgramacaoValue(
        programacoes,
        (programacao) => programacao.periodo,
      ) ?? rdo.turno,
      resolved,
    );
  }

  if (hasAny(normalized, ["area", "área"])) {
    const area =
      sumValues(rdos.flatMap((item) =>
        item.controlesGeometricos.map((controle) => controle.areaM2),
      )) ??
      sumValues(programacoes.map((programacao) => programacao.areaM2));

    return answerSingleRdoValue("Área", area, resolved, "m²");
  }

  if (hasAny(normalized, ["volume"])) {
    const volume =
      sumValues(rdos.flatMap((item) =>
        item.controlesGeometricos.map(
          (controle) => controle.volumeM3,
        ),
      )) ??
      sumValues(programacoes.map((programacao) => programacao.volumeM3));

    return answerSingleRdoValue("Volume", volume, resolved, "m³");
  }

  if (hasAny(normalized, ["extensao", "extensão", "comprimento"])) {
    const extensao =
      sumValues(rdos.flatMap((item) =>
        item.controlesGeometricos.map(
          (controle) => controle.comprimentoM,
        ),
      )) ??
      sumValues(
        programacoes.map((programacao) => programacao.extensaoM),
      );

    return answerSingleRdoValue(
      "Extensão/comprimento",
      extensao,
      resolved,
      "m",
    );
  }

  if (hasAny(normalized, ["tonelada", "massa"])) {
    const massa =
      sumValues(
        programacoes.map(
          (programacao) => programacao.toneladaMassa,
        ),
      ) ??
      sumValues(
        rdos.flatMap((item) =>
          item.materiais.map(
            (material) =>
              material.quantidadePrevista ??
              material.quantidadeAplicada,
          ),
        ),
      );

    return answerSingleRdoValue(
      "Massa prevista",
      massa,
      resolved,
      "t",
    );
  }

  if (hasAny(normalized, ["cap"])) {
    const cap =
      firstProgramacaoValue(
        programacoes,
        (programacao) => programacao.cap,
      ) ??
      firstProgramacaoValue(
        programacoes,
        (programacao) => programacao.tipoCap,
      );

    return answerSingleRdoValue("CAP", cap, resolved);
  }

  if (
    hasAny(normalized, [
      "programacao diaria",
      "programação diária",
      "programacao prevista",
      "programação prevista",
      "programacao semanal",
      "programação semanal",
    ])
  ) {
    if (programacoes.length === 0) {
      return answer(
        "Não encontrei programação diária vinculada ao contexto selecionado.",
        "DETALHE_RDO",
        {
          confidence: "MEDIA",
          insufficientData: true,
          sources: [sourceForRdo(rdo)],
        },
      );
    }

    return answer(
      `Programação prevista:\n\n${limitedBulletList(
        programacoes.map(programacaoLine),
        12,
      )}`,
      "DETALHE_RDO",
      {
        confidence: "ALTA",
        sources: [sourceForRdo(rdo)],
      },
    );
  }

  return answerSummary(resolved);
}

function answerComposite(
  snapshot: StaviaSnapshot,
  resolved: ResolvedContext,
  topics: CompositeTopic[],
): StaviaConsultaResponse {
  const sections: string[] = [];
  const rdos = selectedRdos(resolved);

  if (topics.includes("LOCALIZACAO_OBRA")) {
    const parts = locationParts(resolved);
    sections.push(
      parts.length > 0
        ? `Localização:\n${parts.join("\n")}`
        : "Localização: não encontrei cidade, UF, rodovia ou contrato estruturado para este contexto.",
    );
  }

  if (topics.includes("TURNO")) {
    const shift = readableStatus(
      (resolved.rdo ?? resolved.rdos[0])?.turno,
    );
    sections.push(
      shift
        ? `Turno: ${shift}.`
        : "Turno: não encontrei turno registrado para este contexto.",
    );
  }

  if (topics.includes("SERVICOS_DA_OBRA")) {
    const services = serviceEntries(rdos);
    sections.push(
      services.length > 0
        ? `Serviços:\n${limitedBulletList(services, 12)}`
        : "Serviços: nenhum registro encontrado para este contexto.",
    );
  }

  if (topics.includes("COLABORADORES_DA_OBRA")) {
    const collaborators = collaboratorEntries(rdos);
    sections.push(
      collaborators.length > 0
        ? `Colaboradores/equipe:\n${limitedBulletList(collaborators, 12)}`
        : "Colaboradores/equipe: nenhum registro encontrado para este contexto.",
    );
  }

  if (topics.includes("EQUIPAMENTOS")) {
    const equipment = equipmentNames(rdos);
    sections.push(
      equipment.length > 0
        ? `Equipamentos:\n${limitedBulletList(equipment, 12)}`
        : "Equipamentos: nenhum registro encontrado para este contexto.",
    );
  }

  if (topics.includes("MATERIAIS")) {
    const materials = materialNames(rdos);
    sections.push(
      materials.length > 0
        ? `Materiais:\n${limitedBulletList(materials, 12)}`
        : "Materiais: nenhum registro encontrado para este contexto.",
    );
  }

  if (topics.includes("CONTAGEM_RDOS_OBRA")) {
    const distinctRdos = uniqueRdosById(resolved.rdos);
    sections.push(
      distinctRdos.length > 0
        ? `RDOs da obra: ${distinctRdos.length}.`
        : "RDOs da obra: nenhum RDO encontrado para este contexto.",
    );
  }

  if (topics.includes("PDOR")) {
    const obraId = resolved.obra?.id ?? resolved.rdo?.obraId;
    const pdor = snapshot.pdors.find(
      (candidate) => candidate.obraId === obraId,
    );
    sections.push(
      pdor
        ? `PDOR (risco de receita abaixo do contrato): ${formatPercent(
            pdor.probabilidadeAbaixoContrato ??
              pdor.probabilidadeAbaixo95Pct ??
              pdor.scoreHeuristico,
          )}.${pdorRevenueSentence(pdor)}`
        : "PDOR: não encontrei previsão de receita salva para esta obra.",
    );
  }

  if (topics.includes("OCORRENCIAS")) {
    const occurrences = unique(
      rdos.map((rdo) => text(rdo.observacoes)).filter(Boolean),
    );
    sections.push(
      occurrences.length > 0
        ? `Ocorrências/observações:\n${limitedBulletList(occurrences, 8)}`
        : "Ocorrências/observações: nenhum registro encontrado para este contexto.",
    );
  }

  if (sections.length === 0) {
    return contextMissingAnswer(resolved);
  }

  return answer(sections.join("\n\n"), "CONSULTA_COMPOSTA", {
    confidence: "ALTA",
    sources: selectedRdos(resolved).map(sourceForRdo),
    metadata: {
      origemResposta: "SNAPSHOT_LOCAL_STAVIA",
      consultaComposta: true,
      topicos: topics,
    },
  });
}

function answerWorksByCity(
  snapshot: StaviaSnapshot,
  question: string,
): StaviaConsultaResponse {
  const obraById = new Map(
    snapshot.obras.map((obra) => [obra.id, obra]),
  );
  const matchedCities = cityMatches(snapshot, question);
  const normalizedQuestion = normalizeText(question);
  const asksSpecificCity =
    normalizedQuestion.includes("cidade") ||
    normalizedQuestion.includes("obra em") ||
    normalizedQuestion.includes("obras em") ||
    normalizedQuestion.includes("acontecendo em") ||
    matchedCities.length > 0;

  if (asksSpecificCity && matchedCities.length === 0) {
    return answer(
      "Não encontrei RDOs vinculados à cidade informada no snapshot local.",
      "OBRAS_POR_CIDADE",
      {
        confidence: "MEDIA",
        insufficientData: true,
      },
    );
  }

  const rdos = snapshot.rdos.filter((rdo) => {
    if (matchedCities.length === 0) {
      return true;
    }

    const city = normalizeText(rdoCity(rdo, obraById.get(rdo.obraId)));
    return matchedCities.some(
      (matchedCity) =>
        city === normalizeText(matchedCity) ||
        city.includes(normalizeText(matchedCity)) ||
        normalizeText(matchedCity).includes(city),
    );
  });

  if (rdos.length === 0) {
    return answer(
      "Não encontrei obras com RDOs registrados para esse filtro.",
      "OBRAS_POR_CIDADE",
      {
        confidence: "MEDIA",
        insufficientData: true,
      },
    );
  }

  const byObra = new Map<string, StaviaSnapshotRdo[]>();
  for (const rdo of rdos) {
    byObra.set(
      rdo.obraId,
      [...(byObra.get(rdo.obraId) ?? []), rdo],
    );
  }

  const lines = Array.from(byObra.entries())
    .map(([obraId, obraRdos]) => {
      const latest = uniqueRdosById(obraRdos).sort((left, right) =>
        (right.dataRdo ?? "").localeCompare(left.dataRdo ?? ""),
      )[0];
      const obra = obraById.get(obraId);
      const count = uniqueRdosById(obraRdos).length;
      const city =
        latest ? rdoCity(latest, obra) : text(obra?.cidade);
      const suffix = [
        city,
        `${count} RDO${count === 1 ? "" : "s"}`,
        latest?.dataRdo ? `último em ${latest.dataRdo}` : "",
      ]
        .filter(Boolean)
        .join(" · ");

      return `- ${obraLabel(obra, latest)}${suffix ? ` (${suffix})` : ""}`;
    })
    .sort((left, right) => left.localeCompare(right, "pt-BR"));

  const cityText =
    matchedCities.length === 1
      ? ` em ${matchedCities[0]}`
      : matchedCities.length > 1
        ? ` nas cidades ${matchedCities.join(", ")}`
        : "";

  return answer(
    `Obras com RDO registrado${cityText}:\n\n${lines.join("\n")}`,
    "OBRAS_POR_CIDADE",
    {
      metadata: {
        origemResposta: "SNAPSHOT_LOCAL_STAVIA",
        obraCount: byObra.size,
        rdoCount: uniqueRdosById(rdos).length,
      },
    },
  );
}

function answerRdoCount(
  resolved: ResolvedContext,
): StaviaConsultaResponse {
  const rdos = uniqueRdosById(resolved.rdos);

  if (!resolved.obra && !resolved.rdo) {
    return contextMissingAnswer(resolved);
  }

  if (rdos.length === 0) {
    return answer(
      "Não encontrei RDOs registrados para esta obra no snapshot local.",
      "CONTAGEM_RDOS_OBRA",
      {
        confidence: "MEDIA",
        insufficientData: true,
      },
    );
  }

  const latest = rdos.sort((left, right) =>
    (right.dataRdo ?? "").localeCompare(left.dataRdo ?? ""),
  )[0];
  const label = obraLabel(resolved.obra, latest);

  return answer(
    `Você tem ${rdos.length} RDO${rdos.length === 1 ? "" : "s"} registrado${rdos.length === 1 ? "" : "s"} para ${label}.${
      latest?.dataRdo ? ` Último RDO em ${latest.dataRdo}.` : ""
    }`,
    "CONTAGEM_RDOS_OBRA",
    {
      metadata: {
        origemResposta: "SNAPSHOT_LOCAL_STAVIA",
        obraId: resolved.obra?.id ?? latest?.obraId ?? null,
        rdoCount: rdos.length,
      },
    },
  );
}

function formatPercent(value: number | string | null): string {
  if (value === null || value === undefined || value === "") {
    return "não informado";
  }

  const numberValue =
    typeof value === "number"
      ? value
      : Number(String(value).replace(",", "."));

  if (Number.isNaN(numberValue)) {
    return String(value);
  }

  const percent =
    Math.abs(numberValue) <= 1 ? numberValue * 100 : numberValue;

  return new Intl.NumberFormat("pt-BR", {
    minimumFractionDigits: 0,
    maximumFractionDigits: 2,
  }).format(percent) + "%";
}

function formatRevenue(
  value: number | string | null | undefined,
): string | null {
  if (value === null || value === undefined || value === "") {
    return null;
  }

  const numberValue =
    typeof value === "number"
      ? value
      : Number(String(value).replace(",", "."));

  if (Number.isNaN(numberValue)) {
    return null;
  }

  return new Intl.NumberFormat("pt-BR", {
    style: "currency",
    currency: "BRL",
    maximumFractionDigits: 0,
  }).format(numberValue);
}

function pdorRevenueSentence(pdor: StaviaSnapshotPdor): string {
  const finalRevenue = formatRevenue(
    pdor.receitaEstimadaFinal ?? pdor.p50Receita,
  );

  if (!finalRevenue) {
    return "";
  }

  const p10 = formatRevenue(pdor.p10Receita);
  const p95 = formatRevenue(pdor.p95Receita);
  const range =
    p10 && p95 ? ` (faixa ${p10} a ${p95})` : "";

  return ` Receita prevista final: ${finalRevenue}${range}.`;
}

function answerPdor(
  snapshot: StaviaSnapshot,
  resolved: ResolvedContext,
): StaviaConsultaResponse {
  const obraId = resolved.obra?.id ?? resolved.rdo?.obraId;
  const pdor = snapshot.pdors.find(
    (candidate) => candidate.obraId === obraId,
  );

  if (!pdor) {
    return answer(
      "Não encontrei previsão de receita PDOR salva para esta obra.",
      "PDOR",
      {
        confidence: "MEDIA",
        insufficientData: true,
      },
    );
  }

  const score =
    pdor.probabilidadeAbaixoContrato ??
    pdor.probabilidadeAbaixo95Pct ??
    pdor.scoreHeuristico;
  const calibration = normalizeText(pdor.calibracao);
  const calibrationWarning =
    calibration === "not_calibrated" ||
    calibration === "nao calibrado"
      ? " O modelo ainda está marcado como não calibrado, então isso deve ser tratado como alerta heurístico, não como previsão final."
      : "";

  return answer(
    `PDOR desta obra — risco de receita abaixo do contrato: ${formatPercent(score)}.${pdorRevenueSentence(pdor)}${calibrationWarning}`,
    "PDOR",
    {
      confidence:
        calibrationWarning.length > 0 ? "MEDIA" : "ALTA",
      warnings:
        calibrationWarning.length > 0
          ? [
              "PDOR não calibrado: indicador heurístico/simulatório.",
            ]
          : [],
      sources: [sourceForPdor(pdor)],
    },
  );
}

function answerSummary(
  resolved: ResolvedContext,
): StaviaConsultaResponse {
  const rdo = resolved.rdo ?? resolved.rdos[0];
  if (!rdo) {
    return contextMissingAnswer(resolved);
  }

  const lines = [
    `RDO ${text(rdo.numeroRdo) || "sem número"}${rdo.dataRdo ? ` de ${rdo.dataRdo}` : ""}.`,
    rdo.cidade || rdo.rodovia
      ? `Local: ${[rdo.cidade, rdo.rodovia]
          .filter(Boolean)
          .join(" · ")}.`
      : "",
    rdo.turno ? `Turno: ${readableStatus(rdo.turno)}.` : "",
    collaboratorNames([rdo]).length
      ? `Colaboradores: ${collaboratorNames([rdo]).length}.`
      : "",
    equipmentNames([rdo]).length
      ? `Equipamentos: ${equipmentNames([rdo]).length}.`
      : "",
    materialNames([rdo]).length
      ? `Materiais: ${materialNames([rdo]).length}.`
      : "",
  ].filter(Boolean);

  return answer(lines.join("\n"), "RESUMO_RDO");
}

function answerOccurrences(
  snapshot: StaviaSnapshot,
  resolved: ResolvedContext,
): StaviaConsultaResponse {
  const occurrences = serviceObservationEntries(snapshot, resolved);

  if (occurrences.length === 0) {
    return answer(
      "Não encontrei ocorrências ou observações registradas para este contexto.",
      "OCORRENCIAS",
      {
        confidence: "MEDIA",
      },
    );
  }

  return answer(
    `Ocorrências/observações registradas:\n\n${bulletList(unique(occurrences))}`,
    "OCORRENCIAS",
  );
}

function readableEventType(type: string | null | undefined): string {
  return (type ?? "EVENTO")
    .toLowerCase()
    .replace(/_/g, " ")
    .replace(/^./, (first) => first.toUpperCase());
}

function answerOperationalTimeline(
  snapshot: StaviaSnapshot,
  resolved: ResolvedContext,
): StaviaConsultaResponse {
  const rdos = selectedRdos(resolved);
  const rdoIds = new Set(rdos.map((rdo) => rdo.id));
  const obraId = resolved.obra?.id ?? rdos[0]?.obraId ?? null;

  const events = (snapshot.operationalEvents ?? [])
    .filter((event) => {
      if (rdoIds.size > 0 && event.rdoId && rdoIds.has(event.rdoId)) {
        return true;
      }

      if (obraId && event.obraId === obraId) {
        return true;
      }

      return false;
    })
    .sort((left, right) =>
      (right.occurredAt ?? "").localeCompare(
        left.occurredAt ?? "",
      ),
    )
    .slice(0, 10);

  if (events.length === 0) {
    return answer(
      "Não encontrei eventos ontológicos salvos para este contexto.",
      "TIMELINE_OPERACIONAL",
      {
        confidence: "MEDIA",
      },
    );
  }

  return answer(
    `Timeline operacional:\n\n${bulletList(
      events.map((event) => {
        const when = formatDateTime(event.occurredAt);
        const status = event.syncStatus
          ? ` · ${event.syncStatus}`
          : "";
        return `${when}: ${readableEventType(event.type)}${status}`;
      }),
    )}`,
    "TIMELINE_OPERACIONAL",
    {
      metadata: {
        origemResposta: "SNAPSHOT_LOCAL_STAVIA",
        totalEventos: events.length,
      },
    },
  );
}

function answerRdoPhotos(
  resolved: ResolvedContext,
): StaviaConsultaResponse {
  const attachments = selectedRdos(resolved).flatMap(
    (rdo) => rdo.attachments ?? [],
  );
  const active = attachments.filter(
    (attachment) => !attachment.removedAt,
  );
  const pending = active.filter(
    (attachment) =>
      attachment.syncStatus !== "SYNCED" &&
      attachment.syncStatus !== "UPLOADED",
  );

  if (active.length === 0) {
    return answer(
      "Não encontrei fotos anexadas para este contexto.",
      "FOTOS_RDO",
      {
        confidence: "MEDIA",
      },
    );
  }

  return answer(
    `Encontrei ${active.length} foto(s) no contexto selecionado; ${pending.length} ainda pendente(s) de sincronização.\n\n${bulletList(
      active.slice(0, 8).map((attachment) => {
        const size =
          attachment.tamanhoComprimidoBytes ??
          attachment.tamanhoOriginalBytes ??
          "tamanho não informado";
        return `${attachment.nome ?? attachment.id} · ${attachment.syncStatus ?? "sem status"} · ${size}`;
      }),
    )}`,
    "FOTOS_RDO",
    {
      metadata: {
        origemResposta: "SNAPSHOT_LOCAL_STAVIA",
        totalFotos: active.length,
        fotosPendentes: pending.length,
      },
    },
  );
}

function answerStatus(
  snapshot: StaviaSnapshot,
  isOnline: boolean,
): StaviaConsultaResponse {
  const local = snapshot.metadata.localSyncedAt;
  const database = snapshot.metadata.databaseUpdatedAt;
  const mainTimestamp = local ?? database ?? snapshot.metadata.generatedAt;
  const mode = isOnline
    ? "online"
    : "offline — respondendo com dados salvos neste dispositivo";

  return answer(
    `Status: ${mode}.\nDados locais atualizados pela última vez em ${formatDateTime(mainTimestamp)}.${
      database
        ? `\nSnapshot do banco: ${formatDateTime(database)}.`
        : ""
    }`,
    "STATUS_SINCRONIZACAO",
    {
      metadata: {
        origemResposta: "SNAPSHOT_LOCAL_STAVIA",
        localSyncedAt: local,
        databaseUpdatedAt: database,
      },
    },
  );
}

function answerEvidence(
  resolved: ResolvedContext,
): StaviaConsultaResponse {
  const sources = selectedRdos(resolved).map(sourceForRdo);

  if (sources.length === 0) {
    return contextMissingAnswer(resolved);
  }

  return answer(
    `Evidências disponíveis para o contexto selecionado: ${sources.length}.`,
    "EVIDENCIAS",
    {
      sources,
      metadata: {
        origemResposta: "SNAPSHOT_LOCAL_STAVIA",
        modoDetalhado: true,
      },
    },
  );
}

export function responderComSnapshotStavia({
  snapshot,
  pergunta,
  contextoSelecionado,
  context,
  isOnline,
}: {
  snapshot: StaviaSnapshot;
  pergunta: string;
  contextoSelecionado?: string | null;
  context: StaviaLocalContext;
  isOnline: boolean;
}): StaviaConsultaResponse | null {
  const intent = detectIntent(pergunta);
  const questionForResolution = [pergunta, contextoSelecionado]
    .filter((part): part is string => Boolean(part?.trim()))
    .join(" ");

  const ontology = loadRdoOntology(snapshot);

  if (
    matchesOntologyAttribute(ontology, pergunta) &&
    (
      intent !== "OBSERVACOES_SERVICO" ||
      !isObservationRollupQuestion(pergunta)
    )
  ) {
    const resolvedForOntology = resolveContext(
      snapshot,
      questionForResolution,
      context,
    );
    const ontologyAnswer = answerWithRdoOntology({
      ontology,
      pergunta,
      rdos: selectedRdos(resolvedForOntology),
      operationalEvents: snapshot.operationalEvents ?? [],
    });

    if (ontologyAnswer) {
      return ontologyAnswer;
    }
  }

  if (intent === "DESCONHECIDA") {
    return null;
  }

  if (intent === "STATUS_SINCRONIZACAO") {
    return answerStatus(snapshot, isOnline);
  }

  if (intent === "OBRAS_POR_CIDADE") {
    return answerWorksByCity(snapshot, questionForResolution);
  }

  const resolved =
    intent === "CONTAGEM_RDOS_OBRA"
      ? resolveWorksiteContext(
          snapshot,
          questionForResolution,
          context,
        )
      : resolveContext(snapshot, questionForResolution, context);

  if (
    !resolved.rdo &&
    !resolved.obra &&
    intent !== "EVIDENCIAS"
  ) {
    return contextMissingAnswer(resolved);
  }

  switch (intent) {
    case "CONSULTA_COMPOSTA":
      return answerComposite(
        snapshot,
        resolved,
        requestedCompositeTopics(normalizeText(pergunta)),
      );
    case "SERVICOS_DA_OBRA":
      return answerServices(resolved, pergunta);
    case "OBSERVACOES_SERVICO":
      return answerServiceObservations(snapshot, resolved);
    case "COLABORADORES_DA_OBRA":
      return answerCollaborators(snapshot, resolved);
    case "TURNO":
      return answerShift(resolved);
    case "EQUIPAMENTOS":
      return answerEquipment(resolved);
    case "MATERIAIS":
      return answerMaterials(resolved);
    case "DETALHE_RDO":
      return answerRdoDetail(snapshot, resolved, pergunta);
    case "LOCALIZACAO_OBRA":
      return answerLocation(resolved);
    case "CONTAGEM_RDOS_OBRA":
      return answerRdoCount(resolved);
    case "PDOR":
      return answerPdor(snapshot, resolved);
    case "RESUMO_RDO":
      return answerSummary(resolved);
    case "OCORRENCIAS":
      return answerOccurrences(snapshot, resolved);
    case "TIMELINE_OPERACIONAL":
      return answerOperationalTimeline(snapshot, resolved);
    case "FOTOS_RDO":
      return answerRdoPhotos(resolved);
    case "EVIDENCIAS":
      return answerEvidence(resolved);
    default:
      return null;
  }
}
