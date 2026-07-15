package com.projeto.cortex.intelligence.stavia.ontology.service;

import com.projeto.cortex.intelligence.stavia.ontology.model.DelayAnalysis;
import com.projeto.cortex.intelligence.stavia.ontology.model.OntologyEntity;
import com.projeto.cortex.intelligence.stavia.ontology.model.OntologyEvent;
import com.projeto.cortex.intelligence.stavia.ontology.model.OntologyRelation;
import com.projeto.cortex.intelligence.stavia.ontology.model.OperationalDecision;
import com.projeto.cortex.intelligence.stavia.ontology.model.OperationalEvidence;
import com.projeto.cortex.intelligence.stavia.ontology.model.OperationalState;
import com.projeto.cortex.intelligence.stavia.ontology.model.StaviaOperationalContext;
import com.projeto.cortex.intelligence.stavia.ontology.model.StaviaOperationalIntent;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Service;

@Service
public class StaviaRetrievalService {

    private final StaviaOntologyService ontologyService;

    public StaviaRetrievalService(StaviaOntologyService ontologyService) {
        this.ontologyService = ontologyService;
    }

    public StaviaOperationalContext retrieve(
            String query,
            String scopeObraId,
            String period,
            StaviaOperationalIntent intent
    ) {
        String obraId = resolveObraId(query, scopeObraId);
        List<OntologyEntity> entities = new ArrayList<>();

        if (intent == StaviaOperationalIntent.LIST_DELAYED_ACTIVITIES
                || intent == StaviaOperationalIntent.EXPLAIN_DELAY
                || intent == StaviaOperationalIntent.GENERATE_WEEKLY_REPROGRAMMING
                || intent == StaviaOperationalIntent.LIST_OPERATIONAL_RISKS
                || intent == StaviaOperationalIntent.EXPLAIN_CRITICAL_PATH) {
            entities.addAll(ontologyService.delayedActivities(obraId, 25));
        }

        resolveEntityWithinScope(query, obraId).ifPresent(entities::add);

        if (entities.isEmpty()) {
            entities.addAll(listEntitiesWithinScope(obraId, query));
        }

        List<String> entityIds = distinctEntityIds(entities);
        List<OntologyRelation> relations = relationsInsideEntitySet(
                ontologyService.relationsForEntities(entityIds, 120),
                entityIds
        );
        List<OntologyEvent> events = eventsInsideEntitySet(
                ontologyService.eventsForEntities(entityIds, 120),
                entityIds
        );
        List<OperationalState> states = ontologyService.statesForEntities(entityIds, 120);
        List<OperationalEvidence> evidences = ontologyService.evidencesForEntities(entityIds, 120);

        List<OperationalDecision> decisions = new ArrayList<>();
        for (String entityId : entityIds) {
            decisions.addAll(ontologyService.decisionsForEntity(entityId));
        }

        List<DelayAnalysis> delays = buildDelayAnalyses(entities, states, evidences, relations);

        return new StaviaOperationalContext(
                obraId,
                intent,
                query,
                period,
                deduplicateEntities(entities),
                deduplicateRelations(relations),
                deduplicateEvents(events),
                states,
                evidences,
                decisions,
                delays
        );
    }

    private String resolveObraId(String query, String scopeObraId) {
        if (scopeObraId != null && !scopeObraId.isBlank()) {
            return scopeObraId.trim();
        }

        return ontologyService.resolveWorksiteId(query)
                .orElse(null);
    }

    private Optional<OntologyEntity> resolveEntityWithinScope(
            String query,
            String obraId
    ) {
        if (obraId == null || obraId.isBlank()) {
            return ontologyService.resolveEntity(query);
        }
        return ontologyService.resolveEntityInWorksite(query, obraId);
    }

    private List<OntologyEntity> listEntitiesWithinScope(
            String obraId,
            String query
    ) {
        if (obraId == null || obraId.isBlank()) {
            return ontologyService.listEntities(null, query, 10);
        }
        return ontologyService.listEntitiesForWorksite(obraId, null, query, 10);
    }

    private List<OntologyRelation> relationsInsideEntitySet(
            List<OntologyRelation> relations,
            List<String> entityIds
    ) {
        Set<String> permitted = Set.copyOf(entityIds);
        return relations.stream()
                .filter(relation -> permitted.contains(relation.sourceEntityId()))
                .filter(relation -> permitted.contains(relation.targetEntityId()))
                .toList();
    }

    private List<OntologyEvent> eventsInsideEntitySet(
            List<OntologyEvent> events,
            List<String> entityIds
    ) {
        Set<String> permitted = Set.copyOf(entityIds);
        return events.stream()
                .filter(event -> permitted.contains(event.entityId()))
                .filter(event -> event.relatedEntityId() == null
                        || permitted.contains(event.relatedEntityId()))
                .toList();
    }

    private List<DelayAnalysis> buildDelayAnalyses(
            List<OntologyEntity> entities,
            List<OperationalState> states,
            List<OperationalEvidence> evidences,
            List<OntologyRelation> relations
    ) {
        List<DelayAnalysis> analyses = new ArrayList<>();
        Map<String, List<OperationalState>> statesByEntity = groupStates(states);
        Map<String, List<OperationalEvidence>> evidenceByEntity = groupEvidences(evidences);

        for (OntologyEntity entity : entities) {
            if (!"ATIVIDADE".equals(entity.entityType())) {
                continue;
            }

            List<OperationalState> entityStates = statesByEntity.getOrDefault(entity.id(), List.of());
            Optional<OperationalState> delayState = stateOfType(entityStates, "DELAY_DAYS");
            if (delayState.isEmpty()) {
                continue;
            }

            BigDecimal delayDays = delayState.get().numericValue();
            if (delayDays == null || delayDays.compareTo(BigDecimal.ZERO) <= 0) {
                continue;
            }

            Optional<OperationalState> progressState = stateOfType(entityStates, "PROGRESS_PERCENT");
            Optional<OperationalState> statusState = stateOfType(entityStates, "STATUS");

            List<OperationalEvidence> entityEvidences =
                    evidenceByEntity.getOrDefault(entity.id(), List.of());

            analyses.add(new DelayAnalysis(
                    entity,
                    delayDays,
                    progressState.map(OperationalState::numericValue).orElse(null),
                    statusState.map(OperationalState::stateValue).orElse(entity.status()),
                    confirmedCauses(entityEvidences),
                    probableCauses(entityEvidences, delayState.get()),
                    impactMessages(entity, relations),
                    entityEvidences,
                    blockingRelations(entity, relations),
                    confidence(entityEvidences, relations)
            ));
        }

        return analyses;
    }

    private List<String> confirmedCauses(List<OperationalEvidence> evidences) {
        List<String> causes = new ArrayList<>();
        for (OperationalEvidence evidence : evidences) {
            String text = normalize(evidence.description());
            if (text.contains("chuva") || text.contains("pluviometria")) {
                causes.add("chuva ou impacto climático registrado em RDO");
            }
            if (text.contains("retrabalho")) {
                causes.add("retrabalho registrado");
            }
            if (text.contains("material")) {
                causes.add("restrição ou atraso de material registrado");
            }
            if (text.contains("equipamento")) {
                causes.add("restrição de equipamento registrada");
            }
        }
        return causes.stream().distinct().toList();
    }

    private List<String> probableCauses(
            List<OperationalEvidence> evidences,
            OperationalState delayState
    ) {
        List<String> causes = new ArrayList<>();
        if (confirmedCauses(evidences).isEmpty()) {
            causes.add("há atraso identificado, mas a causa ainda não está confirmada na ontologia");
        }
        if (delayState.metadata().containsKey("criterio")) {
            causes.add("data programada vencida sem execução confirmada");
        }
        return causes.stream().distinct().toList();
    }

    private List<String> impactMessages(
            OntologyEntity entity,
            List<OntologyRelation> relations
    ) {
        List<String> impacts = new ArrayList<>();
        long outgoingImpactCount = relations.stream()
                .filter(relation -> entity.id().equals(relation.sourceEntityId()))
                .filter(relation -> relation.relationType().equals("BLOCKS")
                        || relation.relationType().equals("IMPACTS")
                        || relation.relationType().equals("DEPENDS_ON"))
                .count();

        if (outgoingImpactCount > 0) {
            impacts.add("existem relações de dependência ou impacto ligadas a esta atividade");
        }

        if (impacts.isEmpty()) {
            impacts.add("impacto sistêmico ainda não foi explicitamente registrado como relação ontológica");
        }

        return impacts;
    }

    private List<OntologyRelation> blockingRelations(
            OntologyEntity entity,
            List<OntologyRelation> relations
    ) {
        return relations.stream()
                .filter(relation -> entity.id().equals(relation.sourceEntityId())
                        || entity.id().equals(relation.targetEntityId()))
                .filter(relation -> relation.relationType().equals("BLOCKS")
                        || relation.relationType().equals("DEPENDS_ON")
                        || relation.relationType().equals("IMPACTS"))
                .toList();
    }

    private double confidence(
            List<OperationalEvidence> evidences,
            List<OntologyRelation> relations
    ) {
        double confidence = 0.45;
        if (!evidences.isEmpty()) {
            confidence += 0.25;
        }
        if (!relations.isEmpty()) {
            confidence += 0.15;
        }
        return Math.min(confidence, 0.90);
    }

    private Optional<OperationalState> stateOfType(
            List<OperationalState> states,
            String type
    ) {
        return states.stream()
                .filter(state -> type.equals(state.stateType()))
                .findFirst();
    }

    private Map<String, List<OperationalState>> groupStates(List<OperationalState> states) {
        Map<String, List<OperationalState>> grouped = new LinkedHashMap<>();
        for (OperationalState state : states) {
            grouped.computeIfAbsent(state.entityId(), ignored -> new ArrayList<>())
                    .add(state);
        }
        return grouped;
    }

    private Map<String, List<OperationalEvidence>> groupEvidences(List<OperationalEvidence> evidences) {
        Map<String, List<OperationalEvidence>> grouped = new LinkedHashMap<>();
        for (OperationalEvidence evidence : evidences) {
            grouped.computeIfAbsent(evidence.entityId(), ignored -> new ArrayList<>())
                    .add(evidence);
        }
        return grouped;
    }

    private List<String> distinctEntityIds(List<OntologyEntity> entities) {
        return entities.stream()
                .map(OntologyEntity::id)
                .distinct()
                .toList();
    }

    private List<OntologyEntity> deduplicateEntities(List<OntologyEntity> entities) {
        Map<String, OntologyEntity> byId = new LinkedHashMap<>();
        for (OntologyEntity entity : entities) {
            byId.putIfAbsent(entity.id(), entity);
        }
        return List.copyOf(byId.values());
    }

    private List<OntologyRelation> deduplicateRelations(List<OntologyRelation> relations) {
        Map<String, OntologyRelation> byId = new LinkedHashMap<>();
        for (OntologyRelation relation : relations) {
            byId.putIfAbsent(relation.id(), relation);
        }
        return List.copyOf(byId.values());
    }

    private List<OntologyEvent> deduplicateEvents(List<OntologyEvent> events) {
        Map<String, OntologyEvent> byId = new LinkedHashMap<>();
        for (OntologyEvent event : events) {
            byId.putIfAbsent(event.id(), event);
        }
        return List.copyOf(byId.values());
    }

    private String normalize(String value) {
        return value == null
                ? ""
                : value.toLowerCase(java.util.Locale.ROOT);
    }
}
