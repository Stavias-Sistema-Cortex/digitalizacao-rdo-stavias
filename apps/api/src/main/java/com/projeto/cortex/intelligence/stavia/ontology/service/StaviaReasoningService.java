package com.projeto.cortex.intelligence.stavia.ontology.service;

import com.projeto.cortex.intelligence.stavia.ontology.api.StaviaOperationalQueryResponse;
import com.projeto.cortex.intelligence.stavia.ontology.api.StaviaOperationalScope;
import com.projeto.cortex.intelligence.stavia.ontology.model.OntologyEntity;
import com.projeto.cortex.intelligence.stavia.ontology.model.OntologyEvent;
import com.projeto.cortex.intelligence.stavia.ontology.model.OntologyRelation;
import com.projeto.cortex.intelligence.stavia.ontology.model.StaviaOperationalContext;
import com.projeto.cortex.intelligence.stavia.ontology.model.StaviaOperationalIntent;
import com.projeto.cortex.intelligence.stavia.ontology.model.WeeklyReprogramming;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class StaviaReasoningService {

    private final StaviaOperationalIntentClassifier intentClassifier;
    private final StaviaOntologyService ontologyService;
    private final StaviaRetrievalService retrievalService;
    private final StaviaResponseFormatter formatter;

    public StaviaReasoningService(
            StaviaOperationalIntentClassifier intentClassifier,
            StaviaOntologyService ontologyService,
            StaviaRetrievalService retrievalService,
            StaviaResponseFormatter formatter
    ) {
        this.intentClassifier = intentClassifier;
        this.ontologyService = ontologyService;
        this.retrievalService = retrievalService;
        this.formatter = formatter;
    }

    public StaviaOperationalQueryResponse answer(
            String userId,
            String query,
            StaviaOperationalScope scope
    ) {
        if (query == null || query.isBlank()) {
            throw new IllegalArgumentException("A pergunta da StavIA deve ser informada.");
        }

        String obraId = scope == null ? null : scope.obraId();
        String period = scope == null ? null : scope.period();
        StaviaOperationalIntent intent = intentClassifier.classify(query);

        ontologyService.synchronizeOperationalData(obraId);
        StaviaOperationalContext context = retrievalService.retrieve(query, obraId, period, intent);
        WeeklyReprogramming plan = intent == StaviaOperationalIntent.GENERATE_WEEKLY_REPROGRAMMING
                ? buildWeeklyReprogramming(context)
                : null;
        String answer = formatter.format(context, plan);
        double confidence = confidence(context);
        String queryId = persistQuery(userId, query, scope, intent, context, answer, confidence);

        return new StaviaOperationalQueryResponse(
                answer,
                intent.name(),
                context.entities().stream().map(this::entityMap).toList(),
                context.events().stream().map(this::eventMap).toList(),
                context.relations().stream().map(this::relationMap).toList(),
                confidence,
                queryId
        );
    }

    public WeeklyReprogramming generateWeeklyReprogramming(
            String userId,
            String obraId,
            String period,
            Integer targetRecoveryDays
    ) {
        ontologyService.synchronizeOperationalData(obraId);
        StaviaOperationalContext context = retrievalService.retrieve(
                "preparar reprogramação semanal",
                obraId,
                period,
                StaviaOperationalIntent.GENERATE_WEEKLY_REPROGRAMMING
        );
        WeeklyReprogramming plan = buildWeeklyReprogramming(context);
        persistQuery(
                userId,
                "preparar reprogramação semanal",
                new StaviaOperationalScope(obraId, period),
                StaviaOperationalIntent.GENERATE_WEEKLY_REPROGRAMMING,
                context,
                formatter.format(context, plan),
                plan.confidence()
        );
        return plan;
    }

    public List<String> suggestions(String obraId) {
        ontologyService.synchronizeOperationalData(obraId);
        if (ontologyService.delayedActivities(obraId, 1).isEmpty()) {
            return List.of(
                    "Consultar resumo operacional da obra",
                    "Verificar programação da semana",
                    "Listar RDOs recentes"
            );
        }
        return List.of(
                "Quais atividades estão atrasadas?",
                "Por que a atividade está atrasada?",
                "Prepare uma reprogramação semanal"
        );
    }

    private WeeklyReprogramming buildWeeklyReprogramming(StaviaOperationalContext context) {
        LocalDate start = periodStart(context.period());
        LocalDate end = start.plusDays(6);
        List<String> priorities = context.delays().stream()
                .limit(5)
                .map(delay -> delay.activity().canonicalName())
                .toList();
        if (priorities.isEmpty()) {
            priorities = List.of("Validar programação", "Atualizar RDOs", "Confirmar restrições pendentes");
        }
        return new WeeklyReprogramming(
                start,
                end,
                "Organizar a semana com base no contexto operacional disponível no Córtex.",
                priorities,
                List.of(
                        "Revisar os registros usados pela StavIA.",
                        "Atualizar evidências operacionais pendentes.",
                        "Validar o plano com a equipe responsável."
                ),
                List.of("Registrar dependências explícitas para aumentar a precisão da StavIA."),
                List.of("A confiança cai quando faltam evidências e eventos recentes."),
                List.of("Atualizar a programação após validação operacional."),
                context.entityIds(),
                confidence(context)
        );
    }

    private String persistQuery(
            String userId,
            String query,
            StaviaOperationalScope scope,
            StaviaOperationalIntent intent,
            StaviaOperationalContext context,
            String answer,
            double confidence
    ) {
        String queryId = ontologyService.saveQuery(
                userId,
                query,
                intent.name(),
                scopeMap(scope),
                context.entityIds(),
                context.eventIds(),
                context.relationIds(),
                answer,
                confidence
        );
        ontologyService.saveContextSnapshot(queryId, context.traceMap());
        return queryId;
    }

    private LocalDate periodStart(String period) {
        LocalDate monday = LocalDate.now().with(DayOfWeek.MONDAY);
        if (period == null || period.isBlank()) {
            return monday;
        }
        String normalized = period.trim().toLowerCase(java.util.Locale.ROOT);
        if (normalized.equals("next_week") || normalized.equals("proxima_semana")) {
            return monday.plusWeeks(1);
        }
        if (normalized.equals("last_week") || normalized.equals("semana_passada")) {
            return monday.minusWeeks(1);
        }
        return monday;
    }

    private double confidence(StaviaOperationalContext context) {
        double confidence = 0.35;
        if (!context.entities().isEmpty()) {
            confidence += 0.15;
        }
        if (!context.events().isEmpty()) {
            confidence += 0.15;
        }
        if (!context.evidences().isEmpty()) {
            confidence += 0.15;
        }
        if (!context.relations().isEmpty()) {
            confidence += 0.10;
        }
        if (!context.delays().isEmpty()) {
            confidence += 0.10;
        }
        return Math.min(confidence, 0.92);
    }

    private Map<String, Object> scopeMap(StaviaOperationalScope scope) {
        if (scope == null) {
            return Map.of();
        }
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("obraId", scope.obraId());
        values.put("period", scope.period());
        return values;
    }

    private Map<String, Object> entityMap(OntologyEntity entity) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("id", entity.id());
        values.put("entityType", entity.entityType());
        values.put("name", entity.canonicalName());
        values.put("status", entity.status());
        return values;
    }

    private Map<String, Object> eventMap(OntologyEvent event) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("id", event.id());
        values.put("eventType", event.eventType());
        values.put("description", event.description());
        values.put("occurredAt", event.occurredAt());
        return values;
    }

    private Map<String, Object> relationMap(OntologyRelation relation) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("id", relation.id());
        values.put("relationType", relation.relationType());
        values.put("sourceEntityId", relation.sourceEntityId());
        values.put("targetEntityId", relation.targetEntityId());
        values.put("confidence", relation.confidence());
        return values;
    }
}
