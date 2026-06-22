package com.projeto.cortex.intelligence.stavia;

import org.springframework.beans.factory.annotation.Autowired;
import com.projeto.cortex.intelligence.stavia.generation.StaviaGeneratedResponse;
import com.projeto.cortex.intelligence.stavia.generation.StaviaGenerationResult;
import com.projeto.cortex.intelligence.stavia.generation.StaviaResponseGenerationExecutor;
import com.projeto.cortex.intelligence.stavia.generation.StaviaResponseGenerator;
import com.projeto.cortex.intelligence.stavia.intent.StaviaIntent;
import com.projeto.cortex.intelligence.stavia.intent.StaviaIntentClassifier;
import com.projeto.cortex.intelligence.stavia.model.StaviaAnswer;
import com.projeto.cortex.intelligence.stavia.model.StaviaAnswerType;
import com.projeto.cortex.intelligence.stavia.model.StaviaConfidence;
import com.projeto.cortex.intelligence.stavia.model.StaviaContext;
import com.projeto.cortex.intelligence.stavia.model.StaviaEvidence;
import com.projeto.cortex.intelligence.stavia.model.StaviaExecutionMetadata;
import com.projeto.cortex.intelligence.stavia.model.StaviaQuestion;
import com.projeto.cortex.intelligence.stavia.policy.StaviaContradictionAssessment;
import com.projeto.cortex.intelligence.stavia.policy.StaviaContradictionPolicy;
import com.projeto.cortex.intelligence.stavia.policy.StaviaEvidenceQuality;
import com.projeto.cortex.intelligence.stavia.policy.StaviaEvidenceQualityPolicy;
import com.projeto.cortex.intelligence.stavia.policy.StaviaGroundingValidation;
import com.projeto.cortex.intelligence.stavia.policy.StaviaGroundingValidator;
import com.projeto.cortex.intelligence.stavia.policy.StaviaQualityAssessment;
import com.projeto.cortex.intelligence.stavia.retrieval.StaviaEvidenceSelector;
import org.springframework.stereotype.Service;

import java.time.Clock;

import java.util.ArrayList;
import java.util.List;

@Service
public class StaviaEngine {

    public static final String REQUIRED_PERMISSION =
            "STAVIA_CONSULTAR";

    private final StaviaIntentClassifier intentClassifier;
    private final StaviaEvidenceSelector evidenceSelector;
    private final StaviaGroundingValidator groundingValidator;
    private final StaviaEvidenceQualityPolicy qualityPolicy;
    private final StaviaContradictionPolicy contradictionPolicy;
    private final StaviaResponseGenerator responseGenerator;
    private final StaviaResponseGenerationExecutor generationExecutor;
    private final Clock clock;

    @Autowired
    public StaviaEngine(
            StaviaIntentClassifier intentClassifier,
            StaviaEvidenceSelector evidenceSelector,
            StaviaGroundingValidator groundingValidator,
            StaviaEvidenceQualityPolicy qualityPolicy,
            StaviaContradictionPolicy contradictionPolicy,
            StaviaResponseGenerator responseGenerator
    ) {
        this(
                intentClassifier,
                evidenceSelector,
                groundingValidator,
                qualityPolicy,
                contradictionPolicy,
                responseGenerator,
                new StaviaResponseGenerationExecutor(),
                Clock.systemUTC()
        );
    }

    public StaviaEngine(
            StaviaIntentClassifier intentClassifier,
            StaviaEvidenceSelector evidenceSelector,
            StaviaGroundingValidator groundingValidator,
            StaviaEvidenceQualityPolicy qualityPolicy,
            StaviaContradictionPolicy contradictionPolicy,
            StaviaResponseGenerator responseGenerator,
            StaviaResponseGenerationExecutor generationExecutor,
            Clock clock
    ) {
        if (clock == null) {
            throw new IllegalArgumentException(
                    "O relógio da Stav.IA deve ser informado."
            );
        }

        this.intentClassifier = intentClassifier;
        this.evidenceSelector = evidenceSelector;
        this.groundingValidator = groundingValidator;
        if (responseGenerator == null) {
            throw new IllegalArgumentException(
                    "O gerador de respostas da Stav.IA deve ser informado."
            );
        }

        if (generationExecutor == null) {
            throw new IllegalArgumentException(
                    "O executor de geração da Stav.IA deve ser informado."
            );
        }

        this.qualityPolicy = qualityPolicy;
        this.contradictionPolicy = contradictionPolicy;
        this.responseGenerator = responseGenerator;
        this.generationExecutor = generationExecutor;
        this.clock = clock;
    }

    public StaviaAnswer answer(
            StaviaQuestion question,
            StaviaContext context
    ) {
        if (question == null) {
            return insufficientAnswer(
                    "A pergunta da Stav.IA não foi informada.",
                    List.of(
                            "A consulta não pôde ser processada."
                    )
            );
        }

        return answer(
                question,
                context,
                intentClassifier.classify(question.text())
        );
    }

    public StaviaAnswer answer(
            StaviaQuestion question,
            StaviaContext context,
            StaviaIntent intent
    ) {
        if (question == null) {
            return insufficientAnswer(
                    "A pergunta da Stav.IA não foi informada.",
                    List.of(
                            "A consulta não pôde ser processada."
                    )
            );
        }

        if (context == null) {
            return insufficientAnswer(
                    "O contexto da Stav.IA não foi informado.",
                    List.of(
                            "A consulta não possui contexto autorizado."
                    )
            );
        }

        if (!context.hasPermission(REQUIRED_PERMISSION)) {
            return insufficientAnswer(
                    "Você não possui permissão para consultar a Stav.IA.",
                    List.of(
                            "A consulta foi bloqueada pela política de acesso."
                    )
            );
        }

        if (intent == null) {
            intent = intentClassifier.classify(
                    question.text()
            );
        }

        List<StaviaEvidence> selectedEvidence =
                evidenceSelector.select(
                        intent,
                        context
                );

        if (selectedEvidence.isEmpty()) {
            return insufficientAnswer(
                    "Não há dados suficientes no Córtex para responder a essa pergunta.",
                    List.of(
                            "Nenhuma evidência compatível foi encontrada."
                    )
            );
        }

        List<StaviaEvidence> limitedSources =
                selectedEvidence
                        .stream()
                        .limit(5)
                        .toList();

        StaviaContradictionAssessment contradictionAssessment =
                contradictionPolicy.assess(
                        limitedSources
                );

        if (contradictionAssessment.critical()) {
            return insufficientAnswer(
                    "A Stav.IA encontrou informações conflitantes e não pode fornecer uma conclusão segura.",
                    contradictionAssessment.warnings()
            );
        }

        StaviaQualityAssessment qualityAssessment =
                qualityPolicy.assess(
                        limitedSources
                );

        StaviaGenerationResult generationResult =
                generationExecutor.execute(
                        responseGenerator,
                        question,
                        intent,
                        limitedSources
                );

        if (!generationResult.successful()) {
            return insufficientAnswer(
                    "A Stav.IA não conseguiu gerar uma resposta neste momento.",
                    List.of(
                            "A geração da resposta falhou de forma segura.",
                            "Código interno: "
                                    + generationResult.internalErrorCode()
                    )
            );
        }

        StaviaGeneratedResponse generatedResponse =
                generationResult.response();

        List<StaviaEvidence> citedSources =
                resolveGeneratedSources(
                        generatedResponse,
                        limitedSources
                );

        if (
                generatedResponse.answerType()
                        != StaviaAnswerType.INFORMACAO_INSUFICIENTE
                && citedSources.isEmpty()
        ) {
            return insufficientAnswer(
                    "A Stav.IA não conseguiu produzir uma resposta rastreável.",
                    List.of(
                            "O gerador não informou fontes válidas para a resposta."
                    )
            );
        }

        StaviaAnswer draftAnswer =
                new StaviaAnswer(
                        generatedResponse.text(),
                        mapConfidence(
                                qualityAssessment.quality()
                        ),
                        generatedResponse.answerType(),
                        citedSources,
                        false,
                        buildWarnings(
                                limitedSources,
                                qualityAssessment,
                                contradictionAssessment
                        ),
                        executionMetadata()
                );

        StaviaGroundingValidation validation =
                groundingValidator.validate(
                        draftAnswer,
                        context
                );

        if (!validation.valid()) {
            return insufficientAnswer(
                    "A Stav.IA não conseguiu produzir uma resposta segura com os dados disponíveis.",
                    validation.violations()
            );
        }

        return draftAnswer;
    }

    private StaviaExecutionMetadata executionMetadata() {
        return StaviaExecutionMetadata.current(
                clock.instant()
        );
    }

    private StaviaAnswer insufficientAnswer(
            String answer,
            List<String> warnings
    ) {
        return new StaviaAnswer(
                answer,
                StaviaConfidence.INDETERMINADA,
                StaviaAnswerType.INFORMACAO_INSUFICIENTE,
                List.of(),
                true,
                warnings,
                executionMetadata()
        );
    }

    private List<StaviaEvidence> resolveGeneratedSources(
            StaviaGeneratedResponse generatedResponse,
            List<StaviaEvidence> availableEvidence
    ) {
        if (
                generatedResponse.sourceKeys() == null
                || generatedResponse.sourceKeys().isEmpty()
        ) {
            return List.of();
        }

        List<String> availableKeys = availableEvidence
                .stream()
                .map(this::evidenceKey)
                .toList();

        boolean containsUnknownSource =
                generatedResponse.sourceKeys()
                        .stream()
                        .anyMatch(key ->
                                !availableKeys.contains(key)
                        );

        if (containsUnknownSource) {
            return List.of();
        }

        return availableEvidence
                .stream()
                .filter(evidence ->
                        generatedResponse.sourceKeys()
                                .contains(
                                        evidenceKey(evidence)
                                )
                )
                .toList();
    }

    private String evidenceKey(
            StaviaEvidence evidence
    ) {
        return evidence.type()
                + ":"
                + evidence.id();
    }

    private StaviaConfidence mapConfidence(
            StaviaEvidenceQuality quality
    ) {
        return switch (quality) {
            case ALTA ->
                    StaviaConfidence.ALTA;

            case MEDIA ->
                    StaviaConfidence.MEDIA;

            case BAIXA ->
                    StaviaConfidence.BAIXA;

            case INDETERMINADA ->
                    StaviaConfidence.INDETERMINADA;
        };
    }

    private List<String> buildWarnings(
            List<StaviaEvidence> evidence,
            StaviaQualityAssessment qualityAssessment,
            StaviaContradictionAssessment contradictionAssessment
    ) {
        List<String> warnings = new ArrayList<>(
                qualityAssessment.warnings()
        );

        warnings.addAll(
                contradictionAssessment.warnings()
        );

        boolean hasUnvalidatedEvidence = evidence
                .stream()
                .anyMatch(
                        item -> !item.validated()
                );

        if (
                hasUnvalidatedEvidence
                && warnings.stream().noneMatch(
                        message ->
                                message.contains(
                                        "não foi validada"
                                )
                                || message.contains(
                                        "não foram validadas"
                                )
                )
        ) {
            warnings.add(
                    "Parte das informações utilizadas ainda não foi validada."
            );
        }

        return List.copyOf(warnings);
    }
}
