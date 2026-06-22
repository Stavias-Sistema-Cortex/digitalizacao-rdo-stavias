package com.projeto.cortex.intelligence.stavia.knowledge.ontology;

import java.util.List;

public interface OntologyReader {

    List<OntologyRelation> findByWorksiteGraph(
            String worksiteId,
            int maximumDepth,
            int maximumRelations
    );
}
