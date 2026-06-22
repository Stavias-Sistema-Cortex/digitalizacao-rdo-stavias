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
                            StaviaEvidenceTypes.OBRA
                    );

            case CONSULTAR_ESTADO_ATUAL ->
                    Set.of(
                            StaviaEvidenceTypes.OBRA,
                            StaviaEvidenceTypes.ESTADO,
                            StaviaEvidenceTypes.RDO,
                            StaviaEvidenceTypes.PROGRAMACAO_OPERACIONAL,
                            StaviaEvidenceTypes.PDOC,
                            StaviaEvidenceTypes.RELACAO_ONTOLOGICA
                    );

            case CONSULTAR_HISTORICO ->
                    Set.of(
                            StaviaEvidenceTypes.EVENTO_OPERACIONAL,
                            StaviaEvidenceTypes.RDO,
                            StaviaEvidenceTypes.OCORRENCIA,
                            StaviaEvidenceTypes.INCIDENTE
                    );

            case CONSULTAR_RDO ->
                    Set.of(
                            StaviaEvidenceTypes.RDO,
                            StaviaEvidenceTypes.EVENTO_OPERACIONAL,
                            StaviaEvidenceTypes.RELACAO_ONTOLOGICA
                    );

            case CONSULTAR_PROGRAMACAO ->
                    Set.of(
                            StaviaEvidenceTypes.PROGRAMACAO_OPERACIONAL,
                            StaviaEvidenceTypes.EVENTO_OPERACIONAL,
                            StaviaEvidenceTypes.RELACAO_ONTOLOGICA
                    );

            case CONSULTAR_EQUIPE ->
                    Set.of(
                            StaviaEvidenceTypes.EQUIPE
                    );

            case CONSULTAR_ATIVO ->
                    Set.of(
                            StaviaEvidenceTypes.ATIVO,
                            StaviaEvidenceTypes.EQUIPAMENTO,
                            StaviaEvidenceTypes.RELACAO_ONTOLOGICA
                    );

            case CONSULTAR_OCORRENCIA ->
                    Set.of(
                            StaviaEvidenceTypes.OCORRENCIA,
                            StaviaEvidenceTypes.INCIDENTE,
                            StaviaEvidenceTypes.EVENTO_OPERACIONAL
                    );

            case CONSULTAR_PDOC ->
                    Set.of(
                            StaviaEvidenceTypes.PDOC
                    );

            case RESUMIR_OBRA ->
                    Set.of(
                            StaviaEvidenceTypes.OBRA,
                            StaviaEvidenceTypes.ESTADO,
                            StaviaEvidenceTypes.EVENTO_OPERACIONAL,
                            StaviaEvidenceTypes.RDO,
                            StaviaEvidenceTypes.PROGRAMACAO_OPERACIONAL,
                            StaviaEvidenceTypes.OCORRENCIA,
                            StaviaEvidenceTypes.INCIDENTE,
                            StaviaEvidenceTypes.EQUIPE,
                            StaviaEvidenceTypes.COLABORADOR,
                            StaviaEvidenceTypes.ATIVO,
                            StaviaEvidenceTypes.EQUIPAMENTO,
                            StaviaEvidenceTypes.PDOC,
                            StaviaEvidenceTypes.RELACAO_ONTOLOGICA
                    );

            case DESCONHECIDA ->
                    Set.of();
        };
    }
}
