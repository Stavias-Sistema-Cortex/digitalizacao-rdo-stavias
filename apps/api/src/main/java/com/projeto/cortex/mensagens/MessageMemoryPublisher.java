package com.projeto.cortex.mensagens;

import com.projeto.cortex.memory.CortexOperationalMemoryService;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class MessageMemoryPublisher {

    private static final String SOURCE = "MENSAGENS_CORTEX";

    private final CortexOperationalMemoryService memoryService;

    public MessageMemoryPublisher(CortexOperationalMemoryService memoryService) {
        this.memoryService = memoryService;
    }

    public void mensagemEnviada(
            ConversationScope conversation,
            MensagemResponse message
    ) {
        memoryService.registrarObjeto(
                "MENSAGEM",
                message.id(),
                message.clientMessageId(),
                "Mensagem de " + message.remetenteNome(),
                message.estado(),
                SOURCE,
                "mensagem",
                Map.of(
                        "conversaId", message.conversaId(),
                        "remetenteId", message.remetenteId()
                )
        );
        memoryService.registrarRelacaoAtiva(
                "MENSAGEM", message.id(),
                "CONVERSA", message.conversaId(),
                "ENVIADA_EM", SOURCE,
                "Mensagem enviada na conversa autorizada."
        );

        List<Map<String, Object>> related = new ArrayList<>();
        related.add(Map.of("tipo", "CONVERSA", "id", message.conversaId()));
        related.add(Map.of("tipo", "COLABORADOR", "id", message.remetenteId()));
        for (MensagemReferenciaResponse reference : message.referencias()) {
            memoryService.registrarRelacaoAtiva(
                    "MENSAGEM", message.id(),
                    reference.tipoObjeto(), reference.objetoId(),
                    "REFERE_SE_A", SOURCE,
                    "Referência operacional validada no envio."
            );
            related.add(Map.of(
                    "tipo", reference.tipoObjeto(),
                    "id", reference.objetoId()
            ));
        }
        for (MensagemAnexoResponse attachment : message.anexos()) {
            memoryService.registrarObjeto(
                    "MENSAGEM_ANEXO",
                    attachment.id(),
                    attachment.clientAttachmentId(),
                    attachment.nomeSeguro(),
                    attachment.status(),
                    SOURCE,
                    "mensagem_anexo",
                    Map.of(
                            "mensagemId", message.id(),
                            "mimeType", attachment.mimeType(),
                            "tamanhoBytes", attachment.tamanhoBytes(),
                            "hashSha256", attachment.hashSha256()
                    )
            );
            memoryService.registrarRelacaoAtiva(
                    "MENSAGEM", message.id(),
                    "MENSAGEM_ANEXO", attachment.id(),
                    "ANEXA", SOURCE,
                    "Anexo protegido associado à mensagem."
            );
            related.add(Map.of("tipo", "MENSAGEM_ANEXO", "id", attachment.id()));
        }

        Map<String, Object> afterState = messageState(message);
        publishEvent(
                "MENSAGEM",
                message.id(),
                "MENSAGEM_ENVIADA",
                conversation.worksiteId(),
                message.remetenteId(),
                message.remetenteId(),
                "ENVIAR_MENSAGEM",
                Map.of(),
                afterState,
                List.copyOf(afterState.keySet()),
                message.versaoEntidade(),
                related
        );
    }

    public void mensagemLida(
            ConversationScope conversation,
            MensagemReciboResponse receipt,
            String actorId
    ) {
        memoryService.registrarObjeto(
                "RECIBO_MENSAGEM",
                receipt.id(),
                receipt.id(),
                "Leitura de mensagem",
                "LIDA",
                SOURCE,
                "mensagem_recibo",
                Map.of(
                        "mensagemId", receipt.mensagemId(),
                        "colaboradorId", receipt.colaboradorId()
                )
        );
        memoryService.registrarRelacaoAtiva(
                "COLABORADOR", receipt.colaboradorId(),
                "MENSAGEM", receipt.mensagemId(),
                "LEU", SOURCE,
                "Recibo idempotente de leitura."
        );

        Map<String, Object> afterState = new LinkedHashMap<>();
        afterState.put("mensagemId", receipt.mensagemId());
        afterState.put("colaboradorId", receipt.colaboradorId());
        afterState.put("entregueEm", receipt.entregueEm());
        afterState.put("lidaEm", receipt.lidaEm());
        publishEvent(
                "RECIBO_MENSAGEM",
                receipt.id(),
                "MENSAGEM_LIDA",
                conversation.worksiteId(),
                receipt.colaboradorId(),
                actorId,
                "MARCAR_MENSAGEM_LIDA",
                Map.of(),
                afterState,
                List.copyOf(afterState.keySet()),
                1,
                List.of(
                        Map.of("tipo", "MENSAGEM", "id", receipt.mensagemId()),
                        Map.of("tipo", "COLABORADOR", "id", receipt.colaboradorId())
                )
        );
    }

    public void mensagemEntregue(
            ConversationScope conversation,
            MensagemReciboResponse receipt,
            String actorId
    ) {
        memoryService.registrarObjeto(
                "RECIBO_MENSAGEM",
                receipt.id(),
                receipt.id(),
                "Entrega de mensagem",
                "ENTREGUE",
                SOURCE,
                "mensagem_recibo",
                Map.of(
                        "mensagemId", receipt.mensagemId(),
                        "colaboradorId", receipt.colaboradorId()
                )
        );
        memoryService.registrarRelacaoAtiva(
                "MENSAGEM", receipt.mensagemId(),
                "COLABORADOR", receipt.colaboradorId(),
                "ENTREGUE_A", SOURCE,
                "Recibo idempotente de entrega."
        );

        Map<String, Object> afterState = new LinkedHashMap<>();
        afterState.put("mensagemId", receipt.mensagemId());
        afterState.put("colaboradorId", receipt.colaboradorId());
        afterState.put("entregueEm", receipt.entregueEm());
        publishEvent(
                "RECIBO_MENSAGEM",
                receipt.id(),
                "MENSAGEM_ENTREGUE",
                conversation.worksiteId(),
                receipt.colaboradorId(),
                actorId,
                "MARCAR_MENSAGEM_ENTREGUE",
                Map.of(),
                afterState,
                List.copyOf(afterState.keySet()),
                1,
                List.of(
                        Map.of("tipo", "MENSAGEM", "id", receipt.mensagemId()),
                        Map.of("tipo", "COLABORADOR", "id", receipt.colaboradorId())
                )
        );
    }

    public void anexoDisponivel(
            ConversationScope conversation,
            MensagemAnexoResponse attachment,
            String actorId
    ) {
        memoryService.registrarObjeto(
                "MENSAGEM_ANEXO",
                attachment.id(),
                attachment.clientAttachmentId(),
                attachment.nomeSeguro(),
                attachment.status(),
                SOURCE,
                "mensagem_anexo",
                Map.of(
                        "mensagemId", attachment.mensagemId(),
                        "mimeType", attachment.mimeType(),
                        "tamanhoBytes", attachment.tamanhoBytes(),
                        "hashSha256", attachment.hashSha256()
                )
        );
        memoryService.registrarRelacaoAtiva(
                "MENSAGEM", attachment.mensagemId(),
                "MENSAGEM_ANEXO", attachment.id(),
                "ANEXA", SOURCE,
                "Conteúdo protegido do anexo disponível."
        );

        Map<String, Object> afterState = new LinkedHashMap<>();
        afterState.put("id", attachment.id());
        afterState.put("mensagemId", attachment.mensagemId());
        afterState.put("status", attachment.status());
        afterState.put("mimeType", attachment.mimeType());
        afterState.put("tamanhoBytes", attachment.tamanhoBytes());
        afterState.put("hashSha256", attachment.hashSha256());
        afterState.put("disponivelEm", attachment.disponivelEm());
        publishEvent(
                "MENSAGEM_ANEXO",
                attachment.id(),
                "ANEXO_MENSAGEM_DISPONIVEL",
                conversation.worksiteId(),
                actorId,
                actorId,
                "DISPONIBILIZAR_ANEXO_MENSAGEM",
                Map.of("status", "PENDENTE"),
                afterState,
                List.copyOf(afterState.keySet()),
                attachment.versaoEntidade(),
                List.of(
                        Map.of(
                                "tipo", "MENSAGEM",
                                "id", attachment.mensagemId()
                        ),
                        Map.of("tipo", "CONVERSA", "id", conversation.id())
                )
        );
    }

    private Map<String, Object> messageState(MensagemResponse message) {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("id", message.id());
        state.put("conversaId", message.conversaId());
        state.put("remetenteId", message.remetenteId());
        state.put("remetenteNome", message.remetenteNome());
        state.put("clientMessageId", message.clientMessageId());
        state.put("texto", message.texto());
        state.put("estado", message.estado());
        state.put("enviadaClienteEm", message.enviadaClienteEm());
        state.put("criadaServidorEm", message.criadaServidorEm());
        state.put("atualizadaEm", message.atualizadaEm());
        state.put("referencias", message.referencias().stream()
                .map(this::referenceState)
                .toList());
        state.put("anexos", message.anexos().stream()
                .map(this::attachmentState)
                .toList());
        state.put("recibos", message.recibos().stream()
                .map(this::receiptState)
                .toList());
        return state;
    }

    private Map<String, Object> referenceState(
            MensagemReferenciaResponse reference
    ) {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("id", reference.id());
        state.put("mensagemId", reference.mensagemId());
        state.put("tipoObjeto", reference.tipoObjeto());
        state.put("objetoId", reference.objetoId());
        state.put("obraId", reference.obraId());
        state.put("criadoEm", reference.criadoEm());
        return state;
    }

    private Map<String, Object> attachmentState(
            MensagemAnexoResponse attachment
    ) {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("id", attachment.id());
        state.put("mensagemId", attachment.mensagemId());
        state.put("clientAttachmentId", attachment.clientAttachmentId());
        state.put("nomeOriginal", attachment.nomeOriginal());
        state.put("nomeSeguro", attachment.nomeSeguro());
        state.put("mimeType", attachment.mimeType());
        state.put("tamanhoBytes", attachment.tamanhoBytes());
        state.put("hashSha256", attachment.hashSha256());
        state.put("status", attachment.status());
        state.put("ultimoErro", attachment.ultimoErro());
        state.put("criadoEm", attachment.criadoEm());
        state.put("atualizadoEm", attachment.atualizadoEm());
        state.put("disponivelEm", attachment.disponivelEm());
        state.put("versaoEntidade", attachment.versaoEntidade());
        return state;
    }

    private Map<String, Object> receiptState(MensagemReciboResponse receipt) {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("id", receipt.id());
        state.put("mensagemId", receipt.mensagemId());
        state.put("colaboradorId", receipt.colaboradorId());
        state.put("entregueEm", receipt.entregueEm());
        state.put("lidaEm", receipt.lidaEm());
        return state;
    }

    private void publishEvent(
            String entityType,
            String entityId,
            String eventType,
            String worksiteId,
            String collaboratorId,
            String actorId,
            String operation,
            Map<String, Object> beforeState,
            Map<String, Object> afterState,
            List<String> changedFields,
            long entityVersion,
            List<Map<String, Object>> relatedEntities
    ) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("schemaVersion", 1);
        payload.put("actorId", actorId);
        payload.put("operation", operation);
        payload.put("beforeState", beforeState);
        payload.put("afterState", afterState);
        payload.put("changedFields", changedFields);
        payload.put("entityVersion", entityVersion);
        payload.put("worksiteId", worksiteId);

        LocalDateTime now = LocalDateTime.now();
        memoryService.registrarEventoDetalhado(
                UUID.randomUUID().toString(),
                entityType,
                entityId,
                eventType,
                SOURCE,
                worksiteId,
                null,
                collaboratorId,
                relatedEntities,
                "ONLINE",
                "SYNCED",
                now,
                now,
                1,
                payload
        );
    }
}
