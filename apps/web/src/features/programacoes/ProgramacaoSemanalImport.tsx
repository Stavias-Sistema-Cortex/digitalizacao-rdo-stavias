import {
  useMemo,
  useRef,
  useState,
} from "react";

import {
  buscarEncarregadosProgramacao,
  buscarObrasProgramacao,
  criarProgramacaoDeLinha,
  type ProgramacaoCriada,
} from "./programacaoApi";
import { getSession } from "../auth/authSession";
import {
  createEmptyControleGeometrico,
  createEmptyMaoObra,
  createEmptyMaterial,
  createEmptyRdo,
  createEmptyServicoExecutado,
} from "../rdos/createEmptyRdo";
import type {
  ControleGeometricoDraft,
  MaoObraDraft,
  MaterialDraft,
  NumericInput,
  RdoDraft,
  ServicoExecutadoDraft,
} from "../rdos/rdo.types";
import { saveNewRdoDraftAtomically } from "../../lib/db/localRdoService";
import { importarProgramacaoSemanal } from "./importProgramacaoSemanal";
import type {
  EncarregadoResolvido,
  ObraResolvida,
  ProgramacaoSemanalDia,
  ProgramacaoSemanalImportada,
  ProgramacaoSemanalLinha,
} from "./programacaoSemanal.types";
import "./ProgramacaoSemanalImport.css";

type ObraMap = Record<string, ObraResolvida | null>;
type ObraOverrideMap = Record<string, string>;

interface ProgramacaoSemanalImportProps {
  onRdoCreated?: () => void | Promise<void>;
}

interface LinhaPreparadaParaRdo {
  linha: ProgramacaoSemanalLinha;
  obraId: string;
  obra: ObraResolvida | null;
  linhaOrigem: number;
  programacao: ProgramacaoCriada | null;
}

interface RdoCriadoDaProgramacao {
  numeroRdo: string;
  linhas: number;
}

interface PeriodoOperacional {
  turno: RdoDraft["turno"];
  horaInicio: string;
  horaFim: string;
}

function formatDate(value: string): string {
  const date = new Date(`${value}T00:00:00`);

  if (Number.isNaN(date.getTime())) {
    return value;
  }

  return new Intl.DateTimeFormat("pt-BR", {
    dateStyle: "short",
  }).format(date);
}

function statusLabel(
  dia: ProgramacaoSemanalDia,
): string {
  if (dia.status === "COM_DADOS") {
    return `${dia.linhas.length} linha${dia.linhas.length === 1 ? "" : "s"}`;
  }

  if (dia.status === "BAIXADA") {
    return "Baixada";
  }

  return "Sem dados";
}

function normalize(value: string): string {
  return value
    .normalize("NFD")
    .replace(/\p{Diacritic}/gu, "")
    .toLowerCase()
    .trim();
}

function pickBestObra(
  linha: ProgramacaoSemanalLinha,
  candidates: ObraResolvida[],
): ObraResolvida | null {
  const targetObra = normalize(linha.obraNome);
  const targetCliente = normalize(linha.cliente);
  const targetRodovia = normalize(linha.rodovia);

  return (
    candidates.find(
      (obra) =>
        normalize(obra.nome ?? "") === targetObra,
    ) ??
    candidates.find(
      (obra) =>
        normalize(obra.cliente ?? "") === targetCliente &&
        (!targetRodovia ||
          normalize(obra.rodovia ?? "") === targetRodovia),
    ) ??
    candidates[0] ??
    null
  );
}

function pickBestEncarregado(
  extracted: string,
  candidates: EncarregadoResolvido[],
): EncarregadoResolvido | null {
  const target = normalize(extracted);

  return (
    candidates.find(
      (colaborador) =>
        normalize(colaborador.nome ?? "") === target,
    ) ??
    candidates.find((colaborador) =>
      normalize(colaborador.nome ?? "").includes(
        target,
      ),
    ) ??
    candidates.find((colaborador) =>
      target.includes(
        normalize(colaborador.nome ?? ""),
      ),
    ) ??
    candidates
      .map((colaborador) => ({
        colaborador,
        score: nameMatchScore(
          extracted,
          colaborador.nome ?? "",
        ),
      }))
      .sort((left, right) => right.score - left.score)
      .find((item) => item.score >= 2)
      ?.colaborador ??
    null
  );
}

function nameMatchScore(
  source: string,
  candidate: string,
): number {
  const sourceTokens = nameTokens(source);
  const candidateTokens = new Set(
    nameTokens(candidate),
  );

  return sourceTokens.filter((token) =>
    candidateTokens.has(token),
  ).length;
}

function nameTokens(value: string): string[] {
  return normalize(value)
    .split(/\s+/)
    .map((token) => token.trim())
    .filter((token) => token.length >= 3);
}

function fallbackQueriesForName(
  value: string,
): string[] {
  const tokens = nameTokens(value);
  const queries = [
    value,
    tokens.join(" "),
    tokens.length > 1
      ? `${tokens[0]} ${tokens[tokens.length - 1]}`
      : "",
    tokens[0] ?? "",
  ];

  return uniqueNonEmpty(queries);
}

async function buscarEncarregadoComFallback(
  extracted: string,
): Promise<EncarregadoResolvido | null> {
  const candidatesById = new Map<
    string,
    EncarregadoResolvido
  >();

  for (const query of fallbackQueriesForName(
    extracted,
  )) {
    const candidates =
      await buscarEncarregadosProgramacao(query);

    for (const candidate of candidates) {
      candidatesById.set(candidate.id, candidate);
    }

    const best = pickBestEncarregado(
      extracted,
      Array.from(candidatesById.values()),
    );

    if (best) {
      return best;
    }
  }

  return (
    pickBestEncarregado(
      extracted,
      Array.from(candidatesById.values()),
    ) ??
    null
  );
}

function lineObraId(
  linha: ProgramacaoSemanalLinha,
  obras: ObraMap,
  overrides: ObraOverrideMap,
): string {
  return (
    overrides[linha.id]?.trim() ||
    obras[linha.id]?.id ||
    ""
  );
}

function compactDate(value: string): string {
  return value.replace(/\D/g, "");
}

function clean(value: string | null | undefined): string {
  return value?.trim() ?? "";
}

function numericInput(
  value: number | null,
): NumericInput {
  return value === null ? "" : value;
}

function uniqueNonEmpty(values: string[]): string[] {
  return Array.from(
    new Set(
      values
        .map((value) => value.trim())
        .filter(Boolean),
    ),
  );
}

function joinParts(parts: string[]): string {
  return parts.filter(Boolean).join(" · ");
}

function firstNonEmpty(
  lines: LinhaPreparadaParaRdo[],
  pick: (linha: ProgramacaoSemanalLinha) => string,
): string {
  return (
    lines
      .map((item) => pick(item.linha).trim())
      .find(Boolean) ?? ""
  );
}

function lastNonEmpty(
  lines: LinhaPreparadaParaRdo[],
  pick: (linha: ProgramacaoSemanalLinha) => string,
): string {
  const values = lines
    .map((item) => pick(item.linha).trim())
    .filter(Boolean);

  return values.at(-1) ?? "";
}

function parseHour(value: string): string {
  const match = value.match(
    /(\d{1,2})(?::|h)?(\d{2})?/i,
  );

  if (!match) {
    return "";
  }

  const hour = Number(match[1]);
  const minute = Number(match[2] ?? "0");

  if (
    Number.isNaN(hour) ||
    Number.isNaN(minute) ||
    hour < 0 ||
    hour > 23 ||
    minute < 0 ||
    minute > 59
  ) {
    return "";
  }

  return `${String(hour).padStart(2, "0")}:${String(minute).padStart(2, "0")}`;
}

function extractTimeRange(
  value: string,
): Pick<PeriodoOperacional, "horaInicio" | "horaFim"> | null {
  const match = value.match(
    /(\d{1,2}(?::|h)?\d{0,2})\s*(?:-|a|às|as|ate|até)\s*(\d{1,2}(?::|h)?\d{0,2})/i,
  );

  if (!match) {
    return null;
  }

  const horaInicio = parseHour(match[1]);
  const horaFim = parseHour(match[2]);

  if (!horaInicio || !horaFim) {
    return null;
  }

  return {
    horaInicio,
    horaFim,
  };
}

function isNightPeriod(value: string): boolean {
  const periodos = normalize(
    value,
  );

  return (
    periodos.includes("noturn") ||
    periodos.includes("noite") ||
    periodos.includes("madrugada")
  );
}

function turnoFromPeriodo(
  value: string,
): RdoDraft["turno"] {
  return isNightPeriod(value) ? "NOTURNO" : "DIURNO";
}

function inferPeriodoOperacional(
  lines: LinhaPreparadaParaRdo[],
): PeriodoOperacional {
  const rawPeriodo = lines
    .map((item) => item.linha.periodo)
    .find((value) => value.trim()) ?? "";
  const parsedRange = extractTimeRange(rawPeriodo);
  const turno = turnoFromPeriodo(rawPeriodo);

  if (parsedRange) {
    return {
      turno,
      ...parsedRange,
    };
  }

  return turno === "NOTURNO"
    ? {
        turno,
        horaInicio: "19:00",
        horaFim: "05:00",
      }
    : {
        turno,
        horaInicio: "07:00",
        horaFim: "17:00",
      };
}

function rdoObraPiece(
  lines: LinhaPreparadaParaRdo[],
): string {
  const first = lines[0];
  const raw =
    first?.obra?.codigoContrato ||
    first?.obra?.nome ||
    first?.linha.obraNome ||
    first?.obraId ||
    "OBRA";

  return (
    raw
      .replace(/[^a-zA-Z0-9]/g, "")
      .toUpperCase()
      .slice(0, 10) || "OBRA"
  );
}

function buildLinhaObservacoes(
  item: LinhaPreparadaParaRdo,
): string {
  const { linha, linhaOrigem, programacao } = item;
  const parts = [
    `Linha ${linhaOrigem}`,
    linha.fechamento
      ? `Fechamento ${linha.fechamento}`
      : "",
    linha.periodo ? `Período ${linha.periodo}` : "",
    linha.toneladaMassa !== null
      ? `Massa prevista ${linha.toneladaMassa} t`
      : "",
    linha.tipoCap ? `CAP ${linha.tipoCap}` : "",
    linha.teorCapProjeto !== null
      ? `Teor ${linha.teorCapProjeto}%`
      : "",
    linha.cap !== null ? `CAP previsto ${linha.cap}` : "",
    programacao?.id
      ? `Programação ${programacao.id}`
      : "",
  ];

  return parts.filter(Boolean).join(" | ");
}

function buildServicoExecutado(
  item: LinhaPreparadaParaRdo,
): ServicoExecutadoDraft {
  const { linha } = item;

  return {
    ...createEmptyServicoExecutado(),
    servicoNome:
      linha.servico ||
      linha.fechamento ||
      "Serviço programado",
    quantidadeExecutada: "",
    unidade: linha.toneladaMassa !== null ? "t" : "",
    trechoInicial: linha.kmInicial,
    trechoFinal: linha.kmFinal,
    localizacao: joinParts([
      linha.rodovia,
      linha.sentido,
      linha.faixa ? `Faixa ${linha.faixa}` : "",
    ]),
    turno: turnoFromPeriodo(linha.periodo),
    observacoes: buildLinhaObservacoes(item),
  };
}

function buildControleGeometrico(
  item: LinhaPreparadaParaRdo,
): ControleGeometricoDraft {
  const { linha } = item;

  return {
    ...createEmptyControleGeometrico(),
    subtrecho: linha.rodovia,
    numero: linha.fechamento,
    kmInicial: linha.kmInicial,
    kmFinal: linha.kmFinal,
    pista: linha.sentido,
    faixa: linha.faixa,
    atividadeObservacoes: linha.servico,
    comprimentoM: numericInput(linha.extensaoM),
    observacoes: buildLinhaObservacoes(item),
  };
}

function buildMateriais(
  lines: LinhaPreparadaParaRdo[],
): MaterialDraft[] {
  return lines.flatMap((item) => {
    const { linha } = item;
    const materials: MaterialDraft[] = [];

    if (linha.toneladaMassa !== null) {
      materials.push({
        ...createEmptyMaterial(),
        materialNome: "Massa asfáltica prevista",
        unidade: "t",
        quantidadePrevista: linha.toneladaMassa,
        observacoes: buildLinhaObservacoes(item),
      });
    }

    if (
      linha.cap !== null ||
      linha.tipoCap ||
      linha.teorCapProjeto !== null
    ) {
      materials.push({
        ...createEmptyMaterial(),
        materialNome: linha.tipoCap
          ? `CAP ${linha.tipoCap}`
          : "CAP previsto",
        unidade: linha.cap !== null ? "t" : "",
        quantidadePrevista: numericInput(linha.cap),
        observacoes: buildLinhaObservacoes(item),
      });
    }

    return materials;
  });
}

function buildMaoObraDaProgramacao(
  encarregado: EncarregadoResolvido | null,
  encarregadoExtraido: string,
): MaoObraDraft[] {
  const nome =
    clean(encarregado?.nome) ||
    encarregadoExtraido.trim();

  if (!nome) {
    return [];
  }

  return [
    {
      ...createEmptyMaoObra(),
      colaboradorId: encarregado?.id ?? "",
      nomeColaborador: nome,
      cargo:
        clean(encarregado?.nomePerfil) ||
        "Encarregado",
      quantidade: 1,
    },
  ];
}

function buildRdoObservacoes(
  imported: ProgramacaoSemanalImportada,
  selectedDay: ProgramacaoSemanalDia,
  encarregado: EncarregadoResolvido | null,
  lines: LinhaPreparadaParaRdo[],
): string {
  const programacaoIds = uniqueNonEmpty(
    lines.map((item) => item.programacao?.id ?? ""),
  );
  const detalhes = lines.map(buildLinhaObservacoes);

  return [
    "Origem ontológica: programação diária prevista.",
    `Arquivo: ${imported.arquivoNome}.`,
    `Fonte importada: ${imported.fonteTipo}.`,
    `Dia selecionado: ${formatDate(selectedDay.data)}.`,
    encarregado
      ? `Encarregado resolvido na base: ${encarregado.nome ?? encarregado.id} (${encarregado.id}).`
      : `Encarregado extraído: ${imported.encarregadoExtraido || "não informado"}.`,
    programacaoIds.length > 0
      ? `Programações operacionais vinculadas: ${programacaoIds.join(", ")}.`
      : "Programações operacionais vinculadas: não informadas.",
    "Campos de execução real, equipe e equipamentos permanecem em branco para edição no RDO.",
    "",
    ...detalhes,
  ].join("\n");
}

function buildRdoDraftFromProgramacao(
  imported: ProgramacaoSemanalImportada,
  selectedDay: ProgramacaoSemanalDia,
  encarregado: EncarregadoResolvido | null,
  lines: LinhaPreparadaParaRdo[],
): RdoDraft {
  const base = createEmptyRdo();
  const periodo = inferPeriodoOperacional(lines);
  const first = lines[0];
  const obra = first?.obra ?? null;
  const programacaoIds = uniqueNonEmpty(
    lines.map((item) => item.programacao?.id ?? ""),
  );
  const session = getSession();

  return {
    ...base,
    obraId: first?.obraId ?? "",
    programacaoId: programacaoIds[0] ?? "",
    numeroRdo: `RDO-${compactDate(selectedDay.data)}-${rdoObraPiece(lines)}-${base.id.slice(0, 8).toUpperCase()}`,
    dataRdo: selectedDay.data,
    cliente:
      firstNonEmpty(lines, (linha) => linha.cliente) ||
      clean(obra?.cliente),
    contrato: clean(obra?.codigoContrato),
    rodovia:
      firstNonEmpty(lines, (linha) => linha.rodovia) ||
      clean(obra?.rodovia),
    kmInicialProgramado: firstNonEmpty(
      lines,
      (linha) => linha.kmInicial,
    ),
    kmFinalProgramado: lastNonEmpty(
      lines,
      (linha) => linha.kmFinal,
    ),
    turno: periodo.turno,
    horaInicio: periodo.horaInicio,
    horaFim: periodo.horaFim,
    condicaoNoite:
      periodo.turno === "NOTURNO" ? "" : "NAO_APLICAVEL",
    observacoes: buildRdoObservacoes(
      imported,
      selectedDay,
      encarregado,
      lines,
    ),
    preenchidoPor:
      session?.nome?.trim() ||
      session?.cpfMascarado ||
      "",
    encarregadoObra:
      clean(encarregado?.nome) ||
      imported.encarregadoExtraido,
    servicosExecutados: lines.map(
      buildServicoExecutado,
    ),
    alocacoesColaboradores: [],
    maoObra: buildMaoObraDaProgramacao(
      encarregado,
      imported.encarregadoExtraido,
    ),
    equipamentos: [],
    materiais: buildMateriais(lines),
    controlesGeometricos: lines.map(
      buildControleGeometrico,
    ),
    syncStatus: "PENDING_SYNC",
  };
}

function prepareLinesForRdo(
  selectedDay: ProgramacaoSemanalDia,
  obrasResolvidas: ObraMap,
  obraOverrides: ObraOverrideMap,
  programacoesCriadas: Map<string, ProgramacaoCriada>,
): LinhaPreparadaParaRdo[] {
  return selectedDay.linhas.map((linha, index) => ({
    linha,
    obraId: lineObraId(
      linha,
      obrasResolvidas,
      obraOverrides,
    ),
    obra: obrasResolvidas[linha.id] ?? null,
    linhaOrigem: index + 1,
    programacao:
      programacoesCriadas.get(linha.id) ?? null,
  }));
}

function groupLinesByObra(
  lines: LinhaPreparadaParaRdo[],
): LinhaPreparadaParaRdo[][] {
  const grouped = new Map<
    string,
    LinhaPreparadaParaRdo[]
  >();

  for (const item of lines) {
    const current = grouped.get(item.obraId) ?? [];
    current.push(item);
    grouped.set(item.obraId, current);
  }

  return Array.from(grouped.values());
}

export function ProgramacaoSemanalImport({
  onRdoCreated,
}: ProgramacaoSemanalImportProps) {
  const fileInputRef =
    useRef<HTMLInputElement | null>(null);
  const [imported, setImported] =
    useState<ProgramacaoSemanalImportada | null>(
      null,
    );
  const [selectedDate, setSelectedDate] =
    useState("");
  const [obrasResolvidas, setObrasResolvidas] =
    useState<ObraMap>({});
  const [obraOverrides, setObraOverrides] =
    useState<ObraOverrideMap>({});
  const [encarregado, setEncarregado] =
    useState<EncarregadoResolvido | null>(null);
  const [isParsing, setIsParsing] =
    useState(false);
  const [isResolving, setIsResolving] =
    useState(false);
  const [isSaving, setIsSaving] =
    useState(false);
  const [message, setMessage] =
    useState("");
  const [error, setError] =
    useState("");

  const selectedDay = useMemo(
    () =>
      imported?.dias.find(
        (dia) => dia.data === selectedDate,
      ) ?? null,
    [imported, selectedDate],
  );

  const unresolvedCount = useMemo(() => {
    if (!selectedDay) {
      return 0;
    }

    return selectedDay.linhas.filter(
      (linha) =>
        !lineObraId(
          linha,
          obrasResolvidas,
          obraOverrides,
        ),
    ).length;
  }, [
    selectedDay,
    obrasResolvidas,
    obraOverrides,
  ]);

  async function resolveImported(
    result: ProgramacaoSemanalImportada,
  ): Promise<void> {
    setIsResolving(true);

    try {
      const nextObras: ObraMap = {};
      const allLines = result.dias.flatMap(
        (dia) => dia.linhas,
      );

      for (const linha of allLines) {
        if (!linha.obraNome && !linha.cliente) {
          nextObras[linha.id] = null;
          continue;
        }

        const query =
          linha.obraNome || linha.cliente;
        const candidates =
          await buscarObrasProgramacao(query);
        nextObras[linha.id] = pickBestObra(
          linha,
          candidates,
        );
      }

      setObrasResolvidas(nextObras);

      if (result.encarregadoExtraido) {
        setEncarregado(
          await buscarEncarregadoComFallback(
            result.encarregadoExtraido,
          ),
        );
      } else {
        setEncarregado(null);
      }
    } finally {
      setIsResolving(false);
    }
  }

  async function handleFile(
    file: File,
  ): Promise<void> {
    setIsParsing(true);
    setMessage("");
    setError("");
    setImported(null);
    setSelectedDate("");
    setObrasResolvidas({});
    setObraOverrides({});
    setEncarregado(null);

    try {
      const result =
        await importarProgramacaoSemanal(file);
      const firstDayWithData =
        result.dias.find(
          (dia) => dia.status === "COM_DADOS",
        ) ?? result.dias[0];

      setImported(result);
      setSelectedDate(firstDayWithData?.data ?? "");
      await resolveImported(result);
    } catch (parseError: unknown) {
      setError(
        parseError instanceof Error
          ? parseError.message
          : "Não foi possível ler a programação.",
      );
    } finally {
      setIsParsing(false);
    }
  }

  async function handleSaveSelectedDay(): Promise<void> {
    if (!imported || !selectedDay) {
      return;
    }

    if (selectedDay.status !== "COM_DADOS") {
      setError(
        "Selecione um dia com linhas de programação.",
      );
      return;
    }

    if (
      !encarregado &&
      !imported.encarregadoExtraido.trim()
    ) {
      setError(
        "Não foi possível identificar o encarregado da programação.",
      );
      return;
    }

    if (unresolvedCount > 0) {
      setError(
        "Informe ou resolva o Obra ID de todas as linhas do dia selecionado.",
      );
      return;
    }

    setIsSaving(true);
    setMessage("");
    setError("");

    try {
      let created = 0;
      let programacaoErrorMessage = "";
      const programacoesCriadas = new Map<
        string,
        ProgramacaoCriada
      >();

      try {
        for (
          let index = 0;
          index < selectedDay.linhas.length;
          index += 1
        ) {
          const linha = selectedDay.linhas[index];
          const programacao =
            await criarProgramacaoDeLinha(linha, {
              dataProgramacao: selectedDay.data,
              arquivoNome: imported.arquivoNome,
              obraId: lineObraId(
                linha,
                obrasResolvidas,
                obraOverrides,
              ),
              encarregado,
              linhaOrigem: index + 1,
            });

          programacoesCriadas.set(
            linha.id,
            programacao,
          );
          created += 1;
        }
      } catch (programacaoError: unknown) {
        programacaoErrorMessage =
          programacaoError instanceof Error
            ? programacaoError.message
            : "Falha ao enviar a programação operacional para a API.";
      }

      const linhasPreparadas = prepareLinesForRdo(
        selectedDay,
        obrasResolvidas,
        obraOverrides,
        programacoesCriadas,
      );
      const rdosCriados: RdoCriadoDaProgramacao[] =
        [];

      for (const linhasDaObra of groupLinesByObra(
        linhasPreparadas,
      )) {
        const draft = buildRdoDraftFromProgramacao(
          imported,
          selectedDay,
          encarregado,
          linhasDaObra,
        );

        await saveNewRdoDraftAtomically(draft);
        rdosCriados.push({
          numeroRdo: draft.numeroRdo,
          linhas: linhasDaObra.length,
        });
      }

      if (onRdoCreated) {
        await onRdoCreated();
      }

      setMessage(
        `${created} de ${selectedDay.linhas.length} linha${selectedDay.linhas.length === 1 ? "" : "s"} enviada${selectedDay.linhas.length === 1 ? "" : "s"} para a programação operacional de ${formatDate(selectedDay.data)}. ${rdosCriados.length} RDO${rdosCriados.length === 1 ? "" : "s"} salvo${rdosCriados.length === 1 ? "" : "s"} offline (${rdosCriados.map((rdo) => `${rdo.numeroRdo}: ${rdo.linhas} linha${rdo.linhas === 1 ? "" : "s"}`).join(", ")}). Nenhum outro dia foi enviado.`,
      );
      if (programacaoErrorMessage) {
        setError(
          `A programação online não foi concluída: ${programacaoErrorMessage}. O RDO ficou salvo offline e entrará na sincronização automática.`,
        );
      }
    } catch (saveError: unknown) {
      setError(
        saveError instanceof Error
          ? saveError.message
          : "Falha ao criar a programação e o RDO do dia selecionado.",
      );
    } finally {
      setIsSaving(false);
    }
  }

  return (
    <section className="form-card programacao-import">
      <div className="section-heading">
        <div>
          <span className="section-number">
            P
          </span>
          <h2>Programação diária prevista</h2>
        </div>
      </div>

      <div className="programacao-import__toolbar">
        <div>
          <p className="programacao-import__lead">
            Importe PDF ou Excel, confira a semana e crie a programação de apenas um dia por vez.
          </p>
        </div>

        <button
          type="button"
          className="primary-button"
          onClick={() =>
            fileInputRef.current?.click()
          }
          disabled={isParsing || isResolving}
        >
          {isParsing
            ? "Lendo arquivo..."
            : "Importar PDF/Excel"}
        </button>

        <input
          ref={fileInputRef}
          className="visually-hidden"
          type="file"
          accept=".pdf,.xlsx,.xls,.xlsm,application/pdf,application/vnd.openxmlformats-officedocument.spreadsheetml.sheet,application/vnd.ms-excel,application/vnd.ms-excel.sheet.macroEnabled.12"
          onChange={(event) => {
            const file =
              event.target.files?.[0];
            if (file) {
              void handleFile(file);
            }
            event.currentTarget.value = "";
          }}
        />
      </div>

      {message && (
        <div className="notice">
          {message}
        </div>
      )}

      {error && (
        <div
          className="notice notice-error"
          role="alert"
        >
          {error}
        </div>
      )}

      {imported && (
        <>
          <div className="programacao-import__summary">
            <div>
              <span>Arquivo</span>
              <strong>{imported.arquivoNome}</strong>
            </div>
            <div>
              <span>Tipo</span>
              <strong>{imported.fonteTipo}</strong>
            </div>
            <div>
              <span>Encarregado extraído</span>
              <strong>
                {imported.encarregadoExtraido || "-"}
              </strong>
            </div>
            <div>
              <span>Encarregado da base</span>
              <strong>
                {isResolving
                  ? "Buscando..."
                  : encarregado?.nome ?? "Não resolvido"}
              </strong>
            </div>
          </div>

          {imported.avisos.length > 0 && (
            <div className="programacao-import__warnings">
              {imported.avisos.map((warning) => (
                <span key={warning}>
                  {warning}
                </span>
              ))}
            </div>
          )}

          <div className="programacao-week-grid">
            {imported.dias.map((dia) => (
              <button
                key={dia.data}
                type="button"
                className={`programacao-day programacao-day--${dia.status.toLowerCase()}${
                  dia.data === selectedDate
                    ? " programacao-day--selected"
                    : ""
                }`}
                onClick={() => {
                  setSelectedDate(dia.data);
                  setError("");
                }}
              >
                <span>{dia.diaSemana}</span>
                <strong>
                  {formatDate(dia.data)}
                </strong>
                <em>{statusLabel(dia)}</em>
              </button>
            ))}
          </div>

          {selectedDay && (
            <div className="programacao-day-detail">
              <div className="programacao-day-detail__header">
                <div>
                  <h3>
                    {formatDate(selectedDay.data)}
                  </h3>
                  <p>
                    Dia selecionado para criação. As outras datas ficam apenas em conferência.
                  </p>
                </div>

                <button
                  type="button"
                  className="primary-button"
                  onClick={() => {
                    void handleSaveSelectedDay();
                  }}
                  disabled={
                    isSaving ||
                    selectedDay.status !==
                      "COM_DADOS"
                  }
                >
                  {isSaving
                    ? "Criando..."
                    : "Criar dia selecionado"}
                </button>
              </div>

              <div className="programacao-lines-table-wrap">
                <table className="programacao-lines-table">
                  <thead>
                    <tr>
                      <th>Identificação</th>
                      <th>Trecho</th>
                      <th>Produção</th>
                      <th>Obra no Córtex</th>
                    </tr>
                  </thead>
                  <tbody>
                    {selectedDay.linhas.map(
                      (linha) => {
                        const obra =
                          obrasResolvidas[linha.id];
                        const obraId =
                          lineObraId(
                            linha,
                            obrasResolvidas,
                            obraOverrides,
                          );

                        return (
                          <tr key={linha.id}>
                            <td>
                              <strong>
                                {linha.fechamento}
                              </strong>
                              <span>
                                {linha.servico || "-"}
                              </span>
                              <small>
                                {linha.obraNome || "-"} · {linha.cliente || "-"}
                              </small>
                            </td>
                            <td>
                              <span>
                                {linha.rodovia || "-"} · {linha.sentido || "-"}
                              </span>
                              <small>
                                KM {linha.kmInicial || "-"} → {linha.kmFinal || "-"} · Faixa {linha.faixa || "-"}
                              </small>
                            </td>
                            <td>
                              <span>
                                {linha.periodo || "-"} · {linha.toneladaMassa ?? "-"} t
                              </span>
                              <small>
                                CAP {linha.tipoCap || "-"} · Teor {linha.teorCapProjeto ?? "-"}% · Cap {linha.cap ?? "-"}
                              </small>
                            </td>
                            <td>
                              <span
                                className={
                                  obraId
                                    ? "programacao-match programacao-match--ok"
                                    : "programacao-match programacao-match--missing"
                                }
                              >
                                {obra
                                  ? `${obra.nome ?? obra.id}`
                                  : obraId
                                    ? "Obra ID manual"
                                    : "Não resolvida"}
                              </span>
                              <input
                                type="text"
                                placeholder="Obra ID se não resolver"
                                value={
                                  obraOverrides[
                                    linha.id
                                  ] ?? ""
                                }
                                onChange={(event) => {
                                  setObraOverrides(
                                    (current) => ({
                                      ...current,
                                      [linha.id]:
                                        event.target.value,
                                    }),
                                  );
                                }}
                              />
                            </td>
                          </tr>
                        );
                      },
                    )}

                    {selectedDay.linhas.length ===
                      0 && (
                      <tr>
                        <td colSpan={4}>
                          Nenhuma linha importável neste dia.
                        </td>
                      </tr>
                    )}
                  </tbody>
                </table>
              </div>
            </div>
          )}
        </>
      )}
    </section>
  );
}
