package com.projeto.cortex.intelligence.stavia.knowledge.messaging;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.projeto.cortex.intelligence.stavia.StaviaEngine;
import com.projeto.cortex.intelligence.stavia.intent.StaviaIntent;
import com.projeto.cortex.intelligence.stavia.knowledge.StaviaKnowledgeRequest;
import com.projeto.cortex.intelligence.stavia.knowledge.StaviaKnowledgeSource;
import com.projeto.cortex.intelligence.stavia.knowledge.registry.StaviaSourceDescriptor;
import com.projeto.cortex.intelligence.stavia.model.StaviaEvidence;
import com.projeto.cortex.intelligence.stavia.model.StaviaEvidenceTypes;
import com.projeto.cortex.intelligence.stavia.planning.QueryDomain;
import com.projeto.cortex.intelligence.stavia.planning.QueryOperation;
import com.projeto.cortex.intelligence.stavia.text.StaviaText;
import org.springframework.stereotype.Component;

import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
public class MessageKnowledgeSource implements StaviaKnowledgeSource {

    private final MessageKnowledgeReader reader;
    private final ObjectMapper objectMapper;

    public MessageKnowledgeSource(MessageKnowledgeReader reader, ObjectMapper objectMapper) {
        this.reader = reader;
        this.objectMapper = objectMapper;
    }

    @Override
    public String sourceName() {
        return "mensagens-autorizadas";
    }

    @Override
    public String sourceVersion() {
        return "STAVIA-MESSAGING-SOURCE-0.1.0";
    }

    @Override
    public StaviaSourceDescriptor descriptor() {
        return new StaviaSourceDescriptor(
                sourceName(), sourceVersion(), Set.of(QueryDomain.OBRA),
                Set.of("MENSAGEM", "MENSAGEM_ANEXO"),
                Set.of("texto", "remetente", "referencias", "anexos"),
                Set.of("REFERENCIA", "ANEXA"),
                Set.of(QueryOperation.LIST_OBJECTS, QueryOperation.SUMMARIZE),
                Set.of(StaviaEngine.REQUIRED_PERMISSION), "Sob demanda", 20, 50
        );
    }

    @Override
    public boolean supports(StaviaKnowledgeRequest request) {
        return request != null
                && request.permissions().contains(StaviaEngine.REQUIRED_PERMISSION)
                && request.intent() == StaviaIntent.CONSULTAR_HISTORICO
                && mentionsMessages(request.question().text());
    }

    @Override
    public List<StaviaEvidence> retrieve(StaviaKnowledgeRequest request) {
        List<MessageKnowledgeRecord> records = reader.findAuthorizedByWorksite(
                request.worksiteId(), request.question().userId()
        );
        if (records.isEmpty()) {
            return List.of(noEvidence(request.worksiteId()));
        }
        return records.stream().map(this::toEvidence).toList();
    }

    private StaviaEvidence toEvidence(MessageKnowledgeRecord record) {
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("obraId", record.worksiteId());
        attributes.put("mensagemId", record.id());
        attributes.put("conversaId", record.conversationId());
        attributes.put("tipoConversa", record.conversationType());
        attributes.put("remetenteId", record.senderId());
        attributes.put("remetenteNome", record.senderName());
        attributes.put("encontrado", true);
        attributes.put("sourceMode", "MESSAGING");
        attributes.put("permissaoAplicada", "PARTICIPANTE_ATIVO_E_ACESSO_A_OBRA");
        attributes.put("linkInterno", "/mensagens?conversa=" + record.conversationId()
                + "&mensagem=" + record.id());
        putText(attributes, "tituloConversa", record.conversationTitle());
        putText(attributes, "texto", record.text());
        putJson(attributes, "referencias", record.referencesJson());
        putJson(attributes, "anexos", record.attachmentsJson());
        if (record.sentAt() != null) attributes.put("enviadaEm", record.sentAt().toString());

        String summary = "Mensagem " + record.id()
                + " enviada por " + record.senderName()
                + (record.text() == null || record.text().isBlank()
                        ? " com conteúdo em anexo."
                        : ": " + record.text());
        return new StaviaEvidence(
                StaviaEvidenceTypes.MENSAGEM,
                record.id(),
                summary,
                record.sentAt() == null ? null : record.sentAt().toInstant(ZoneOffset.UTC),
                true,
                attributes
        );
    }

    private StaviaEvidence noEvidence(String worksiteId) {
        return new StaviaEvidence(
                StaviaEvidenceTypes.MENSAGEM,
                "MENSAGEM:NENHUMA:" + worksiteId,
                "Nenhuma mensagem autorizada relacionada à obra foi encontrada.",
                null,
                false,
                Map.of(
                        "obraId", worksiteId,
                        "encontrado", false,
                        "sourceMode", "MESSAGING",
                        "permissaoAplicada", "PARTICIPANTE_ATIVO_E_ACESSO_A_OBRA"
                )
        );
    }

    private boolean mentionsMessages(String question) {
        String normalized = StaviaText.normalize(question);
        return List.of("mensagem", "mensagens", "conversa", "chat", "anexo", "arquivo compartilhado")
                .stream().anyMatch(normalized::contains);
    }

    private void putJson(Map<String, Object> target, String key, String json) {
        try {
            JsonNode value = json == null ? null : objectMapper.readTree(json);
            if (value != null && !value.isNull()) target.put(key, value);
        } catch (Exception ignored) {
            // JSON inválido não vira texto inventado; a evidência principal permanece.
        }
    }

    private void putText(Map<String, Object> target, String key, String value) {
        if (value != null && !value.isBlank()) target.put(key, value);
    }
}
