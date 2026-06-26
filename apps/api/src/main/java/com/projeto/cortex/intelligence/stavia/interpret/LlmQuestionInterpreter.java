package com.projeto.cortex.intelligence.stavia.interpret;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.projeto.cortex.intelligence.stavia.intent.StaviaClassification;
import com.projeto.cortex.intelligence.stavia.intent.StaviaIntent;
import com.projeto.cortex.intelligence.stavia.llm.OllamaChatClient;
import com.projeto.cortex.intelligence.stavia.model.StaviaQuestion;
import com.projeto.cortex.intelligence.stavia.planning.QueryDomain;
import com.projeto.cortex.intelligence.stavia.planning.QueryOperation;
import com.projeto.cortex.intelligence.stavia.planning.ResolvedEntity;
import com.projeto.cortex.intelligence.stavia.planning.StaviaQueryPlan;
import com.projeto.cortex.intelligence.stavia.planning.TemporalFilter;
import com.projeto.cortex.intelligence.stavia.semantic.StaviaSemanticCatalog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Component
public class LlmQuestionInterpreter implements StaviaQuestionInterpreter {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(LlmQuestionInterpreter.class);

    private final OllamaChatClient chatClient;
    private final StaviaInterpretationPromptBuilder promptBuilder;
    private final StaviaSemanticCatalog catalog;
    private final ObjectMapper mapper = new ObjectMapper();

    public LlmQuestionInterpreter(
            OllamaChatClient chatClient,
            StaviaInterpretationPromptBuilder promptBuilder,
            StaviaSemanticCatalog catalog
    ) {
        this.chatClient = chatClient;
        this.promptBuilder = promptBuilder;
        this.catalog = catalog;
    }

    @Override
    public Optional<StaviaInterpretation> interpret(StaviaQuestion question) {
        try {
            String content = chatClient.chat(promptBuilder.build(question), 0.0);
            JsonNode root = mapper.readTree(content);

            StaviaIntent intent = parseIntent(root.path("intent").asText(null));
            if (intent == null) {
                return Optional.empty();
            }

            double confidence = root.path("confidence").asDouble(0.0);
            if (confidence < 0.0 || confidence > 1.0) {
                return Optional.empty();
            }

            List<ResolvedEntity> entities = new ArrayList<>();
            if (question.obraId() != null) {
                entities.add(ResolvedEntity.worksiteById(question.obraId()));
            }
            for (JsonNode entity : root.path("entities")) {
                String type = entity.path("type").asText("");
                String value = entity.path("value").asText("");
                if (value.isBlank()) {
                    continue;
                }
                switch (type) {
                    case "COLABORADOR" -> entities.add(ResolvedEntity.collaboratorByName(value));
                    case "ROLE" -> entities.add(ResolvedEntity.roleByLabel(value));
                    default -> { /* tipos não suportados são ignorados com segurança */ }
                }
            }

            StaviaQueryPlan plan = new StaviaQueryPlan(
                    domainFor(intent), QueryOperation.READ_ATTRIBUTE,
                    entities, TemporalFilter.none(),
                    List.of(), List.of(), List.of(), List.of(),
                    false, false, false);

            return Optional.of(new StaviaInterpretation(
                    new StaviaClassification(intent, confidence), plan, Origin.LLM));
        } catch (RuntimeException | com.fasterxml.jackson.core.JsonProcessingException exception) {
            LOGGER.warn("Interpretação LLM descartada: {}", exception.getMessage());
            return Optional.empty();
        }
    }

    private StaviaIntent parseIntent(String value) {
        if (value == null) {
            return null;
        }
        try {
            return StaviaIntent.valueOf(value.trim());
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private QueryDomain domainFor(StaviaIntent intent) {
        return switch (intent) {
            case CONSULTAR_RDO, CONSULTAR_PROGRAMACAO -> QueryDomain.RDO;
            case CONSULTAR_EQUIPE -> QueryDomain.EQUIPE;
            case CONSULTAR_ALOCACAO_COLABORADOR, CONSULTAR_FREQUENCIA, CONSULTAR_BANCO_HORAS ->
                    QueryDomain.COLABORADOR;
            case CONSULTAR_ATIVO -> QueryDomain.EQUIPAMENTO;
            case CONSULTAR_RECEITA, CONSULTAR_MARGEM, CONSULTAR_PREVISAO_FINANCEIRA,
                    CONSULTAR_PRODUCAO, CONSULTAR_RECEITA_EM_RISCO, CONSULTAR_PDOC ->
                    QueryDomain.FINANCEIRO;
            case CONSULTAR_OBRA, CONSULTAR_ESTADO_ATUAL -> QueryDomain.OBRA;
            default -> QueryDomain.DESCONHECIDO;
        };
    }
}
