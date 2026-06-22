package com.projeto.cortex.intelligence.stavia.knowledge.pdoc;

import com.projeto.cortex.intelligence.PdocContextBuilder;

import java.util.Optional;

public interface PdocSnapshotProvider {

    Optional<PdocContextBuilder.PdocSourceSnapshot>
    findLatestByWorksiteId(String worksiteId);
}
