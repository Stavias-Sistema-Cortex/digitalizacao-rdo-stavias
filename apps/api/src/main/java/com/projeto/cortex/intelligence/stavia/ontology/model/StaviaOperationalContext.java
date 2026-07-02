package com.projeto.cortex.intelligence.stavia.ontology.model;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record StaviaOperationalContext(
        String obraId,
        StaviaOperationalIntent intent,
        String originalQuery,
        String period,
        List<OntologyEntity> entities,
        List<OntologyRelation> relations,
        List<OntologyEvent> events,
        List<OperationalState> states,
        List<OperationalEvidence> evidences,
        List<OperationalDecision> decisions,
        List<DelayAnalysis> delays
) {

    public StaviaOperationalContext {
        entities = entities == null ? List.of() : List.copyOf(entities);
        relations = relations == null ? List.of() : List.copyOf(relations);
        events = events == null ? List.of() : List.copyOf(events);
        states = states == null ? List.of() : List.copyOf(states);
        evidences = evidences == null ? List.of() : List.copyOf(evidences);
        decisions = decisions == null ? List.of() : List.copyOf(decisions);
        delays = delays == null ? List.of() : List.copyOf(delays);
    }

    public List<String> entityIds() {
        return entities.stream().map(OntologyEntity::id).distinct().toList();
    }

    public List<String> relationIds() {
        return relations.stream().map(OntologyRelation::id).distinct().toList();
    }

    public List<String> eventIds() {
        return events.stream().map(OntologyEvent::id).distinct().toList();
    }

    public Map<String, Object> traceMap() {
        Map<String, Object> trace = new LinkedHashMap<>();
        trace.put("obraId", obraId);
        trace.put("intent", intent == null ? null : intent.name());
        trace.put("period", period);
        trace.put("entities", entityIds());
        trace.put("relations", relationIds());
        trace.put("events", eventIds());
        trace.put("evidences", evidences.stream().map(OperationalEvidence::id).distinct().toList());
        trace.put("states", states.stream().map(OperationalState::id).distinct().toList());
        trace.put("decisions", decisions.stream().map(OperationalDecision::id).distinct().toList());
        return trace;
    }
}
