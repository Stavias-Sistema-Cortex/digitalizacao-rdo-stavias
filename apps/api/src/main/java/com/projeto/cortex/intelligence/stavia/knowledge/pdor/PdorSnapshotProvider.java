package com.projeto.cortex.intelligence.stavia.knowledge.pdor;

import com.projeto.cortex.intelligence.PdorContextBuilder;

import java.util.Optional;

public interface PdorSnapshotProvider {

    Optional<PdorContextBuilder.PdorSourceSnapshot>
    findLatestByWorksiteId(String worksiteId);
}
