export type StaviaConfidence =
  | "ALTA"
  | "MEDIA"
  | "BAIXA"
  | "INDETERMINADA";

export type StaviaAnswerType =
  | "FATO"
  | "RESUMO"
  | "INFORMACAO_INSUFICIENTE"
  | string;

export interface StaviaEvidence {
  type: string;
  id: string;
  summary: string;
  updatedAt: string | null;
  validated: boolean;
  attributes: Record<string, unknown>;
}

export interface StaviaAnswer {
  answer: string;
  confidence: StaviaConfidence;
  answerType: StaviaAnswerType;
  sources: StaviaEvidence[];
  insufficientData: boolean;
  warnings: string[];
  metadata: Record<string, unknown> | null;
}

export interface StaviaConsultaRequest {
  pergunta: string;
  usuarioId: string;
  obraId: string;
}

export interface StaviaConsultaResponse {
  answer: StaviaAnswer;
  intent: string;
  consultedKnowledgeSources: Record<string, string>;
  knowledgeWarnings: string[];
}
