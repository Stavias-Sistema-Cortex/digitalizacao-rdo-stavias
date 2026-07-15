package com.projeto.cortex.intelligence.stavia.knowledge.geospatial;

import java.util.List;

public interface GeospatialKnowledgeReader {
    List<GeospatialKnowledgeRecord> findCurrentByWorksiteId(String worksiteId);
}
