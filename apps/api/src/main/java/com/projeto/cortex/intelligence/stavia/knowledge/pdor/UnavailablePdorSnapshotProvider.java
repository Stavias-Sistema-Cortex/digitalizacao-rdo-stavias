package com.projeto.cortex.intelligence.stavia.knowledge.pdor;

import com.projeto.cortex.intelligence.PdorContextBuilder;

import java.util.Optional;

public final class UnavailablePdorSnapshotProvider
        implements PdorSnapshotProvider {

    @Override
    public Optional<PdorContextBuilder.PdorSourceSnapshot>
    findLatestByWorksiteId(String worksiteId) {
        return Optional.empty();
    }
}
