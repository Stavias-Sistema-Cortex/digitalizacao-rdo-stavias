package com.projeto.cortex.intelligence.stavia.ontology.service;

import com.projeto.cortex.intelligence.stavia.ontology.model.DelayAnalysis;
import com.projeto.cortex.intelligence.stavia.ontology.model.OntologyEntity;
import com.projeto.cortex.intelligence.stavia.ontology.model.OntologyEvent;
import com.projeto.cortex.intelligence.stavia.ontology.model.OntologyRelation;
import com.projeto.cortex.intelligence.stavia.ontology.model.OperationalEvidence;
import com.projeto.cortex.intelligence.stavia.ontology.model.StaviaOperationalContext;
import com.projeto.cortex.intelligence.stavia.ontology.model.StaviaOperationalIntent;
import com.projeto.cortex.intelligence.stavia.ontology.model.WeeklyReprogramming;
import java.math.BigDecimal;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class StaviaResponseFormatter {

    public String format(
            StaviaOperationalContext context,
            WeeklyReprogramming weeklyReprogramming
    ) {
        if (context == null) {
            return "Não encontrei contexto operacional suficiente para responder com segurança.";
        }

        StaviaOperationalIntent intent = context.intent();
        if (intent == null) {
            intent = StaviaOperationalIntent.GENERAL_SYSTEM_QUERY;
        }

        return switch (intent) {
            case LIST_DELAYED_ACTIVITIES -> formatDelayedActivities(context);
            case EXPLAIN_DELAY -> formatDelayExplanation(context);
            case GENERATE_WEEKLY_REPROGRAMMING -> formatWeeklyReprogramming(context, weeklyReprogramming);
            case LIST_DEPENDENCIES -> formatDependencies(context);
            case EXPLAIN_CRITICAL_PATH -> formatCriticalPath(context);
            case TRACE_ENTITY_HISTORY -> formatHistory(context);
            case LIST_OPERATIONAL_RISKS -> formatRisks(context);
            case SEARCH_ENTITY, EXPLAIN_ENTITY -> formatEntityExplanation(context);
            case SUMMARIZE_WEEK,
                    COMPARE_PLANNED_VS_ACTUAL,
                    GENERAL_SYSTEM_QUERY -> formatGeneral(context);
        };
    }

    private String formatDelayedActivities(StaviaOperationalContext context) {
        if (context.delays().isEmpty()) {
            return "Não encontrei atividades atrasadas registradas na ontologia para o contexto informado.";
        }

        StringBuilder answer = new StringBuilder();
        answer.append("Atividades atrasadas identificadas na ontologia do Córtex:\n\n");

        int index = 1;
        for (DelayAnalysis delay : context.delays().stream().limit(10).toList()) {
            answer.append(index++).append(". ").append(delay.activity().canonicalName()).append("\n");
            answer.append("   - ID: ").append(delay.activity().id()).append("\n");
            answer.append("   - Atraso aproximado: ").append(formatNumber(delay.delayDays())).append(" dias.\n");
            answer.append("   - Status: ").append(nonBlank(delay.status(), "não informado")).append(".\n");
            answer.append("   - Dado confirmado: ").append(confirmedText(delay)).append("\n");
            answer.append("   - Inferência provável: ").append(probableText(delay)).append("\n");
            answer.append("   - Impacto: ").append(String.join("; ", delay.impacts())).append(".\n\n");
        }

        answer.append("Recomendação: priorizar as atividades com maior atraso e registrar explicitamente relações de bloqueio, restrição e impacto quando ainda não existirem na ontologia.");
        answer.append(idsUsed(context));
        return answer.toString();
    }

    private String formatDelayExplanation(StaviaOperationalContext context) {
        if (context.delays().isEmpty()) {
            return "Consigo procurar atrasos na ontologia, mas não encontrei atraso suficiente para explicar com segurança no contexto informado.";
        }

        DelayAnalysis delay = context.delays().getFirst();
        StringBuilder answer = new StringBuilder();
        answer.append("Análise do atraso — ").append(delay.activity().canonicalName()).append("\n\n");
        answer.append("Dado confirmado:\n");
        answer.append("- A atividade possui atraso aproximado de ").append(formatNumber(delay.delayDays())).append(" dias.\n");
        answer.append("- Status registrado: ").append(nonBlank(delay.status(), "não informado")).append(".\n\n");

        answer.append("Causas confirmadas:\n");
        if (delay.confirmedCauses().isEmpty()) {
            answer.append("- Não há causa confirmada registrada como evidência explícita.\n");
        } else {
            appendBullets(answer, delay.confirmedCauses());
        }

        answer.append("\nInferências prováveis:\n");
        appendBullets(answer, delay.probableCauses());

        answer.append("\nImpactos conhecidos ou inferidos:\n");
        appendBullets(answer, delay.impacts());

        answer.append("\nObservação: quando a causa não aparece diretamente nos RDOs, estados ou evidências, a StavIA trata a explicação como inferência, não como confirmação.");
        answer.append(idsUsed(context));
        return answer.toString();
    }

    private String formatWeeklyReprogramming(
            StaviaOperationalContext context,
            WeeklyReprogramming weeklyReprogramming
    ) {
        if (weeklyReprogramming == null) {
            return "Não encontrei dados suficientes para gerar uma reprogramação semanal com segurança.";
        }

        StringBuilder answer = new StringBuilder();
        answer.append("Reprogramação semanal — Córtex\n\n");
        answer.append("Período: ").append(weeklyReprogramming.periodStart())
                .append(" a ").append(weeklyReprogramming.periodEnd()).append("\n\n");
        answer.append("Objetivo:\n").append(weeklyReprogramming.objective()).append("\n\n");

        answer.append("Prioridades:\n");
        appendBullets(answer, weeklyReprogramming.priorities());

        answer.append("\nAções recomendadas:\n");
        appendBullets(answer, weeklyReprogramming.recommendedActions());

        answer.append("\nDependências:\n");
        appendBullets(answer, weeklyReprogramming.dependencies());

        answer.append("\nRiscos:\n");
        appendBullets(answer, weeklyReprogramming.risks());

        answer.append("\nMarcos da semana:\n");
        appendBullets(answer, weeklyReprogramming.milestones());

        answer.append("\nObservação: esta reprogramação é uma recomendação operacional gerada a partir da ontologia disponível. Ela deve ser validada pela equipe responsável antes de virar plano oficial.");
        answer.append(idsUsed(context));
        return answer.toString();
    }

    private String formatDependencies(StaviaOperationalContext context) {
        if (context.relations().isEmpty()) {
            return "Não encontrei relações de dependência registradas para a entidade consultada.";
        }

        StringBuilder answer = new StringBuilder("Relações operacionais encontradas:\n\n");
        for (OntologyRelation relation : context.relations().stream().limit(20).toList()) {
            answer.append("- ")
                    .append(relation.relationType())
                    .append(": ")
                    .append(relation.sourceEntityId())
                    .append(" → ")
                    .append(relation.targetEntityId())
                    .append("\n");
        }
        answer.append(idsUsed(context));
        return answer.toString();
    }

    private String formatCriticalPath(StaviaOperationalContext context) {
        if (context.delays().isEmpty() && context.relations().isEmpty()) {
            return "Ainda não há relações e estados suficientes na ontologia para explicar o caminho crítico com segurança.";
        }

        StringBuilder answer = new StringBuilder();
        answer.append("Leitura simplificada de caminho crítico:\n\n");
        if (context.delays().isEmpty()) {
            answer.append("- Não há atrasos atuais suficientes para apontar atividade crítica.\n");
        } else {
            for (DelayAnalysis delay : context.delays().stream().limit(5).toList()) {
                answer.append("- ").append(delay.activity().canonicalName())
                        .append(" tem atraso aproximado de ")
                        .append(formatNumber(delay.delayDays()))
                        .append(" dias.\n");
            }
        }
        answer.append("\nRecomendação: registrar relações BLOCKS, DEPENDS_ON e IMPACTS entre atividades para aumentar a precisão da análise crítica.");
        answer.append(idsUsed(context));
        return answer.toString();
    }

    private String formatHistory(StaviaOperationalContext context) {
        if (context.events().isEmpty()) {
            return "Não encontrei eventos históricos suficientes para a entidade consultada.";
        }

        StringBuilder answer = new StringBuilder("Histórico operacional encontrado:\n\n");
        for (OntologyEvent event : context.events().stream().limit(20).toList()) {
            answer.append("- ")
                    .append(event.occurredAt())
                    .append(" — ")
                    .append(event.description())
                    .append(" [")
                    .append(event.id())
                    .append("]\n");
        }
        answer.append(idsUsed(context));
        return answer.toString();
    }

    private String formatRisks(StaviaOperationalContext context) {
        if (context.delays().isEmpty()) {
            return "Não encontrei riscos operacionais relevantes registrados para o contexto informado.";
        }

        StringBuilder answer = new StringBuilder("Riscos operacionais identificados:\n\n");
        for (DelayAnalysis delay : context.delays().stream().limit(8).toList()) {
            answer.append("- ").append(delay.activity().canonicalName())
                    .append(": atraso de ")
                    .append(formatNumber(delay.delayDays()))
                    .append(" dias; impacto: ")
                    .append(String.join("; ", delay.impacts()))
                    .append(".\n");
        }
        answer.append("\nRecomendação: transformar riscos recorrentes em restrições ou decisões registradas para melhorar rastreabilidade.");
        answer.append(idsUsed(context));
        return answer.toString();
    }

    private String formatEntityExplanation(StaviaOperationalContext context) {
        if (context.entities().isEmpty()) {
            return "Não encontrei uma entidade correspondente na ontologia.";
        }

        OntologyEntity entity = context.entities().getFirst();
        StringBuilder answer = new StringBuilder();
        answer.append("Entidade encontrada:\n\n");
        answer.append("- Nome: ").append(entity.canonicalName()).append("\n");
        answer.append("- ID: ").append(entity.id()).append("\n");
        answer.append("- Tipo: ").append(entity.entityType()).append("\n");
        answer.append("- Status: ").append(nonBlank(entity.status(), "não informado")).append("\n");
        answer.append("- Referência externa: ").append(nonBlank(entity.externalRefType(), "não informada"))
                .append("/")
                .append(nonBlank(entity.externalRefId(), "não informada"))
                .append("\n");

        if (!context.relations().isEmpty()) {
            answer.append("\nRelações relevantes:\n");
            for (OntologyRelation relation : context.relations().stream().limit(10).toList()) {
                answer.append("- ").append(relation.relationType())
                        .append(" ")
                        .append(relation.sourceEntityId())
                        .append(" → ")
                        .append(relation.targetEntityId())
                        .append("\n");
            }
        }
        answer.append(idsUsed(context));
        return answer.toString();
    }

    private String formatGeneral(StaviaOperationalContext context) {
        if (context.entities().isEmpty() && context.events().isEmpty()) {
            return "Não encontrei dados suficientes na ontologia para afirmar isso com segurança.";
        }

        StringBuilder answer = new StringBuilder();
        answer.append("Encontrei contexto operacional no Córtex, mas a pergunta exige uma leitura geral.\n\n");
        answer.append("Dados usados:\n");
        answer.append("- Entidades: ").append(context.entities().size()).append("\n");
        answer.append("- Relações: ").append(context.relations().size()).append("\n");
        answer.append("- Eventos: ").append(context.events().size()).append("\n");
        answer.append("- Evidências: ").append(context.evidences().size()).append("\n\n");
        answer.append("Para uma resposta mais precisa, informe a obra, atividade, RDO ou período desejado.");
        answer.append(idsUsed(context));
        return answer.toString();
    }

    private String confirmedText(DelayAnalysis delay) {
        if (delay.confirmedCauses().isEmpty()) {
            return "atraso registrado por estado operacional, sem causa explícita confirmada.";
        }
        return String.join("; ", delay.confirmedCauses()) + ".";
    }

    private String probableText(DelayAnalysis delay) {
        if (delay.probableCauses().isEmpty()) {
            return "não há inferência suficiente.";
        }
        return String.join("; ", delay.probableCauses()) + ".";
    }

    private String idsUsed(StaviaOperationalContext context) {
        StringBuilder ids = new StringBuilder("\n\nIDs usados:\n");
        appendIdList(ids, "Entidades", context.entityIds());
        appendIdList(ids, "Relações", context.relationIds());
        appendIdList(ids, "Eventos", context.eventIds());
        return ids.toString();
    }

    private void appendIdList(
            StringBuilder builder,
            String label,
            List<String> ids
    ) {
        builder.append("- ").append(label).append(": ");
        if (ids == null || ids.isEmpty()) {
            builder.append("nenhum registro usado\n");
            return;
        }
        builder.append(String.join(", ", ids.stream().limit(12).toList()));
        if (ids.size() > 12) {
            builder.append(", ...");
        }
        builder.append("\n");
    }

    private void appendBullets(
            StringBuilder builder,
            List<String> items
    ) {
        if (items == null || items.isEmpty()) {
            builder.append("- Não há registros suficientes.\n");
            return;
        }
        for (String item : items) {
            builder.append("- ").append(item).append("\n");
        }
    }

    private String formatNumber(BigDecimal value) {
        if (value == null) {
            return "não informado";
        }
        return value.stripTrailingZeros().toPlainString();
    }

    private String nonBlank(String value, String fallback) {
        return value == null || value.isBlank()
                ? fallback
                : value.trim();
    }
}
