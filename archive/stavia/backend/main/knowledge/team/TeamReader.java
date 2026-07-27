package com.projeto.cortex.intelligence.stavia.knowledge.team;

import java.util.List;

public interface TeamReader {

    List<TeamRecord> findByWorksiteId(
            String worksiteId
    );
}
