package com.projeto.cortex.intelligence.stavia.knowledge.ontology;

import java.util.List;

public interface OntologyReader {

    List<OntologyRelation> findByWorksiteGraph(
            String worksiteId,
            int maximumDepth,
            int maximumRelations
    );

    default List<OntologyObject> findObjectsByWorksiteGraph(
            String worksiteId,
            int maximumDepth,
            int maximumObjects
    ) {
        return List.of();
    }

    default List<OntologyAttribute> findAttributesByWorksiteGraph(
            String worksiteId,
            int maximumDepth,
            int maximumAttributes
    ) {
        return List.of();
    }
}
