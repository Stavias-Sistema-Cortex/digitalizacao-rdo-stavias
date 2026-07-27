package com.projeto.cortex.intelligence.stavia.knowledge.occurrence;

import java.util.List;

public interface OccurrenceReader {

    List<OccurrenceRecord> findByWorksiteId(
            String worksiteId
    );
}
