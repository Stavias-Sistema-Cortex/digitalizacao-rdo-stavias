package com.projeto.cortex.intelligence.stavia.intent;

import org.springframework.stereotype.Component;

import java.text.Normalizer;
import java.util.Locale;

@Component
public class StaviaIntentClassifier {

    public StaviaIntent classify(String question) {
        String normalized = normalize(question);

        if (containsAny(
                normalized,
                "programacao",
                "programado",
                "planejado"
        )) {
            return StaviaIntent.CONSULTAR_PROGRAMACAO;
        }

        if (containsAny(
                normalized,
                "historico",
                "mudou",
                "ultimas 24",
                "aconteceu"
        )) {
            return StaviaIntent.CONSULTAR_HISTORICO;
        }

        if (containsAny(normalized, "rdo", "relatorio diario")) {
            return StaviaIntent.CONSULTAR_RDO;
        }

        if (containsAny(
                normalized,
                "equipe",
                "colaborador",
                "encarregado",
                "engenheiro",
                "mao de obra",
                "quem trabalhou",
                "quantas pessoas",
                "quantos trabalhadores"
        )) {
            return StaviaIntent.CONSULTAR_EQUIPE;
        }

        if (containsAny(
                normalized,
                "equipamento",
                "ativo",
                "maquina",
                "veiculo"
        )) {
            return StaviaIntent.CONSULTAR_ATIVO;
        }

        if (containsAny(
                normalized,
                "ocorrencia",
                "incidente",
                "problema",
                "parada"
        )) {
            return StaviaIntent.CONSULTAR_OCORRENCIA;
        }

        if (containsAny(
                normalized,
                "pdoc",
                "previsao de custo",
                "risco de estouro",
                "risco de custo",
                "projecao de custo",
                "custo final estimado",
                "probabilidade de ultrapassar"
        )) {
            return StaviaIntent.CONSULTAR_PDOC;
        }

        if (containsAny(
                normalized,
                "resumo",
                "resuma",
                "visao geral"
        )) {
            return StaviaIntent.RESUMIR_OBRA;
        }

        if (containsAny(
                normalized,
                "estado atual",
                "situacao atual",
                "como esta"
        )) {
            return StaviaIntent.CONSULTAR_ESTADO_ATUAL;
        }

        if (
                normalized.contains("obra")
                && containsAny(
                        normalized,
                        "data",
                        "quando",
                        "comec",
                        "inicio",
                        "termin",
                        "fim",
                        "nome",
                        "codigo",
                        "cw",
                        "contrato",
                        "cliente",
                        "cidade",
                        "local",
                        "rodovia",
                        "cadastro",
                        "cadastrada",
                        "criada",
                        "atualizada",
                        "status",
                        "qual obra",
                        "que obra"
                )
        ) {
            return StaviaIntent.CONSULTAR_OBRA;
        }

        return StaviaIntent.DESCONHECIDA;
    }

    private boolean containsAny(
            String value,
            String... candidates
    ) {
        for (String candidate : candidates) {
            if (value.contains(candidate)) {
                return true;
            }
        }

        return false;
    }

    private String normalize(String value) {
        String normalized = Normalizer.normalize(
                value,
                Normalizer.Form.NFD
        );

        return normalized
                .replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT)
                .trim();
    }
}
