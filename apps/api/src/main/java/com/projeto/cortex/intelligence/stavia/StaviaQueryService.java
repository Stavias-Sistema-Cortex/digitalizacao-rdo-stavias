package com.projeto.cortex.intelligence.stavia;

import com.projeto.cortex.intelligence.stavia.access.StaviaAccessPolicy;
import com.projeto.cortex.intelligence.stavia.context.StaviaContextBuilder;
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
import com.projeto.cortex.intelligence.stavia.model.StaviaContext;
import com.projeto.cortex.intelligence.stavia.model.StaviaEvidence;
import com.projeto.cortex.intelligence.stavia.model.StaviaQuestion;
import com.projeto.cortex.intelligence.stavia.planning.StaviaQueryPlan;
import com.projeto.cortex.intelligence.stavia.planning.StaviaQueryPlanner;
import com.projeto.cortex.intelligence.stavia.semantic.StaviaSemanticCatalog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;

@Service
public class StaviaQueryService {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(StaviaQueryService.class);

    private final StaviaIntentClassifier intentClassifier;
    private final StaviaKnowledgeOrchestrator knowledgeOrchestrator;
    private final StaviaContextBuilder contextBuilder;
    private final StaviaEngine engine;
    private final StaviaAccessPolicy accessPolicy;
    private final StaviaQueryPlanner queryPlanner;
    private final StaviaInterpretationCoordinator coordinator;

    @Autowired
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

        if (
                question.obraId() == null
                || question.obraId().isBlank()
        ) {
            StaviaAnswer answer =
                    engine.answer(
                            question,
                            new StaviaContext(
                                    normalizedPermissions,
                                    List.of()
                            )
                    );

            return new StaviaQueryResult(
                    answer,
                    StaviaIntent.DESCONHECIDA,
                    0.0,
                    java.util.Map.of(),
                    List.of(
                            "A consulta não informou uma obra."
                    )
            );
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
                    List.of(
                            "O usuário não tem acesso a esta obra."
                    )
            );
        }

        StaviaInterpretation interpretation = coordinator.interpret(question);
        StaviaQueryPlan plan = interpretation.plan();
        StaviaIntent intent = interpretation.intent();

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
                        question,
                        intent,
                        question.obraId(),
                        normalizedPermissions,
                        plan
                );

        StaviaKnowledgeBundle knowledgeBundle =
                knowledgeOrchestrator.retrieve(
                        knowledgeRequest
                );

        StaviaRawContext rawContext =
                new StaviaRawContext(
                        question.userId(),
                        question.obraId(),
                        normalizedPermissions,
                        knowledgeBundle.evidences()
                                .stream()
                                .map(evidence ->
                                        toRawEvidence(
                                                question.obraId(),
                                                evidence
                                        )
                                )
                                .toList()
                );

        StaviaContext context =
                contextBuilder.build(rawContext);

        StaviaAnswer answer =
                engine.answer(
                        question,
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

    private StaviaRawContext.RawEvidence toRawEvidence(
            String worksiteId,
            StaviaEvidence evidence
    ) {
        return new StaviaRawContext.RawEvidence(
                evidence.type(),
                evidence.id(),
                worksiteId,
                StaviaEngine.REQUIRED_PERMISSION,
                evidence.summary(),
                evidence.updatedAt(),
                evidence.validated(),
                evidence.attributes()
        );
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
