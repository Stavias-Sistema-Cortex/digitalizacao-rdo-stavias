package com.projeto.cortex.intelligence.stavia.ontology.service;

import com.projeto.cortex.intelligence.stavia.ontology.model.StaviaOperationalIntent;
import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Component;

@Component
public class StaviaOperationalIntentClassifier {

    private static final List<IntentRule> RULES = List.of(
            rule(StaviaOperationalIntent.GENERATE_WEEKLY_REPROGRAMMING,
                    "reprogramacao", "reprogramar", "programacao semanal", "recuperar atraso", "plano da semana", "proxima semana"),
            rule(StaviaOperationalIntent.EXPLAIN_CRITICAL_PATH,
                    "caminho critico", "critico", "marco comprometido", "segura o caminho"),
            rule(StaviaOperationalIntent.EXPLAIN_DELAY,
                    "por que", "porque", "causa", "causa provavel", "explicar atraso", "motivo", "quando comecou", "atraso da"),
            rule(StaviaOperationalIntent.LIST_DELAYED_ACTIVITIES,
                    "atividades atrasadas", "atrasadas", "atrasado", "em atraso", "delay", "late"),
            rule(StaviaOperationalIntent.LIST_DEPENDENCIES,
                    "depende", "dependem", "dependencia", "bloqueia", "bloqueadas", "restricoes impedem", "seguram"),
            rule(StaviaOperationalIntent.COMPARE_PLANNED_VS_ACTUAL,
                    "planejado versus realizado", "planejado vs realizado", "previsto vs realizado", "comparar planejado", "executado"),
            rule(StaviaOperationalIntent.TRACE_ENTITY_HISTORY,
                    "historico", "quando", "desde quando", "o que mudou", "mudou desde", "rdos mencionaram", "ja teve retrabalho"),
            rule(StaviaOperationalIntent.LIST_OPERATIONAL_RISKS,
                    "risco", "riscos", "em risco", "restricao", "restricoes"),
            rule(StaviaOperationalIntent.SUMMARIZE_WEEK,
                    "resumo da semana", "resuma a semana", "semana", "ultimos 7 dias"),
            rule(StaviaOperationalIntent.SEARCH_ENTITY,
                    "procure", "buscar", "encontre", "qual id", "ids relacionados", "mostre os ids"),
            rule(StaviaOperationalIntent.EXPLAIN_ENTITY,
                    "explique", "o que e", "detalhe", "entidade")
    );

    public StaviaOperationalIntent classify(String query) {
        String normalized = normalize(query);
        IntentRule winner = null;
        int bestScore = 0;

        for (IntentRule rule : RULES) {
            int score = rule.score(normalized);
            if (score > bestScore) {
                bestScore = score;
                winner = rule;
            }
        }

        if (winner == null) {
            return StaviaOperationalIntent.GENERAL_SYSTEM_QUERY;
        }

        return winner.intent();
    }

    private static IntentRule rule(
            StaviaOperationalIntent intent,
            String... signals
    ) {
        return new IntentRule(intent, List.of(signals));
    }

    public static String normalize(String value) {
        if (value == null) {
            return "";
        }
        return Normalizer.normalize(value, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", " ")
                .trim();
    }

    private record IntentRule(
            StaviaOperationalIntent intent,
            List<String> signals
    ) {

        int score(String normalized) {
            int matched = 0;
            for (String signal : signals) {
                String normalizedSignal = normalize(signal);
                if (normalized.contains(normalizedSignal)) {
                    matched++;
                }
            }
            return matched;
        }
    }
}
