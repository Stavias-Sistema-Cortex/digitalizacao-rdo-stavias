package com.projeto.cortex.intelligence.stavia.knowledge.pdoc;

import com.projeto.cortex.intelligence.PdocContextBuilder;

import java.util.Optional;

public final class UnavailablePdocSnapshotProvider
        implements PdocSnapshotProvider {

    @Override
    public Optional<PdocContextBuilder.PdocSourceSnapshot>
    findLatestByWorksiteId(String worksiteId) {
        return Optional.empty();
    }
}
