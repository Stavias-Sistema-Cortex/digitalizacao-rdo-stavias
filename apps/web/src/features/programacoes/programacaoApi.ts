import {
  apiFetch,
  readResponseBody,
  responseErrorMessage,
} from "../../lib/api/apiClient";

import type {
  EncarregadoResolvido,
  ObraResolvida,
  ProgramacaoSemanalLinha,
} from "./programacaoSemanal.types";

interface ProgramacaoCreatePayload {
  obraId: string;
  dataProgramacao: string;
  equipe: string | null;
  fechamento: string | null;
  encarregado: string | null;
  encarregadoColaboradorId: string | null;
  cliente: string | null;
  servico: string | null;
  tipoServico: string | null;
  rodovia: string | null;
  sentido: string | null;
  periodo: string | null;
  faixa: string | null;
  kmInicial: string | null;
  kmFinal: string | null;
  extensaoM: number | null;
  toneladaMassa: number | null;
  tipoCap: string | null;
  teorCapProjeto: number | null;
  cap: number | null;
  status: string;
  fonteCriacao: string;
  fonteArquivo: string | null;
  linhaOrigem: number | null;
  observacoes: string | null;
}

export interface ProgramacaoCriada {
  id: string;
  dataProgramacao: string;
  obraId: string;
  servico: string | null;
  fechamento: string | null;
}

async function readJson<T>(
  response: Response,
): Promise<T> {
  const body = await readResponseBody(response);

  if (!response.ok) {
    throw new Error(
      responseErrorMessage(body, response.status),
    );
  }

  return body as T;
}

export async function buscarObrasProgramacao(
  query: string,
): Promise<ObraResolvida[]> {
  const params = new URLSearchParams();
  if (query.trim()) {
    params.set("query", query.trim());
  }

  const response = await apiFetch(
    `/obras?${params.toString()}`,
  );
  return readJson<ObraResolvida[]>(response);
}

export async function buscarEncarregadosProgramacao(
  query: string,
): Promise<EncarregadoResolvido[]> {
  const params = new URLSearchParams();
  if (query.trim()) {
    params.set("query", query.trim());
  }

  const response = await apiFetch(
    `/colaboradores?${params.toString()}`,
  );
  return readJson<EncarregadoResolvido[]>(response);
}

export async function criarProgramacaoDeLinha(
  linha: ProgramacaoSemanalLinha,
  options: {
    dataProgramacao: string;
    arquivoNome: string;
    obraId: string;
    encarregado: EncarregadoResolvido | null;
    linhaOrigem: number;
  },
): Promise<ProgramacaoCriada> {
  const payload: ProgramacaoCreatePayload = {
    obraId: options.obraId,
    dataProgramacao: options.dataProgramacao,
    equipe: null,
    fechamento: emptyToNull(linha.fechamento),
    encarregado:
      emptyToNull(options.encarregado?.nome ?? "") ??
      null,
    encarregadoColaboradorId:
      options.encarregado?.id ?? null,
    cliente: emptyToNull(linha.cliente),
    servico: emptyToNull(linha.servico),
    tipoServico: emptyToNull(linha.servico),
    rodovia: emptyToNull(linha.rodovia),
    sentido: emptyToNull(linha.sentido),
    periodo: emptyToNull(linha.periodo),
    faixa: emptyToNull(linha.faixa),
    kmInicial: emptyToNull(linha.kmInicial),
    kmFinal: emptyToNull(linha.kmFinal),
    extensaoM: linha.extensaoM,
    toneladaMassa: linha.toneladaMassa,
    tipoCap: emptyToNull(linha.tipoCap),
    teorCapProjeto: linha.teorCapProjeto,
    cap: linha.cap,
    status: "PLANEJADA",
    fonteCriacao: "IMPORTACAO_SEMANAL",
    fonteArquivo: options.arquivoNome,
    linhaOrigem: options.linhaOrigem,
    observacoes: buildObservacoes(linha),
  };

  const response = await apiFetch("/programacoes", {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
    },
    body: JSON.stringify(payload),
  });

  return readJson<ProgramacaoCriada>(response);
}

function buildObservacoes(
  linha: ProgramacaoSemanalLinha,
): string {
  const parts = [
    `Origem: ${linha.fechamento}`,
    linha.periodo
      ? `Período: ${linha.periodo}`
      : "",
    linha.toneladaMassa !== null
      ? `Ton. Massa: ${linha.toneladaMassa}`
      : "",
    linha.tipoCap
      ? `Tipo do Cap: ${linha.tipoCap}`
      : "",
    linha.teorCapProjeto !== null
      ? `Teor Cap Projeto: ${linha.teorCapProjeto}%`
      : "",
    linha.cap !== null ? `Cap: ${linha.cap}` : "",
  ].filter(Boolean);

  return parts.join(" | ");
}

function emptyToNull(value: string): string | null {
  const trimmed = value.trim();
  return trimmed ? trimmed : null;
}
