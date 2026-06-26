package com.projeto.cortex.intelligence.stavia;

import com.projeto.cortex.intelligence.stavia.generation.DeterministicStaviaResponseGenerator;
import com.projeto.cortex.intelligence.stavia.intent.StaviaIntent;
import com.projeto.cortex.intelligence.stavia.intent.StaviaIntentClassifier;
import com.projeto.cortex.intelligence.stavia.model.StaviaAnswer;
import com.projeto.cortex.intelligence.stavia.model.StaviaContext;
import com.projeto.cortex.intelligence.stavia.model.StaviaEvidence;
import com.projeto.cortex.intelligence.stavia.model.StaviaEvidenceTypes;
import com.projeto.cortex.intelligence.stavia.model.StaviaQuestion;
import com.projeto.cortex.intelligence.stavia.policy.StaviaContradictionPolicy;
import com.projeto.cortex.intelligence.stavia.policy.StaviaEvidenceQualityPolicy;
import com.projeto.cortex.intelligence.stavia.policy.StaviaGroundingValidator;
import com.projeto.cortex.intelligence.stavia.retrieval.StaviaEvidenceSelector;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertTrue;

class StaviaEngineBudgetTest {

    @Test
    void shouldKeepUpToFiftyAllocationEvidences() {
        List<StaviaEvidence> evidences = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            evidences.add(new StaviaEvidence(
                    StaviaEvidenceTypes.ALOCACAO_COLABORADOR,
                    "ALOCACAO_COLABORADOR:aloc-" + i,
                    "Abner esteve na obra CW1 em 0" + (i % 9 + 1) + "/06/2026 por 8 hora(s).",
                    Instant.now(),
                    true,
                    Map.of("colaboradorNome", "Abner", "data", "2026-06-0" + (i % 9 + 1))));
        }

        StaviaEngine engine = new StaviaEngine(
                new StaviaIntentClassifier(),
                new StaviaEvidenceSelector(),
                new StaviaGroundingValidator(),
                new StaviaEvidenceQualityPolicy(),
                new StaviaContradictionPolicy(),
                new DeterministicStaviaResponseGenerator());

        StaviaAnswer answer = engine.answer(
                new StaviaQuestion("Onde o Abner trabalhou?", "u1", "obra-1"),
                new StaviaContext(Set.of(StaviaEngine.REQUIRED_PERMISSION), evidences),
                StaviaIntent.CONSULTAR_ALOCACAO_COLABORADOR);

        // Com truncamento antigo (5), só 5 alocações chegavam ao gerador, que exibe 10
        // e anuncia "omitidas". Com orçamento de 50, as 30 cabem (10 exibidas + 20 omitidas
        // no texto do gerador determinístico) — o ponto do teste é que o engine NÃO corta em 5.
        assertTrue(answer.sources().size() >= 30,
                "engine deveria reter >=30 evidências de alocação, reteve " + answer.sources().size());
    }
}
