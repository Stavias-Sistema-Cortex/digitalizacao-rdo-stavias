package com.projeto.cortex.intelligence.stavia.knowledge.rdo;

import java.util.List;

public interface RdoKnowledgeReader {

    List<RdoKnowledgeRecord> findByWorksiteId(String worksiteId);
}
