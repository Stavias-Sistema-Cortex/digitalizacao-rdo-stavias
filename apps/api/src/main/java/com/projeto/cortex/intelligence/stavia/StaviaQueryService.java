package com.projeto.cortex.intelligence.stavia;

import com.projeto.cortex.financeiro.access.FinancialPermission;
import com.projeto.cortex.intelligence.stavia.access.StaviaAccessPolicy;
import com.projeto.cortex.intelligence.stavia.context.StaviaContextBuilder;
import com.projeto.cortex.intelligence.stavia.context.StaviaContextResolution;
import com.projeto.cortex.intelligence.stavia.context.StaviaContextResolutionService;
import com.projeto.cortex.intelligence.stavia.context.StaviaRawContext;
import com.projeto.cortex.intelligence.stavia.intent.StaviaIntent;
import com.projeto.cortex.intelligence.stavia.intent.StaviaIntentClassifier;
import com.projeto.cortex.intelligence.stavia.interpret.DeterministicQuestionInterpreter;
import com.projeto.cortex.intelligence.stavia.interpret.StaviaInterpretation;
import com.projeto.cortex.intelligence.stavia.interpret.StaviaInterpretationCoordinator;
import com.projeto.cortex.intelligence.stavia.knowledge.StaviaKnowledgeBundle;
import com.projeto.cortex.intelligence.stavia.knowledge.StaviaKnowledgeOrchestrator;
import com.projeto.cortex.intelligence.stavia.knowledge.StaviaKnowledgeRequest;
import com.projeto.cortex.intelligence.stavia.model.StaviaAnswer;
import com.projeto.cortex.intelligence.stavia.model.StaviaAnswerType;
import com.projeto.cortex.intelligence.stavia.model.StaviaConfidence;
import com.projeto.cortex.intelligence.stavia.model.StaviaContext;
import com.projeto.cortex.intelligence.stavia.model.StaviaEvidence;
import com.projeto.cortex.intelligence.stavia.model.StaviaQuestion;
import com.projeto.cortex.intelligence.stavia.planning.StaviaQueryPlan;
import com.projeto.cortex.intelligence.stavia.planning.StaviaQueryPlanner;
import com.projeto.cortex.intelligence.stavia.semantic.StaviaSemanticCatalog;
import com.projeto.cortex.intelligence.stavia.text.StaviaText;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

@Service
public class StaviaQueryService {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(StaviaQueryService.class);

    /** Aviso emitido quando o acesso à obra é negado; usado também na auditoria. */
    public static final String AVISO_SEM_ACESSO =
            "O usuário não tem acesso a esta obra.";

    private final StaviaIntentClassifier intentClassifier;
    private final StaviaKnowledgeOrchestrator knowledgeOrchestrator;
    private final StaviaContextBuilder contextBuilder;
    private final StaviaEngine engine;
    private final StaviaAccessPolicy accessPolicy;
    private final StaviaQueryPlanner queryPlanner;
    private final StaviaInterpretationCoordinator coordinator;
    private final StaviaContextResolutionService contextResolutionService;

    @Autowired
    public StaviaQueryService(
            StaviaIntentClassifier intentClassifier,
            StaviaKnowledgeOrchestrator knowledgeOrchestrator,
            StaviaContextBuilder contextBuilder,
            StaviaEngine engine,
            StaviaAccessPolicy accessPolicy,
            StaviaInterpretationCoordinator coordinator,
            ObjectProvider<StaviaContextResolutionService> contextResolutionService
    ) {
        this.intentClassifier = require(
                intentClassifier,
                "O classificador de intenção deve ser informado."
        );

        this.knowledgeOrchestrator = require(
                knowledgeOrchestrator,
                "O orquestrador de conhecimento deve ser informado."
        );

        this.contextBuilder = require(
                contextBuilder,
                "O builder de contexto deve ser informado."
        );

        this.engine = require(
                engine,
                "O motor da Stav.IA deve ser informado."
        );

        this.accessPolicy = require(
                accessPolicy,
                "A política de acesso da Stav.IA deve ser informada."
        );

        this.queryPlanner = new StaviaQueryPlanner(new StaviaSemanticCatalog());

        this.coordinator = require(
                coordinator,
                "O coordinator de interpretação da Stav.IA deve ser informado."
        );

        this.contextResolutionService = contextResolutionService == null
                ? null
                : contextResolutionService.getIfAvailable();
    }

    public StaviaQueryService(
            StaviaIntentClassifier intentClassifier,
            StaviaKnowledgeOrchestrator knowledgeOrchestrator,
            StaviaContextBuilder contextBuilder,
            StaviaEngine engine,
            StaviaAccessPolicy accessPolicy
    ) {
        this(
                intentClassifier,
                knowledgeOrchestrator,
                contextBuilder,
                engine,
                accessPolicy,
                new StaviaQueryPlanner(
                        new StaviaSemanticCatalog()
                )
        );
    }

    public StaviaQueryService(
            StaviaIntentClassifier intentClassifier,
            StaviaKnowledgeOrchestrator knowledgeOrchestrator,
            StaviaContextBuilder contextBuilder,
            StaviaEngine engine,
            StaviaAccessPolicy accessPolicy,
            StaviaInterpretationCoordinator coordinator
    ) {
        this.intentClassifier = require(
                intentClassifier,
                "O classificador de intenção deve ser informado."
        );

        this.knowledgeOrchestrator = require(
                knowledgeOrchestrator,
                "O orquestrador de conhecimento deve ser informado."
        );

        this.contextBuilder = require(
                contextBuilder,
                "O builder de contexto deve ser informado."
        );

        this.engine = require(
                engine,
                "O motor da Stav.IA deve ser informado."
        );

        this.accessPolicy = require(
                accessPolicy,
                "A política de acesso da Stav.IA deve ser informada."
        );

        this.queryPlanner = new StaviaQueryPlanner(new StaviaSemanticCatalog());
        this.coordinator = require(
                coordinator,
                "O coordinator de interpretação da Stav.IA deve ser informado."
        );
        this.contextResolutionService = null;
    }

    public StaviaQueryService(
            StaviaIntentClassifier intentClassifier,
            StaviaKnowledgeOrchestrator knowledgeOrchestrator,
            StaviaContextBuilder contextBuilder,
            StaviaEngine engine,
            StaviaAccessPolicy accessPolicy,
            StaviaQueryPlanner queryPlanner
    ) {
        this.intentClassifier = require(
                intentClassifier,
                "O classificador de intenção deve ser informado."
        );

        this.knowledgeOrchestrator = require(
                knowledgeOrchestrator,
                "O orquestrador de conhecimento deve ser informado."
        );

        this.contextBuilder = require(
                contextBuilder,
                "O builder de contexto deve ser informado."
        );

        this.engine = require(
                engine,
                "O motor da Stav.IA deve ser informado."
        );

        this.accessPolicy = require(
                accessPolicy,
                "A política de acesso da Stav.IA deve ser informada."
        );

        this.queryPlanner = require(
                queryPlanner,
                "O planejador de consultas da Stav.IA deve ser informado."
        );

        this.coordinator = new StaviaInterpretationCoordinator(
                new DeterministicQuestionInterpreter(intentClassifier, queryPlanner),
                null, "deterministic", 0.45);
        this.contextResolutionService = null;
    }

    public StaviaQueryResult query(
            StaviaQuestion question
    ) {
        if (question == null) {
            throw new IllegalArgumentException(
                    "A pergunta deve ser informada."
            );
        }

        Set<String> normalizedPermissions =
                Set.copyOf(
                        accessPolicy.permissionsFor(
                                question.userId()
                        )
                );

        if (question.obraId() == null || question.obraId().isBlank()) {
            StaviaContextResolution resolution =
                    resolveContext(question);

            if (resolution.found()) {
                question = new StaviaQuestion(
                        question.text(),
                        question.userId(),
                        resolution.obraId()
                );
            } else {
                return unresolvedContextResult(resolution);
            }
        }

        if (
                !accessPolicy.canAccessWorksite(
                        question.userId(),
                        question.obraId()
                )
        ) {
            LOGGER.warn(
                    "Consulta Stav.IA negada por obra. worksiteIdPresent={} userIdPresent={}",
                    question.obraId() != null && !question.obraId().isBlank(),
                    question.userId() != null && !question.userId().isBlank()
            );

            StaviaAnswer answer =
                    engine.answer(
                            question,
                            new StaviaContext(
                                    Set.of(),
                                    List.of()
                            )
                    );

            return new StaviaQueryResult(
                    answer,
                    StaviaIntent.DESCONHECIDA,
                    0.0,
                    java.util.Map.of(),
                    List.of(AVISO_SEM_ACESSO)
            );
        }

        final StaviaQuestion effectiveQuestion = question;
        StaviaInterpretation interpretation =
                coordinator.interpret(effectiveQuestion);
        StaviaQueryPlan plan = interpretation.plan();
        StaviaIntent intent = interpretation.intent();
        StaviaIntent directlyClassifiedIntent = intentClassifier.classify(
                effectiveQuestion.text()
        );
        boolean financialIntent = isFinancialIntent(intent)
                || isFinancialIntent(directlyClassifiedIntent)
                || isExplicitFinancialProductionQuestion(
                        effectiveQuestion.text(),
                        intent,
                        directlyClassifiedIntent
                )
                || plan.domain()
                == com.projeto.cortex.intelligence.stavia.planning
                        .QueryDomain.FINANCEIRO;

        Set<String> queryPermissions = normalizedPermissions;
        if (financialIntent) {
            if (!accessPolicy.canAccessFinancial(
                    effectiveQuestion.userId(),
                    effectiveQuestion.obraId()
            )) {
                LOGGER.warn(
                        "Consulta Stav.IA financeira negada. worksiteIdPresent={} userIdPresent={}",
                        effectiveQuestion.obraId() != null,
                        effectiveQuestion.userId() != null
                );
                StaviaAnswer answer = engine.answer(
                        effectiveQuestion,
                        new StaviaContext(Set.of(), List.of())
                );
                return new StaviaQueryResult(
                        answer,
                        intent,
                        interpretation.classification().confidence(),
                        java.util.Map.of(),
                        List.of(AVISO_SEM_ACESSO)
                );
            }
            LinkedHashSet<String> granted = new LinkedHashSet<>(
                    normalizedPermissions
            );
            granted.add(
                    FinancialPermission.FINANCEIRO_VISUALIZAR.name()
            );
            queryPermissions = Set.copyOf(granted);
        }

        LOGGER.info(
                "Planner Stav.IA concluído. classifiedIntent={} effectiveIntent={} planDomain={} operation={} requestedAttributes={} requiredSources={}",
                interpretation.classification().intent(),
                intent,
                plan.domain(),
                plan.operation(),
                plan.requestedAttributes().size(),
                plan.requiredSources()
        );

        StaviaKnowledgeRequest knowledgeRequest =
                new StaviaKnowledgeRequest(
                        effectiveQuestion,
                        intent,
                        effectiveQuestion.obraId(),
                        queryPermissions,
                        plan
                );

        StaviaKnowledgeBundle knowledgeBundle =
                knowledgeOrchestrator.retrieve(
                        knowledgeRequest
                );

        StaviaRawContext rawContext =
                new StaviaRawContext(
                        effectiveQuestion.userId(),
                        effectiveQuestion.obraId(),
                        queryPermissions,
                        knowledgeBundle.evidences()
                                .stream()
                                .map(evidence ->
                                        toRawEvidence(
                                                effectiveQuestion,
                                                evidence,
                                                allowsCrossWorksiteEvidence(plan)
                                        )
                                )
                                .filter(Objects::nonNull)
                                .toList()
                );

        StaviaContext context =
                contextBuilder.build(rawContext);

        StaviaAnswer answer =
                engine.answer(
                        effectiveQuestion,
                        context,
                        intent
                );

        return new StaviaQueryResult(
                answer,
                intent,
                interpretation.classification().confidence(),
                knowledgeBundle.consultedSources(),
                knowledgeBundle.warnings()
        );
    }

    private boolean isFinancialIntent(StaviaIntent intent) {
        return switch (intent) {
            case CONSULTAR_PDOR,
                    CONSULTAR_RECEITA,
                    CONSULTAR_MARGEM,
                    CONSULTAR_PREVISAO_FINANCEIRA,
                    CONSULTAR_RECEITA_EM_RISCO -> true;
            default -> false;
        };
    }

    private boolean isExplicitFinancialProductionQuestion(
            String text,
            StaviaIntent interpreted,
            StaviaIntent classified
    ) {
        if (interpreted != StaviaIntent.CONSULTAR_PRODUCAO
                && classified != StaviaIntent.CONSULTAR_PRODUCAO) {
            return false;
        }
        String normalized = StaviaText.normalize(text);
        return StaviaText.containsWord(normalized, "previsao financeira")
                || StaviaText.containsWord(normalized, "financeiro da obra")
                || StaviaText.containsWord(normalized, "receita")
                || StaviaText.containsWord(normalized, "margem");
    }

    private StaviaRawContext.RawEvidence toRawEvidence(
            StaviaQuestion question,
            StaviaEvidence evidence,
            boolean allowsCrossWorksiteEvidence
    ) {
        String evidenceWorksiteId = textAttribute(
                evidence.attributes(),
                "obraId"
        );

        if (evidenceWorksiteId == null) {
            evidenceWorksiteId = question.obraId();
        }

        boolean crossWorksite = allowsCrossWorksiteEvidence
                && !question.obraId().equals(evidenceWorksiteId);

        if (crossWorksite && !accessPolicy.canAccessWorksite(
                question.userId(),
                evidenceWorksiteId
        )) {
            LOGGER.warn(
                    "Evidência Stav.IA de outra obra removida por falta de acesso. evidenceWorksiteIdPresent={}",
                    !evidenceWorksiteId.isBlank()
            );
            return null;
        }

        Map<String, Object> attributes = new LinkedHashMap<>(
                evidence.attributes()
        );
        if (crossWorksite) {
            attributes.put("crossWorksiteAuthorized", true);
        }

        return new StaviaRawContext.RawEvidence(
                evidence.type(),
                evidence.id(),
                evidenceWorksiteId,
                StaviaEngine.REQUIRED_PERMISSION,
                evidence.summary(),
                evidence.updatedAt(),
                evidence.validated(),
                attributes
        );
    }

    private boolean allowsCrossWorksiteEvidence(StaviaQueryPlan plan) {
        return plan != null
                && plan.domain()
                        == com.projeto.cortex.intelligence.stavia.planning.QueryDomain.COLABORADOR
                && plan.operation()
                        == com.projeto.cortex.intelligence.stavia.planning.QueryOperation.TRAVERSE_RELATIONSHIP
                && (
                    plan.requestedRelationships().contains("ALOCADO_EM")
                            || plan.requestedRelationships().contains(
                                    "PERFIL_COLABORADOR"
                            )
                );
    }

    private StaviaContextResolution resolveContext(
            StaviaQuestion question
    ) {
        if (contextResolutionService == null) {
            return StaviaContextResolution.notFound();
        }

        try {
            return contextResolutionService.resolve(question);
        } catch (RuntimeException exception) {
            LOGGER.warn(
                    "Falha ao resolver contexto Stav.IA sem obraId.",
                    exception
            );
            return StaviaContextResolution.notFound();
        }
    }

    private StaviaQueryResult unresolvedContextResult(
            StaviaContextResolution resolution
    ) {
        String answerText;
        List<String> warnings;

        if (resolution.ambiguous()) {
            answerText = "Encontrei mais de uma obra/RDO possível. Qual destes contextos você quer consultar?\n\n"
                    + resolution.ambiguousOptions()
                            .stream()
                            .map(option -> "- " + option)
                            .collect(java.util.stream.Collectors.joining("\n"));
            warnings = List.of("Contexto operacional ambíguo.");
        } else {
            answerText = "Não encontrei contexto suficiente para identificar a obra ou o RDO. Informe o número do RDO, cidade, contrato/CW ou nome da obra.";
            warnings = List.of("A consulta não informou contexto operacional suficiente.");
        }

        return new StaviaQueryResult(
                new StaviaAnswer(
                        answerText,
                        StaviaConfidence.INDETERMINADA,
                        StaviaAnswerType.INFORMACAO_INSUFICIENTE,
                        List.of(),
                        true,
                        warnings
                ),
                StaviaIntent.DESCONHECIDA,
                0.0,
                java.util.Map.of(),
                warnings
        );
    }

    private String textAttribute(
            Map<String, Object> attributes,
            String key
    ) {
        if (attributes == null) {
            return null;
        }

        Object value = attributes.get(key);
        if (value == null || String.valueOf(value).isBlank()) {
            return null;
        }

        return String.valueOf(value).trim();
    }

    private static <T> T require(
            T value,
            String message
    ) {
        if (value == null) {
            throw new IllegalArgumentException(message);
        }

        return value;
    }
}
