import type {
  MensagemAnexoLocalRecord,
  MensagemLocalRecord,
  MensagemSyncStatus,
  OutboxMutationRecord,
} from "../../lib/db/db.types";

export interface BuildQueuedMessageInput {
  conversaId: string;
  autorId: string;
  autorNome: string;
  corpo: string;
  files: File[];
  now?: string;
  ids?: string[];
}

export interface QueuedMessagePlan {
  message: MensagemLocalRecord;
  attachments: MensagemAnexoLocalRecord[];
  uploadMutations: OutboxMutationRecord[];
}

const STATUS_LABELS: Record<MensagemSyncStatus, string> = {
  LOCAL: "Local",
  NA_FILA: "Na fila",
  SINCRONIZANDO: "Sincronizando",
  SINCRONIZADO: "Sincronizado",
  FALHOU: "Falhou",
};

export function mensagemStatusLabel(
  status: MensagemSyncStatus,
): string {
  return STATUS_LABELS[status];
}

export function buildQueuedMessage(
  input: BuildQueuedMessageInput,
): QueuedMessagePlan {
  const now = input.now ?? new Date().toISOString();
  const nextId = idSource(input.ids);
  const messageId = nextId();
  const normalizedBody = input.corpo.trim();

  if (!normalizedBody && input.files.length === 0) {
    throw new Error("Escreva uma mensagem ou selecione um anexo.");
  }

  const uploadMutations: OutboxMutationRecord[] = [];
  const attachments = input.files.map((file, ordem) => {
    const attachmentId = nextId();
    const uploadMutationId = nextId();
    uploadMutations.push({
      clientMutationId: uploadMutationId,
      entidadeTipo: "MENSAGEM_ANEXO",
      entidadeId: attachmentId,
      operacao: "ADICIONAR_MENSAGEM_ANEXO",
      baseVersao: null,
      payload: {
        attachmentId,
        mensagemId: messageId,
        conversaId: input.conversaId,
      },
      status: "PENDING",
      tentativas: 0,
      ultimaTentativaEm: null,
      ultimoErro: null,
      conflito: null,
      criadaNoClienteEm: now,
      updatedAt: now,
      transport: "OBJECT_UPLOAD",
      dependsOnMutationIds: [],
      correlationId: uploadMutationId,
    });

    return {
      id: attachmentId,
      mensagemId: messageId,
      conversaId: input.conversaId,
      objetoId: null,
      uploadMutationId,
      nome: file.name || `anexo-${ordem + 1}`,
      mediaType: file.type || "application/octet-stream",
      tamanhoBytes: file.size,
      sha256: null,
      ordem,
      arquivo: file,
      syncStatus: "NA_FILA" as const,
      ultimoErro: null,
      createdAt: now,
      updatedAt: now,
    } satisfies MensagemAnexoLocalRecord;
  });

  const message: MensagemLocalRecord = {
    id: messageId,
    conversaId: input.conversaId,
    autorId: input.autorId,
    autorNome: input.autorNome,
    corpo: normalizedBody || null,
    status: "ATIVA",
    clientMutationId: messageId,
    criadaNoClienteEm: now,
    criadaEm: null,
    editadaEm: null,
    deletadaEm: null,
    versaoEntidade: null,
    syncStatus: "NA_FILA",
    ultimoErro: null,
    updatedAt: now,
  };

  return {
    message,
    attachments,
    uploadMutations,
  };
}

function idSource(ids?: string[]): () => string {
  let index = 0;
  return () => {
    const provided = ids?.[index];
    index += 1;
    if (provided) {
      return provided;
    }
    return crypto.randomUUID();
  };
}
