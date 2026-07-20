package com.projeto.cortex.intelligence.stavia.knowledge.worksite;

import java.util.Optional;

public interface WorksiteReader {

    Optional<WorksiteSnapshot> findById(
            String worksiteId
    );
}
