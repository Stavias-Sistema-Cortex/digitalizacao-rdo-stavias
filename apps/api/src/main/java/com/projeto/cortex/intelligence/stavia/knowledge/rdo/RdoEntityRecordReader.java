package com.projeto.cortex.intelligence.stavia.knowledge.rdo;

import com.projeto.cortex.intelligence.stavia.planning.AggregationSpec;
import com.projeto.cortex.intelligence.stavia.semantic.rdo.RdoOntologyAttribute;
import com.projeto.cortex.intelligence.stavia.semantic.rdo.RdoOntologyEntity;

import java.util.List;
import java.util.Map;

public interface RdoEntityRecordReader {

    List<Map<String, Object>> findRecords(
            RdoOntologyEntity entity,
            RdoRecordQuery query
    );

    List<Map<String, Object>> aggregate(
            RdoOntologyEntity entity,
            RdoOntologyAttribute attribute,
            AggregationSpec spec,
            RdoRecordQuery query
    );
}
