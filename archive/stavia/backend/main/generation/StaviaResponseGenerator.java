package com.projeto.cortex.intelligence.stavia.generation;

import com.projeto.cortex.intelligence.stavia.intent.StaviaIntent;
import com.projeto.cortex.intelligence.stavia.model.StaviaEvidence;
import com.projeto.cortex.intelligence.stavia.model.StaviaQuestion;

import java.util.List;

public interface StaviaResponseGenerator {

    StaviaGeneratedResponse generate(
            StaviaQuestion question,
            StaviaIntent intent,
            List<StaviaEvidence> evidences
    );
}
