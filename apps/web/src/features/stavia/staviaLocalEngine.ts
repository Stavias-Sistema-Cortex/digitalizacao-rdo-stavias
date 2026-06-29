import type {
  StaviaAnswer,
  StaviaConfidence,
  StaviaConsultaResponse,
  StaviaEvidence,
  StaviaSnapshot,
  StaviaSnapshotObra,
  StaviaSnapshotPdoc,
  StaviaSnapshotRdo,
} from "./stavia.types";

export interface StaviaLocalContext {
  activeObraId?: string | null;
  activeRdoId?: string | null;
  lastObraId?: string | null;
  lastRdoId?: string | null;
}

type LocalIntent =
  | "COLABORADORES_DA_OBRA"
  | "TURNO"
  | "EQUIPAMENTOS"
  | "MATERIAIS"
  | "LOCALIZACAO_OBRA"
  | "STATUS_SINCRONIZACAO"
  | "PDOC"
  | "OBRAS_POR_CIDADE"
  | "CONTAGEM_RDOS_OBRA"
  | "RESUMO_RDO"
  | "OCORRENCIAS"
  | "CONSULTA_COMPOSTA"
  | "EVIDENCIAS"
  | "DESCONHECIDA";

type CompositeTopic =
  | "LOCALIZACAO_OBRA"
  | "TURNO"
  | "COLABORADORES_DA_OBRA"
  | "EQUIPAMENTOS"
  | "MATERIAIS"
  | "CONTAGEM_RDOS_OBRA"
  | "PDOC"
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
      "pdoc",
      "risco de desvio",
      "risco de custo",
      "risco de custos",
      "estourar custo",
      "estouro de custo",
      "probabilidade de desvio",
      "probabilidade de exceder",
    ])
  ) {
    topics.push("PDOC");
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

  if (requestedCompositeTopics(normalized).length > 1) {
    return "CONSULTA_COMPOSTA";
  }

  if (
    hasAny(normalized, [
      "pdoc",
      "risco de desvio",
      "risco de custo",
      "risco de custos",
      "estourar custo",
      "estouro de custo",
      "probabilidade de desvio",
      "probabilidade de exceder",
    ])
  ) {
    return "PDOC";
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
      turno: rdo.turno,
      status: rdo.status,
    },
  };
}

function sourceForPdoc(pdoc: StaviaSnapshotPdoc): StaviaEvidence {
  return {
    type: "PDOC_LOCAL",
    id: pdoc.snapshotId ?? `PDOC:${pdoc.obraId}`,
    summary: "Snapshot PDOC salvo no dispositivo",
    updatedAt: pdoc.dataExecucao,
    validated: pdoc.statusExecucao === "SUCCESS",
    attributes: { ...pdoc },
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
      ...rdo.maoObra.map((item) => item.nomeColaborador),
      ...rdo.alocacoesColaboradores.map((item) => item.equipe),
    ]),
  );
}

function collaboratorEntries(
  rdos: StaviaSnapshotRdo[],
): string[] {
  return unique(
    rdos.flatMap((rdo) => [
      ...rdo.maoObra.map((item) =>
        [
          text(item.nomeColaborador),
          text(item.cargo),
          text(item.tipoVinculo),
          item.quantidade ? `qtd. ${item.quantidade}` : "",
        ]
          .filter(Boolean)
          .join(" · "),
      ),
      ...rdo.alocacoesColaboradores.map((item) =>
        [
          text(item.equipe),
          text(item.funcao),
          text(item.servicoNome),
          text(item.turno)
            ? `turno ${readableStatus(item.turno)}`
            : "",
        ]
          .filter(Boolean)
          .join(" · "),
      ),
    ]),
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

function answerCollaborators(
  resolved: ResolvedContext,
): StaviaConsultaResponse {
  const rdos = selectedRdos(resolved);
  const names = collaboratorEntries(rdos);

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
    `${resolved.rdo ? "Colaboradores encontrados neste RDO" : "Colaboradores encontrados nesta obra"}:\n\n${limitedBulletList(names)}`,
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

  if (topics.includes("PDOC")) {
    const obraId = resolved.obra?.id ?? resolved.rdo?.obraId;
    const pdoc = snapshot.pdocs.find(
      (candidate) => candidate.obraId === obraId,
    );
    sections.push(
      pdoc
        ? `PDOC: ${formatPercent(
            pdoc.probabilidadeQualquerExcedente ??
              pdoc.probabilidadeExceder5Pct ??
              pdoc.scoreHeuristico,
          )}.`
        : "PDOC: não encontrei resultado salvo para esta obra.",
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

function answerPdoc(
  snapshot: StaviaSnapshot,
  resolved: ResolvedContext,
): StaviaConsultaResponse {
  const obraId = resolved.obra?.id ?? resolved.rdo?.obraId;
  const pdoc = snapshot.pdocs.find(
    (candidate) => candidate.obraId === obraId,
  );

  if (!pdoc) {
    return answer(
      "Não encontrei resultado PDOC salvo para esta obra.",
      "PDOC",
      {
        confidence: "MEDIA",
        insufficientData: true,
      },
    );
  }

  const score =
    pdoc.probabilidadeQualquerExcedente ??
    pdoc.probabilidadeExceder5Pct ??
    pdoc.scoreHeuristico;
  const calibration = normalizeText(pdoc.calibracao);
  const calibrationWarning =
    calibration === "not_calibrated" ||
    calibration === "nao calibrado"
      ? " O modelo ainda está marcado como não calibrado, então isso deve ser tratado como alerta heurístico, não como previsão final."
      : "";

  return answer(
    `PDOC desta obra: ${formatPercent(score)}.${calibrationWarning}`,
    "PDOC",
    {
      confidence:
        calibrationWarning.length > 0 ? "MEDIA" : "ALTA",
      warnings:
        calibrationWarning.length > 0
          ? [
              "PDOC não calibrado: indicador heurístico/simulatório.",
            ]
          : [],
      sources: [sourceForPdoc(pdoc)],
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
  resolved: ResolvedContext,
): StaviaConsultaResponse {
  const occurrences = selectedRdos(resolved)
    .map((rdo) => text(rdo.observacoes))
    .filter(Boolean);

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
    case "COLABORADORES_DA_OBRA":
      return answerCollaborators(resolved);
    case "TURNO":
      return answerShift(resolved);
    case "EQUIPAMENTOS":
      return answerEquipment(resolved);
    case "MATERIAIS":
      return answerMaterials(resolved);
    case "LOCALIZACAO_OBRA":
      return answerLocation(resolved);
    case "CONTAGEM_RDOS_OBRA":
      return answerRdoCount(resolved);
    case "PDOC":
      return answerPdoc(snapshot, resolved);
    case "RESUMO_RDO":
      return answerSummary(resolved);
    case "OCORRENCIAS":
      return answerOccurrences(resolved);
    case "EVIDENCIAS":
      return answerEvidence(resolved);
    default:
      return null;
  }
}
