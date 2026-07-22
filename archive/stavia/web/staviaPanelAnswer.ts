import { consultarStavia } from "./staviaApi";
import {
  responderComSnapshotStavia,
  type StaviaLocalContext,
} from "./staviaLocalEngine";
import { getBestAvailableStaviaSnapshot } from "./staviaSnapshotStorage";
import type {
  StaviaConsultaRequest,
  StaviaConsultaResponse,
  StaviaSnapshot,
} from "./stavia.types";

export interface StaviaPanelLastContext {
  obraId: string | null;
  rdoId: string | null;
}

interface AnswerStaviaPanelQuestionParams {
  snapshot: StaviaSnapshot | null;
  questionText: string;
  contextHint: string;
  activeObraId: string;
  activeRdoId: string;
  lastContext: StaviaPanelLastContext;
  isOnline: boolean;
  loadSnapshot?: () => Promise<StaviaSnapshot | null>;
  consultar?: (
    request: StaviaConsultaRequest,
  ) => Promise<StaviaConsultaResponse>;
}

function trimToNull(value: string | null | undefined): string | null {
  const trimmed = value?.trim() ?? "";
  return trimmed || null;
}

export function buildStaviaPanelLocalContext({
  activeObraId,
  activeRdoId,
  lastContext,
}: {
  activeObraId: string;
  activeRdoId: string;
  lastContext: StaviaPanelLastContext;
}): StaviaLocalContext {
  const normalizedActiveObraId = trimToNull(activeObraId);
  const normalizedActiveRdoId = trimToNull(activeRdoId);

  return {
    activeObraId: normalizedActiveObraId,
    activeRdoId: normalizedActiveRdoId,
    lastObraId: normalizedActiveObraId ?? lastContext.obraId,
    lastRdoId: normalizedActiveRdoId ?? lastContext.rdoId,
  };
}

export async function answerStaviaPanelQuestion({
  snapshot,
  questionText,
  contextHint,
  activeObraId,
  activeRdoId,
  lastContext,
  isOnline,
  loadSnapshot = getBestAvailableStaviaSnapshot,
  consultar = consultarStavia,
}: AnswerStaviaPanelQuestionParams): Promise<StaviaConsultaResponse> {
  let loadedSnapshot: StaviaSnapshot | null = null;

  try {
    loadedSnapshot = await loadSnapshot();
  } catch (error) {
    if (!snapshot) {
      throw error;
    }
  }

  const latestSnapshot = loadedSnapshot ?? snapshot;
  const selectedContextText = trimToNull(contextHint);
  const localContext = buildStaviaPanelLocalContext({
    activeObraId,
    activeRdoId,
    lastContext,
  });

  if (latestSnapshot) {
    const localResponse = responderComSnapshotStavia({
      snapshot: latestSnapshot,
      pergunta: questionText,
      contextoSelecionado: selectedContextText,
      context: localContext,
      isOnline,
    });

    if (localResponse) {
      return localResponse;
    }
  }

  if (!isOnline) {
    throw new Error(
      "Estou offline e não encontrei essa resposta no snapshot salvo. Atualize a base quando a internet voltar.",
    );
  }

  return consultar({
    pergunta: questionText,
    usuarioId: "frontend-local",
    obraId: localContext.activeObraId,
    rdoId: localContext.activeRdoId,
    contextoSelecionado: selectedContextText,
    ultimoObraId: localContext.lastObraId,
    ultimoRdoId: localContext.lastRdoId,
  });
}
