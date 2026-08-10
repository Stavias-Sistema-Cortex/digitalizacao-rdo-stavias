package com.projeto.cortex.obras.mapa;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * O quilômetro que o mapa mostra é lido do RDO, nunca copiado para cá.
 *
 * <p>A geometria guarda a forma, e só. Foi uma decisão deliberada: o quilômetro
 * existia em dois lugares sem nada que os reconciliasse, e corrigir num deixava
 * o outro mentindo. Ele passou a morar na linha de execução do apontamento.
 *
 * <p>Só que ninguém fechou o outro lado. A resposta do mapa devolve exatamente
 * o que está em {@code propriedades_json}, então o quilômetro simplesmente não
 * chegava à tela — o trecho aparecia desenhado, sem dizer de que quilômetro a
 * que quilômetro ele fala, que é a primeira coisa que se pergunta olhando para
 * uma rodovia.
 *
 * <p>Projetar na leitura resolve sem recriar a divergência: não há segunda
 * cópia a envelhecer, e acertar o RDO muda o que o mapa mostra na consulta
 * seguinte. Por isso isto é uma classe e não um campo gravado.
 *
 * <p>Fica fora de {@code ObraMapaService} porque é outra responsabilidade — o
 * serviço cuida da geometria; esta leitura atravessa a fronteira do RDO — e
 * porque assim ela pode ser exercitada contra banco de verdade sem subir o
 * contexto inteiro.
 */
@Component
public class QuilometroDoApontamento {

    private final JdbcTemplate jdbcTemplate;

    public QuilometroDoApontamento(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** Os dois extremos declarados pelo apontamento. */
    public record Quilometragem(String inicial, String finalKm) {
    }

    /**
     * Devolve as feições com o quilômetro do apontamento acrescentado.
     *
     * <p>O desenho diz qual linha ele representa, em {@code execucaoId}. Quando
     * não diz — porque foi desenhado antes de o elo existir — vale o RDO
     * inteiro, e só se ele tiver uma única frente com quilômetro declarado.
     * Escolher entre várias por adivinhação escreveria no mapa um quilômetro
     * que pertence a outro trabalho.
     */
    public List<ObraGeometriaResponse> projetarEm(
            List<ObraGeometriaResponse> features
    ) {
        Set<String> execucaoIds = new HashSet<>();
        Set<String> rdoIds = new HashSet<>();
        for (ObraGeometriaResponse feature : features) {
            if (!"TRECHO".equals(feature.categoria())) {
                continue;
            }
            String execucaoId = execucaoIdDe(feature);
            if (execucaoId != null) {
                execucaoIds.add(execucaoId);
            } else if (rdoIdDe(feature) != null) {
                rdoIds.add(rdoIdDe(feature));
            }
        }
        if (execucaoIds.isEmpty() && rdoIds.isEmpty()) {
            return features;
        }

        Map<String, Quilometragem> porLinha = porExecucao(execucaoIds);
        Map<String, Quilometragem> porApontamento = unicaPorRdo(rdoIds);

        List<ObraGeometriaResponse> projetadas = new ArrayList<>(features.size());
        for (ObraGeometriaResponse feature : features) {
            String execucaoId = execucaoIdDe(feature);
            String rdoId = rdoIdDe(feature);
            Quilometragem km = execucaoId != null
                    ? porLinha.get(execucaoId)
                    : rdoId == null ? null : porApontamento.get(rdoId);
            projetadas.add(km == null ? feature : comQuilometragem(feature, km));
        }
        return projetadas;
    }

    private static String execucaoIdDe(ObraGeometriaResponse feature) {
        if (!"TRECHO".equals(feature.categoria()) || feature.properties() == null) {
            return null;
        }
        Object valor = feature.properties().get("execucaoId");
        return valor instanceof String texto && !texto.isBlank()
                ? texto.trim()
                : null;
    }

    private static String rdoIdDe(ObraGeometriaResponse feature) {
        if (!"TRECHO".equals(feature.categoria())
                || !"RDO".equals(feature.objetoTipo())
                || feature.objetoId() == null
                || feature.objetoId().isBlank()) {
            return null;
        }
        return feature.objetoId().trim();
    }

    /**
     * O quilômetro não sobrescreve o que a geometria já declara.
     *
     * <p>Um desenho antigo pode ter o campo gravado nas propriedades, de antes
     * de o quilômetro migrar para o RDO. Deixar o valor velho vencer manteria
     * na tela exatamente a divergência que a migração eliminou — e o valor
     * velho é o que ninguém consegue corrigir.
     */
    private static ObraGeometriaResponse comQuilometragem(
            ObraGeometriaResponse feature,
            Quilometragem km
    ) {
        Map<String, Object> properties = new HashMap<>(feature.properties());
        if (km.inicial() != null) {
            properties.put("kmInicial", km.inicial());
        }
        if (km.finalKm() != null) {
            properties.put("kmFinal", km.finalKm());
        }
        return new ObraGeometriaResponse(
                feature.id(), feature.categoria(), feature.objetoTipo(),
                feature.objetoId(), feature.geometry(), properties, feature.fonte(),
                feature.status(), feature.validoDesde(), feature.validoAte(),
                feature.motivoEncerramento(), feature.versao(),
                feature.criadoPor(), feature.atualizadoPor(),
                feature.criadoEm(), feature.atualizadoEm()
        );
    }

    Map<String, Quilometragem> porExecucao(Set<String> execucaoIds) {
        if (execucaoIds.isEmpty()) {
            return Map.of();
        }
        Map<String, Quilometragem> resultado = new HashMap<>();
        jdbcTemplate.query(
                """
                SELECT id, trecho_inicial, trecho_final
                FROM execucao_servico_rdo
                WHERE id = ANY (?)
                  AND cancelada = false
                  AND (trecho_inicial IS NOT NULL OR trecho_final IS NOT NULL)
                """,
                rs -> {
                    resultado.put(rs.getString("id"), new Quilometragem(
                            rs.getString("trecho_inicial"),
                            rs.getString("trecho_final")
                    ));
                },
                (Object) execucaoIds.toArray(String[]::new)
        );
        return resultado;
    }

    /**
     * O quilômetro do RDO que tem uma frente só com quilômetro declarado.
     *
     * <p>{@code HAVING COUNT(*) = 1} é a regra inteira: com duas frentes no
     * mesmo dia não há como saber qual delas o desenho representa.
     */
    Map<String, Quilometragem> unicaPorRdo(Set<String> rdoIds) {
        if (rdoIds.isEmpty()) {
            return Map.of();
        }
        Map<String, Quilometragem> resultado = new HashMap<>();
        jdbcTemplate.query(
                """
                SELECT rdo_id,
                       MIN(trecho_inicial) AS trecho_inicial,
                       MIN(trecho_final) AS trecho_final
                FROM execucao_servico_rdo
                WHERE rdo_id = ANY (?)
                  AND cancelada = false
                  AND (trecho_inicial IS NOT NULL OR trecho_final IS NOT NULL)
                GROUP BY rdo_id
                HAVING COUNT(*) = 1
                """,
                rs -> {
                    resultado.put(rs.getString("rdo_id"), new Quilometragem(
                            rs.getString("trecho_inicial"),
                            rs.getString("trecho_final")
                    ));
                },
                (Object) rdoIds.toArray(String[]::new)
        );
        return resultado;
    }
}
