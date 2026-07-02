package com.projeto.cortex.intelligence.stavia.interpret;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.projeto.cortex.intelligence.stavia.intent.StaviaClassification;
import com.projeto.cortex.intelligence.stavia.intent.StaviaIntent;
import com.projeto.cortex.intelligence.stavia.llm.OllamaChatClient;
import com.projeto.cortex.intelligence.stavia.model.StaviaQuestion;
import com.projeto.cortex.intelligence.stavia.planning.AggregationSpec;
import com.projeto.cortex.intelligence.stavia.planning.QueryDomain;
import com.projeto.cortex.intelligence.stavia.planning.QueryOperation;
import com.projeto.cortex.intelligence.stavia.planning.ResolvedEntity;
import com.projeto.cortex.intelligence.stavia.planning.StaviaQueryPlan;
import com.projeto.cortex.intelligence.stavia.planning.TemporalFilter;
import com.projeto.cortex.intelligence.stavia.planning.TemporalFilterParser;
import com.projeto.cortex.intelligence.stavia.semantic.StaviaSemanticCatalog;
import com.projeto.cortex.intelligence.stavia.semantic.rdo.RdoOntology;
import com.projeto.cortex.intelligence.stavia.semantic.rdo.RdoOntologyEntity;
import com.projeto.cortex.intelligence.stavia.text.StaviaText;

@Component
public class LlmQuestionInterpreter implements StaviaQuestionInterpreter {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(LlmQuestionInterpreter.class);

    private final OllamaChatClient chatClient;
    private final StaviaInterpretationPromptBuilder promptBuilder;
    private final StaviaSemanticCatalog catalog;
    private final RdoOntology ontology;
    private final TemporalFilterParser temporalParser;
    private final ObjectMapper mapper = new ObjectMapper();

    public LlmQuestionInterpreter(
            OllamaChatClient chatClient,
            StaviaInterpretationPromptBuilder promptBuilder,
            StaviaSemanticCatalog catalog
    ) {
        this(chatClient, promptBuilder, catalog, RdoOntology.load());
    }

    @Autowired
    public LlmQuestionInterpreter(
            OllamaChatClient chatClient,
            StaviaInterpretationPromptBuilder promptBuilder,
            StaviaSemanticCatalog catalog,
            RdoOntology ontology
    ) {
        this.chatClient = chatClient;
        this.promptBuilder = promptBuilder;
        this.catalog = catalog;
        this.ontology = ontology;
        this.temporalParser = new TemporalFilterParser(null);
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

            List<String> ontologyAttributes = new ArrayList<>();
            for (JsonNode attribute : root.path("attributes")) {
                String value = attribute.asText("");
                if (value.contains(".")) {
                    ontologyAttributes.add(value.trim());
                }
            }

            StaviaQueryPlan plan;

            if (!ontologyAttributes.isEmpty()) {
                plan = ontologyPlan(
                        question,
                        root,
                        ontologyAttributes,
                        entities
                );

                if (plan == null) {
                    return Optional.empty();
                }
            } else {
                plan = new StaviaQueryPlan(
                        domainFor(intent), QueryOperation.READ_ATTRIBUTE,
                        entities, TemporalFilter.none(),
                        List.of(), List.of(), List.of(), List.of(),
                        false, false, false);
            }

            return Optional.of(new StaviaInterpretation(
                    new StaviaClassification(intent, confidence), plan, Origin.LLM));
        } catch (RuntimeException | com.fasterxml.jackson.core.JsonProcessingException exception) {
            LOGGER.warn("Interpretação LLM descartada: {}", exception.getMessage());
            return Optional.empty();
        }
    }

    private StaviaQueryPlan ontologyPlan(
            StaviaQuestion question,
            JsonNode root,
            List<String> attributes,
            List<ResolvedEntity> entities
    ) {
        List<RdoOntologyEntity> resolvedEntities = new ArrayList<>();

        for (String qualified : attributes) {
            RdoOntologyEntity entity = resolveEntity(qualified);

            if (entity == null) {
                LOGGER.warn(
                        "Atributo de ontologia desconhecido descartado: {}",
                        qualified
                );
                return null;
            }

            resolvedEntities.add(entity);
        }

        QueryOperation operation =
                switch (root.path("operation").asText("READ")) {
                    case "LIST" -> QueryOperation.LIST_OBJECTS;
                    case "AGGREGATE" -> QueryOperation.AGGREGATE;
                    case "COMPARE" -> QueryOperation.COMPARE;
                    default -> QueryOperation.READ_ATTRIBUTE;
                };

        List<AggregationSpec> aggregations = List.of();
        JsonNode aggregation = root.path("aggregation");

        if (aggregation.isObject()) {
            String aggregateAttribute = aggregation.path("attribute")
                    .asText(attributes.getFirst());

            if (resolveEntity(aggregateAttribute) == null) {
                LOGGER.warn(
                        "Atributo de agregação desconhecido descartado: {}",
                        aggregateAttribute
                );
                return null;
            }

            aggregations = List.of(new AggregationSpec(
                    aggregation.path("function").asText("SUM"),
                    aggregateAttribute,
                    aggregation.path("groupBy").isTextual()
                            ? aggregation.path("groupBy").asText()
                            : null
            ));
        } else if (operation == QueryOperation.AGGREGATE) {
            aggregations = List.of(new AggregationSpec(
                    "SUM", attributes.getFirst(), null
            ));
        }

        List<ResolvedEntity> planEntities = new ArrayList<>(entities);
        String identity = root.path("identity").asText(null);

        if (identity != null && !identity.isBlank()) {
            planEntities.add(new ResolvedEntity(
                    resolvedEntities.getFirst()
                            .name()
                            .toUpperCase(Locale.ROOT),
                    null,
                    "NOME",
                    identity.trim(),
                    false,
                    List.of()
            ));
        }

        boolean latestFallback =
                operation == QueryOperation.READ_ATTRIBUTE
                        || operation == QueryOperation.LIST_OBJECTS;
        TemporalFilter temporal = temporalParser.parse(
                StaviaText.normalize(question.text()),
                latestFallback
        );

        List<String> sources = resolvedEntities.stream()
                .map(RdoOntologyEntity::source)
                .distinct()
                .toList();

        return new StaviaQueryPlan(
                QueryDomain.RDO,
                operation,
                planEntities,
                temporal,
                attributes,
                List.of(),
                aggregations,
                sources,
                temporal.latestCriterion() != null,
                temporal.startDate() != null,
                operation == QueryOperation.COMPARE
        );
    }

    private RdoOntologyEntity resolveEntity(String qualified) {
        int dot = qualified == null ? -1 : qualified.indexOf('.');

        if (dot <= 0) {
            return null;
        }

        String entityName = qualified.substring(0, dot);
        String attributeName = qualified.substring(dot + 1);

        return ontology.entityByName(entityName)
                .filter(entity ->
                        entity.attributeByName(attributeName).isPresent()
                )
                .orElse(null);
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
