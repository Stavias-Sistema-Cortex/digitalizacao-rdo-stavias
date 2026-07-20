package com.projeto.cortex.intelligence.stavia.knowledge.history;

import java.util.List;

public interface OperationalHistoryReader {

    List<OperationalHistoryEvent> findByWorksiteGraph(
            String worksiteId,
            int maximumDepth,
            int maximumEvents
    );
}
