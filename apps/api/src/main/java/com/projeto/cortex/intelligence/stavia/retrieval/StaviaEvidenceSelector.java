package com.projeto.cortex.intelligence.stavia.retrieval;

import com.projeto.cortex.intelligence.stavia.intent.StaviaIntent;
import com.projeto.cortex.intelligence.stavia.model.StaviaContext;
import com.projeto.cortex.intelligence.stavia.model.StaviaEvidence;
import com.projeto.cortex.intelligence.stavia.model.StaviaEvidenceTypes;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

@Component
public class StaviaEvidenceSelector {

    public List<StaviaEvidence> select(
            StaviaIntent intent,
            StaviaContext context
    ) {
        Set<String> acceptedTypes = acceptedTypes(intent);

        if (acceptedTypes.isEmpty()) {
            return context.evidences();
        }

        return context.evidences()
                .stream()
                .filter(evidence ->
                        acceptedTypes.contains(
                                evidence.type()
                        )
                )
                .toList();
    }

    private Set<String> acceptedTypes(
            StaviaIntent intent
    ) {
        return switch (intent) {
            case CONSULTAR_OBRA ->
                    Set.of(
                            StaviaEvidenceTypes.OBRA,
                            StaviaEvidenceTypes.CONTEXTO_OBRA
                    );

            case CONSULTAR_ESTADO_ATUAL ->
                    Set.of(
                            StaviaEvidenceTypes.OBRA,
                            StaviaEvidenceTypes.ESTADO,
                            StaviaEvidenceTypes.RDO,
                            StaviaEvidenceTypes.PROGRAMACAO_OPERACIONAL,
                            StaviaEvidenceTypes.PDOR,
                            StaviaEvidenceTypes.RELACAO_ONTOLOGICA,
                            StaviaEvidenceTypes.CONTEXTO_OBRA
                    );

            case CONSULTAR_HISTORICO ->
                    Set.of(
                            StaviaEvidenceTypes.EVENTO_OPERACIONAL
                    );

            case CONSULTAR_RDO ->
                    Set.of(
                            StaviaEvidenceTypes.RDO,
                            StaviaEvidenceTypes.RDO_ATTRIBUTE,
                            StaviaEvidenceTypes.RDO_MATERIAL,
                            StaviaEvidenceTypes.RDO_MAO_OBRA,
                            StaviaEvidenceTypes.RDO_EQUIPAMENTO,
                            StaviaEvidenceTypes.RDO_CONTROLE_GEOMETRICO,
                            StaviaEvidenceTypes.RDO_EXECUCAO_SERVICO,
                            StaviaEvidenceTypes.RDO_ALOCACAO_COLABORADOR,
                            StaviaEvidenceTypes.RDO_ATTACHMENT,
                            StaviaEvidenceTypes.RDO_OPERATIONAL_EVENT,
                            StaviaEvidenceTypes.RDO_AGREGACAO,
                            StaviaEvidenceTypes.TRECHO_OPERACIONAL,
                            StaviaEvidenceTypes.RELACAO_ONTOLOGICA,
                            StaviaEvidenceTypes.CONTEXTO_OBRA
                    );

            case CONSULTAR_PROGRAMACAO ->
                    Set.of(
                            StaviaEvidenceTypes.RDO,
                            StaviaEvidenceTypes.RDO_ATTRIBUTE,
                            StaviaEvidenceTypes.PROGRAMACAO_OPERACIONAL,
                            StaviaEvidenceTypes.RELACAO_ONTOLOGICA,
                            StaviaEvidenceTypes.CONTEXTO_OBRA
                    );

            case CONSULTAR_EQUIPE ->
                    Set.of(
                            StaviaEvidenceTypes.EQUIPE
                    );

            case CONSULTAR_ATIVO ->
                    Set.of(
                            StaviaEvidenceTypes.ATIVO,
                            StaviaEvidenceTypes.EQUIPAMENTO
                    );

            case CONSULTAR_OCORRENCIA ->
                    Set.of(
                            StaviaEvidenceTypes.OCORRENCIA
                    );

            case CONSULTAR_PDOR ->
                    Set.of(
                            StaviaEvidenceTypes.PDOR
                    );

            case CONSULTAR_RECEITA,
                 CONSULTAR_MARGEM,
                 CONSULTAR_PREVISAO_FINANCEIRA,
                 CONSULTAR_PRODUCAO,
                 CONSULTAR_RECEITA_EM_RISCO ->
                    Set.of(
                            StaviaEvidenceTypes.PREVISAO_FINANCEIRA,
                            StaviaEvidenceTypes.PDOR
                    );

            case CONSULTAR_ALOCACAO_COLABORADOR ->
                    Set.of(
                            StaviaEvidenceTypes.ALOCACAO_COLABORADOR,
                            StaviaEvidenceTypes.COLABORADOR
                    );

            case CONSULTAR_FREQUENCIA,
                 CONSULTAR_BANCO_HORAS ->
                    Set.of(
                            StaviaEvidenceTypes.FREQUENCIA,
                            StaviaEvidenceTypes.ALOCACAO_COLABORADOR
                    );

            case RESUMIR_OBRA ->
                    Set.of(
                            StaviaEvidenceTypes.OBRA,
                            StaviaEvidenceTypes.ESTADO,
                            StaviaEvidenceTypes.EVENTO_OPERACIONAL,
                            StaviaEvidenceTypes.RDO,
                            StaviaEvidenceTypes.RDO_ATTRIBUTE,
                            StaviaEvidenceTypes.PROGRAMACAO_OPERACIONAL,
                            StaviaEvidenceTypes.OCORRENCIA,
                            StaviaEvidenceTypes.INCIDENTE,
                            StaviaEvidenceTypes.EQUIPE,
                            StaviaEvidenceTypes.COLABORADOR,
                            StaviaEvidenceTypes.ATIVO,
                            StaviaEvidenceTypes.EQUIPAMENTO,
                            StaviaEvidenceTypes.PDOR,
                            StaviaEvidenceTypes.PREVISAO_FINANCEIRA,
                            StaviaEvidenceTypes.ALOCACAO_COLABORADOR,
                            StaviaEvidenceTypes.FREQUENCIA,
                            StaviaEvidenceTypes.RELACAO_ONTOLOGICA,
                            StaviaEvidenceTypes.CONTEXTO_OBRA
                    );

            case DESCONHECIDA ->
                    Set.of();
        };
    }
}
