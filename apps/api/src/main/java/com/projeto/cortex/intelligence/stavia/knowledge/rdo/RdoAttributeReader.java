package com.projeto.cortex.intelligence.stavia.knowledge.rdo;

import java.time.LocalDate;
import java.util.List;

public interface RdoAttributeReader {

    List<RdoAttributeRecord> findByWorksiteId(
            String worksiteId,
            LocalDate startDate,
            LocalDate endDate,
            int limit
    );
}
