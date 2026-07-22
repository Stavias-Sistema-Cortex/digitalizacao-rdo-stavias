package com.projeto.cortex.intelligence.stavia;

import com.projeto.cortex.intelligence.stavia.context.StaviaContextBuilder;
import com.projeto.cortex.intelligence.stavia.generation.DeterministicStaviaResponseGenerator;
import com.projeto.cortex.intelligence.stavia.interpret.DeterministicQuestionInterpreter;
import com.projeto.cortex.intelligence.stavia.interpret.Origin;
import com.projeto.cortex.intelligence.stavia.interpret.StaviaInterpretation;
import com.projeto.cortex.intelligence.stavia.interpret.StaviaInterpretationCoordinator;
import com.projeto.cortex.intelligence.stavia.interpret.StaviaQuestionInterpreter;
import com.projeto.cortex.intelligence.stavia.intent.StaviaClassification;
import com.projeto.cortex.intelligence.stavia.intent.StaviaIntent;
import com.projeto.cortex.intelligence.stavia.intent.StaviaIntentClassifier;
import com.projeto.cortex.intelligence.stavia.knowledge.StaviaKnowledgeOrchestrator;
import com.projeto.cortex.intelligence.stavia.knowledge.StaviaKnowledgeRequest;
import com.projeto.cortex.intelligence.stavia.knowledge.StaviaKnowledgeSource;
import com.projeto.cortex.intelligence.stavia.model.StaviaEvidence;
import com.projeto.cortex.intelligence.stavia.model.StaviaEvidenceTypes;
import com.projeto.cortex.intelligence.stavia.model.StaviaQuestion;
import com.projeto.cortex.intelligence.stavia.planning.ResolvedEntity;
import com.projeto.cortex.intelligence.stavia.planning.QueryDomain;
import com.projeto.cortex.intelligence.stavia.planning.QueryOperation;
import com.projeto.cortex.intelligence.stavia.planning.StaviaQueryPlan;
import com.projeto.cortex.intelligence.stavia.planning.TemporalFilter;
import com.projeto.cortex.intelligence.stavia.policy.StaviaContradictionPolicy;
import com.projeto.cortex.intelligence.stavia.policy.StaviaEvidenceQualityPolicy;
import com.projeto.cortex.intelligence.stavia.policy.StaviaGroundingValidator;
import com.projeto.cortex.intelligence.stavia.retrieval.StaviaEvidenceSelector;
import com.projeto.cortex.intelligence.stavia.access.StaviaAccessPolicy;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class StaviaParaphrasePipelineTest {

    private StaviaInterpretation roleInterpretation(String obraId) {
        StaviaQueryPlan plan = new StaviaQueryPlan(
                QueryDomain.EQUIPE, QueryOperation.READ_ATTRIBUTE,
                List.of(ResolvedEntity.worksiteById(obraId), ResolvedEntity.roleByLabel("apontador")),
                TemporalFilter.none(), List.of(), List.of(), List.of(), List.of(),
                false, false, false);
        return new StaviaInterpretation(
                new StaviaClassification(StaviaIntent.CONSULTAR_EQUIPE, 0.95), plan, Origin.LLM);
    }

    @Test
    void twoParaphrasesYieldSameIntentAndAnswer() {
        StaviaQuestionInterpreter llm = q -> Optional.of(roleInterpretation(q.obraId()));
        StaviaInterpretationCoordinator coordinator = new StaviaInterpretationCoordinator(
                new DeterministicQuestionInterpreter(
                        new StaviaIntentClassifier(),
                        new com.projeto.cortex.intelligence.stavia.planning.StaviaQueryPlanner(
                                new com.projeto.cortex.intelligence.stavia.semantic.StaviaSemanticCatalog())),
                llm, "llm", 0.45);

        StaviaKnowledgeSource team = new StaviaKnowledgeSource() {
            public String sourceName() { return "equipe-rdos"; }
            public String sourceVersion() { return "T-1"; }
            public boolean supports(StaviaKnowledgeRequest r) {
                return r.intent() == StaviaIntent.CONSULTAR_EQUIPE;
            }
            public List<StaviaEvidence> retrieve(StaviaKnowledgeRequest r) {
                return List.of(new StaviaEvidence(
                        StaviaEvidenceTypes.EQUIPE, "EQUIPE:1",
                        "Apontador: Maria Souza no RDO-10.", Instant.now(), true,
                        Map.of("cargo", "Apontador", "colaboradorNome", "Maria Souza")));
            }
        };

        StaviaEngine engine = new StaviaEngine(
                new StaviaIntentClassifier(), new StaviaEvidenceSelector(),
                new StaviaGroundingValidator(), new StaviaEvidenceQualityPolicy(),
                new StaviaContradictionPolicy(), new DeterministicStaviaResponseGenerator());

        StaviaAccessPolicy policy = new StaviaAccessPolicy() {
            public Set<String> permissionsFor(String userId) {
                return Set.of(StaviaEngine.REQUIRED_PERMISSION);
            }
            public boolean canAccessWorksite(String userId, String worksiteId) { return true; }
        };

        StaviaQueryService service = new StaviaQueryService(
                new StaviaIntentClassifier(),
                new StaviaKnowledgeOrchestrator(List.of(team)),
                new StaviaContextBuilder(), engine, policy, coordinator);

        StaviaQueryResult a = service.query(
                new StaviaQuestion("Tem apontador?", "u1", "obra-1"));
        StaviaQueryResult b = service.query(
                new StaviaQuestion("Quem é o apontador dessa obra?", "u1", "obra-1"));

        assertEquals(StaviaIntent.CONSULTAR_EQUIPE, a.intent());
        assertEquals(a.intent(), b.intent());
        assertFalse(a.answer().insufficientData());
        assertFalse(b.answer().insufficientData());
    }
}
