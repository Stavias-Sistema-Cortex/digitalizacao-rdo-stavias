package com.projeto.cortex.intelligence.stavia.knowledge.equipment;

import java.util.List;

public interface EquipmentReader {

    List<EquipmentRecord> findByWorksiteId(
            String worksiteId
    );
}
