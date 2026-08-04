import type {
  ConversaLocalRecord,
  ConversaParticipanteLocal,
  ConversaTipo,
  MensagemAnexoLocalRecord,
  MensagemLocalRecord,
  MensagemSyncStatus,
  OutboxMutationRecord,
} from "../../lib/db/db.types";

export interface BuildQueuedConversationInput {
  tipo: ConversaTipo;
  titulo: string | null;
  obraId: string | null;
  equipeId: string | null;
  /** Quem entra na conversa, além de quem a está criando. */
  participantes: readonly { colaboradorId: string; nome: string }[];
  autorId: string;
  autorNome: string;
  now?: string;
  id?: string;
}

export interface QueuedConversationPlan {
  conversation: ConversaLocalRecord;
  mutation: OutboxMutationRecord;
}

/**
 * A conversa nasce no aparelho, como todo o resto.
 *
 * <p>Criar conversa era a única escrita de Mensagens que falava direto com o
 * servidor: sem rede, o botão ficava desabilitado e não havia como começar
 * uma conversa em campo — justamente onde o Córtex precisa funcionar. O
 * servidor já aceitava id cunhado pelo cliente nesta operação; o que faltava
 * era o cliente usar essa porta.
 *
 * <p>O identificador é do dispositivo e não muda depois: é ele que amarra as
 * mensagens escritas antes de a conversa subir.
 */
export function buildQueuedConversation(
  input: BuildQueuedConversationInput,
): QueuedConversationPlan {
  const now = input.now ?? new Date().toISOString();
  const conversationId = input.id ?? crypto.randomUUID();
  const titulo = input.titulo?.trim() || null;

  if (input.tipo === "DIRETA" && input.participantes.length !== 1) {
    throw new Error("Selecione uma pessoa para a conversa direta.");
  }
  if (input.tipo === "GRUPO" && (!titulo || input.participantes.length < 1)) {
    throw new Error(
      "Informe o nome do grupo e selecione ao menos uma pessoa.",
    );
  }
  if (input.tipo === "OBRA" && !input.obraId) {
    throw new Error("Selecione a obra da conversa.");
  }

  // Quem cria participa, e como administrador: é o que o servidor faz ao
  // aplicar a criação, e a tela precisa mostrar a mesma coisa antes disso.
  const participantes: ConversaParticipanteLocal[] = [
    {
      colaboradorId: input.autorId,
      nome: input.autorNome,
      papel: "ADMIN",
      status: "ATIVO",
      adicionadoEm: now,
    },
    ...input.participantes
      .filter((pessoa) => pessoa.colaboradorId !== input.autorId)
      .map((pessoa) => ({
        colaboradorId: pessoa.colaboradorId,
        nome: pessoa.nome,
        papel: "MEMBRO" as const,
        status: "ATIVO" as const,
        adicionadoEm: now,
      })),
  ];

  const conversation: ConversaLocalRecord = {
    id: conversationId,
    tipo: input.tipo,
    titulo,
    obraId: input.obraId,
    equipeId: input.equipeId,
    status: "ATIVA",
    participantes,
    criadaEm: now,
    atualizadaEm: now,
    // Nula declara que o servidor ainda não viu esta conversa. É por este
    // campo que a reconciliação sabe que não pode apagá-la.
    versaoEntidade: null,
  };

  const mutation: OutboxMutationRecord = {
    clientMutationId: conversationId,
    entidadeTipo: "CONVERSA",
    entidadeId: conversationId,
    operacao: "CRIAR_CONVERSA",
    baseVersao: null,
    payload: {
      id: conversationId,
      tipo: input.tipo,
      titulo,
      obraId: input.obraId,
      equipeId: input.equipeId,
      participanteIds: participantes
        .filter((pessoa) => pessoa.colaboradorId !== input.autorId)
        .map((pessoa) => pessoa.colaboradorId),
      criadaNoClienteEm: now,
    },
    status: "PENDING",
    tentativas: 0,
    ultimaTentativaEm: null,
    ultimoErro: null,
    conflito: null,
    criadaNoClienteEm: now,
    updatedAt: now,
    transport: "SYNC_PUSH",
    dependsOnMutationIds: [],
    correlationId: conversationId,
  };

  return { conversation, mutation };
}

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
  messageMutation: OutboxMutationRecord;
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

  const messageMutation: OutboxMutationRecord = {
    clientMutationId: messageId,
    entidadeTipo: "MENSAGEM",
    entidadeId: messageId,
    operacao: "CRIAR_MENSAGEM",
    baseVersao: null,
    payload: {
      conversaId: input.conversaId,
      corpo: normalizedBody || null,
      criadaNoClienteEm: now,
      anexos: uploadMutations.map((mutation) => ({
        uploadMutationId: mutation.clientMutationId,
      })),
    },
    status: "PENDING",
    tentativas: 0,
    ultimaTentativaEm: null,
    ultimoErro: null,
    conflito: null,
    criadaNoClienteEm: now,
    updatedAt: now,
    transport: "SYNC_PUSH",
    dependsOnMutationIds: uploadMutations.map(
      (mutation) => mutation.clientMutationId,
    ),
    correlationId: messageId,
  };

  return {
    message,
    attachments,
    uploadMutations,
    messageMutation,
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
