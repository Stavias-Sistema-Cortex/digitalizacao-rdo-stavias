package com.projeto.cortex.intelligence.stavia.generation;

import com.projeto.cortex.intelligence.stavia.prompt.StaviaPrompt;

public interface StaviaModelClient {

    StaviaModelResponse generate(
            StaviaPrompt prompt
    );
}
