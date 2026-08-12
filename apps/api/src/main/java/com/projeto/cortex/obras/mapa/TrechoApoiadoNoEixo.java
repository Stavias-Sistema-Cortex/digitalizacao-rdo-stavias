package com.projeto.cortex.obras.mapa;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.projeto.cortex.obras.trecho.QuilometroParser;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * O trecho que o RDO apontou por quilômetro, desenhado sobre o eixo da obra.
 *
 * <p>Um trecho só existia no mapa se alguém o tivesse desenhado ali à mão, um
 * desenho por RDO. Quem apontava pelo quilômetro — que é como a obra fala — não
 * via nada: o dado estava lá, com km inicial e final declarados, e a única tela
 * que mostra onde o trabalho aconteceu ficava vazia.
 *
 * <p>O eixo é a régua: a rodovia traçada uma vez, com o quilômetro de cada
 * ponta. Com ela de pé, todo apontamento com quilômetro tem onde se apoiar, e a
 * linha sai daqui — da leitura, como o {@link QuilometroDoApontamento} já faz
 * com o quilômetro. <b>Nada é gravado.</b> Guardar a posição derivada criaria
 * uma segunda cópia do que o RDO já afirma, e as duas divergiriam no primeiro
 * acerto de uma delas; acertar o km no RDO muda o que o mapa mostra na consulta
 * seguinte, e é só isso que precisa acontecer.
 *
 * <p>O que já tem desenho próprio fica de fora: o desenho é a posição declarada
 * por quem estava lá, e sobrepor a ela uma linha derivada mostraria o mesmo
 * trabalho duas vezes, em lugares levemente diferentes.
 */
@Component
public class TrechoApoiadoNoEixo {

    /** Categoria do eixo em {@code obra_geometria}. */
    public static final String CATEGORIA_EIXO = "EIXO_OBRA";

    /** Marca a feição derivada, para o mapa saber que ela não é desenho. */
    public static final String PROPRIEDADE_DERIVADA = "derivadoDoEixo";

    private static final double RAIO_DA_TERRA_M = 6_371_008.8;

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public TrechoApoiadoNoEixo(
            JdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * Acrescenta às feições da obra os trechos que o eixo sustenta.
     *
     * <p>Devolve a mesma lista quando não há eixo cadastrado ou não há
     * apontamento posicionável: sem régua não há como situar quilômetro nenhum,
     * e inventar posição seria pior do que não desenhar.
     */
    public List<ObraGeometriaResponse> projetarEm(
            String obraId,
            List<ObraGeometriaResponse> features
    ) {
        Eixo eixo = lerEixo(features);
        if (eixo == null) {
            return features;
        }
        List<Apontamento> apontamentos = buscarApontamentos(
                obraId, rdosComDesenhoProprio(features)
        );
        if (apontamentos.isEmpty()) {
            return features;
        }

        List<ObraGeometriaResponse> resultado = new ArrayList<>(features);
        for (Apontamento apontamento : apontamentos) {
            List<double[]> recorte = recortarPorKm(
                    eixo, apontamento.kmInicial(), apontamento.kmFinal()
            );
            if (recorte == null) {
                continue;
            }
            resultado.add(feicaoDerivada(eixo, apontamento, recorte));
        }
        return resultado;
    }

    /**
     * O eixo da obra entre as feições já lidas.
     *
     * <p>Uma obra tem um eixo. Havendo mais de um vigente — uma correção que
     * subiu duas vezes, por exemplo —, vale o último da leitura, que é o mais
     * recente na ordenação por vigência. Escolher em silêncio é melhor do que
     * não desenhar nada, e os dois seguem visíveis para quem quiser encerrar o
     * antigo.
     */
    private Eixo lerEixo(List<ObraGeometriaResponse> features) {
        Eixo encontrado = null;
        for (ObraGeometriaResponse feature : features) {
            if (!CATEGORIA_EIXO.equals(feature.categoria())) {
                continue;
            }
            List<double[]> coordenadas = coordenadasDaLinha(feature.geometry());
            BigDecimal kmInicial = kmDaPropriedade(feature, "kmInicial");
            BigDecimal kmFinal = kmDaPropriedade(feature, "kmFinal");
            if (coordenadas == null || kmInicial == null || kmFinal == null) {
                continue;
            }
            // Sem amplitude não há régua: os dois extremos no mesmo quilômetro
            // não dizem onde cai nenhum ponto entre eles.
            if (kmInicial.compareTo(kmFinal) == 0) {
                continue;
            }
            encontrado = new Eixo(
                    feature.id(),
                    coordenadas,
                    kmInicial.doubleValue(),
                    kmFinal.doubleValue()
            );
        }
        return encontrado;
    }

    private BigDecimal kmDaPropriedade(
            ObraGeometriaResponse feature,
            String chave
    ) {
        if (feature.properties() == null) {
            return null;
        }
        Object valor = feature.properties().get(chave);
        if (valor instanceof Number numero) {
            return new BigDecimal(numero.toString());
        }
        return valor instanceof String texto
                ? QuilometroParser.parse(texto)
                : null;
    }

    private List<double[]> coordenadasDaLinha(JsonNode geometry) {
        if (geometry == null
                || !"LineString".equals(geometry.path("type").asText())) {
            return null;
        }
        JsonNode bruto = geometry.path("coordinates");
        if (!bruto.isArray() || bruto.size() < 2) {
            return null;
        }
        List<double[]> pontos = new ArrayList<>(bruto.size());
        for (JsonNode par : bruto) {
            if (!par.isArray() || par.size() < 2
                    || !par.get(0).isNumber() || !par.get(1).isNumber()) {
                return null;
            }
            pontos.add(new double[] {
                    par.get(0).asDouble(), par.get(1).asDouble()
            });
        }
        return pontos;
    }

    /** RDOs que já têm desenho próprio e não precisam do eixo. */
    private List<String> rdosComDesenhoProprio(
            List<ObraGeometriaResponse> features
    ) {
        List<String> rdos = new ArrayList<>();
        for (ObraGeometriaResponse feature : features) {
            if (!"TRECHO".equals(feature.categoria())
                    || !"RDO".equals(feature.objetoTipo())
                    || feature.objetoId() == null
                    || feature.objetoId().isBlank()) {
                continue;
            }
            rdos.add(feature.objetoId().trim());
        }
        return rdos;
    }

    /**
     * As linhas de serviço com quilômetro declarado nos dois extremos.
     *
     * <p>Um extremo isolado descreve um ponto, não um trecho percorrido, e não
     * é posicionável. O RDO apagado leva junto o que apontou: cancelar marca
     * {@code rdo.cancelado_em} e não toca nas linhas, então é o salto pelo RDO
     * que faz o dia apagado sumir do mapa.
     */
    private List<Apontamento> buscarApontamentos(
            String obraId,
            List<String> rdosComDesenho
    ) {
        List<Apontamento> encontrados = new ArrayList<>();
        jdbcTemplate.query(
                """
                SELECT execution.id,
                       execution.rdo_id,
                       execution.servico_nome,
                       execution.trecho_inicial,
                       execution.trecho_final,
                       execution.pista,
                       execution.status_validacao,
                       execution.data_execucao,
                       rdo.numero_rdo,
                       rdo.cidade,
                       rdo.rodovia,
                       rdo.status AS rdo_status
                FROM execucao_servico_rdo execution
                JOIN rdo
                  ON rdo.id = execution.rdo_id
                 AND rdo.cancelado_em IS NULL
                WHERE execution.obra_id = ?
                  AND execution.cancelada = FALSE
                  AND execution.trecho_inicial IS NOT NULL
                  AND execution.trecho_final IS NOT NULL
                  AND NOT (execution.rdo_id = ANY (?))
                ORDER BY execution.data_execucao, execution.id
                """,
                rs -> {
                    BigDecimal inicial =
                            QuilometroParser.parse(rs.getString("trecho_inicial"));
                    BigDecimal fim =
                            QuilometroParser.parse(rs.getString("trecho_final"));
                    if (inicial == null || fim == null) {
                        return;
                    }
                    encontrados.add(new Apontamento(
                            rs.getString("id"),
                            rs.getString("rdo_id"),
                            rs.getString("numero_rdo"),
                            rs.getString("servico_nome"),
                            inicial.doubleValue(),
                            fim.doubleValue(),
                            rs.getString("pista"),
                            rs.getString("cidade"),
                            rs.getString("rodovia"),
                            rs.getString("status_validacao"),
                            rs.getString("rdo_status"),
                            rs.getDate("data_execucao") == null
                                    ? null
                                    : rs.getDate("data_execucao").toLocalDate()
                                            .atStartOfDay()
                    ));
                },
                obraId,
                rdosComDesenho.toArray(String[]::new)
        );
        return encontrados;
    }

    private ObraGeometriaResponse feicaoDerivada(
            Eixo eixo,
            Apontamento apontamento,
            List<double[]> recorte
    ) {
        ArrayNode coordenadas = objectMapper.createArrayNode();
        for (double[] ponto : recorte) {
            ArrayNode par = objectMapper.createArrayNode();
            par.add(ponto[0]);
            par.add(ponto[1]);
            coordenadas.add(par);
        }
        ObjectNode geometry = objectMapper.createObjectNode();
        geometry.put("type", "LineString");
        geometry.set("coordinates", coordenadas);

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put(PROPRIEDADE_DERIVADA, true);
        properties.put("eixoId", eixo.id());
        properties.put("execucaoId", apontamento.execucaoId());
        properties.put("servico", apontamento.servicoNome());
        properties.put("numeroRdo", apontamento.numeroRdo());
        properties.put("kmInicial", apontamento.kmInicial());
        properties.put("kmFinal", apontamento.kmFinal());
        // O balão fala a língua da obra: cidade, quilômetro e pista — os três
        // vindos do próprio RDO, que é quem desenha esta linha.
        if (temTexto(apontamento.pista())) {
            properties.put("pista", apontamento.pista().trim());
        }
        if (temTexto(apontamento.cidade())) {
            properties.put("cidade", apontamento.cidade().trim());
        }
        if (temTexto(apontamento.rodovia())) {
            properties.put("rodovia", apontamento.rodovia().trim());
        }
        properties.put("statusValidacao", apontamento.statusValidacao());
        properties.put("rdoStatus", apontamento.rdoStatus());

        return new ObraGeometriaResponse(
                // A identidade carrega a linha de origem: é ela que o mapa usa
                // para levar uma correção de traçado de volta ao apontamento.
                "eixo:" + apontamento.execucaoId(),
                "TRECHO",
                "RDO",
                apontamento.rdoId(),
                geometry,
                properties,
                "APONTAMENTO_RDO",
                "ATIVA",
                apontamento.dataExecucao(),
                null,
                null,
                0L,
                null,
                null,
                apontamento.dataExecucao(),
                apontamento.dataExecucao()
        );
    }

    /**
     * A parte do eixo que vai de um quilômetro a outro.
     *
     * <p>Os vértices intermediários são preservados: um eixo com curva
     * recortado só pelas pontas viraria uma reta que corta fora da pista. A
     * ordem acompanha a ordem pedida, para o trecho ser desenhado no sentido em
     * que foi apontado.
     *
     * <p>Devolve {@code null} quando o intervalo não encosta no eixo. Um trecho
     * que passa da ponta é recortado ao que existe — a parte de dentro é real.
     */
    static List<double[]> recortarPorKm(Eixo eixo, double kmA, double kmB) {
        if (!Double.isFinite(kmA) || !Double.isFinite(kmB)) {
            return null;
        }
        double menorDoEixo = Math.min(eixo.kmInicial(), eixo.kmFinal());
        double maiorDoEixo = Math.max(eixo.kmInicial(), eixo.kmFinal());
        if (Math.max(kmA, kmB) < menorDoEixo
                || Math.min(kmA, kmB) > maiorDoEixo) {
            return null;
        }

        double[] acumulado = acumular(eixo.coordenadas());
        double comprimento = acumulado[acumulado.length - 1];
        if (comprimento <= 0) {
            return null;
        }

        double inicio = distanciaDoKm(eixo, kmA, comprimento);
        double fim = distanciaDoKm(eixo, kmB, comprimento);
        double menor = Math.min(inicio, fim);
        double maior = Math.max(inicio, fim);

        List<double[]> recorte = new ArrayList<>();
        recorte.add(pontoNaDistancia(eixo.coordenadas(), acumulado, menor));
        for (int i = 0; i < eixo.coordenadas().size(); i += 1) {
            if (acumulado[i] > menor && acumulado[i] < maior) {
                recorte.add(eixo.coordenadas().get(i));
            }
        }
        recorte.add(pontoNaDistancia(eixo.coordenadas(), acumulado, maior));

        if (inicio > fim) {
            List<double[]> invertido = new ArrayList<>(recorte);
            java.util.Collections.reverse(invertido);
            return invertido;
        }
        return recorte;
    }

    private static double distanciaDoKm(
            Eixo eixo,
            double km,
            double comprimento
    ) {
        double menor = Math.min(eixo.kmInicial(), eixo.kmFinal());
        double maior = Math.max(eixo.kmInicial(), eixo.kmFinal());
        double preso = Math.min(maior, Math.max(menor, km));
        double fracao =
                (preso - eixo.kmInicial()) / (eixo.kmFinal() - eixo.kmInicial());
        return fracao * comprimento;
    }

    /** Distância acumulada até cada vértice, do primeiro ao último. */
    private static double[] acumular(List<double[]> coordenadas) {
        double[] acumulado = new double[coordenadas.size()];
        for (int i = 1; i < coordenadas.size(); i += 1) {
            acumulado[i] = acumulado[i - 1]
                    + distanciaM(coordenadas.get(i - 1), coordenadas.get(i));
        }
        return acumulado;
    }

    /**
     * Distância entre dois pontos sobre a esfera, em metros.
     *
     * <p>Haversine e não Euclides: um eixo de rodovia tem dezenas de
     * quilômetros, e medir em graus faria o quilômetro valer coisas diferentes
     * conforme a latitude — o trecho sairia deslocado justamente nas obras mais
     * longas.
     */
    private static double distanciaM(double[] a, double[] b) {
        double deltaLat = Math.toRadians(b[1] - a[1]);
        double deltaLng = Math.toRadians(b[0] - a[0]);
        double seno = Math.pow(Math.sin(deltaLat / 2), 2)
                + Math.cos(Math.toRadians(a[1]))
                * Math.cos(Math.toRadians(b[1]))
                * Math.pow(Math.sin(deltaLng / 2), 2);
        return 2 * RAIO_DA_TERRA_M
                * Math.asin(Math.min(1, Math.sqrt(seno)));
    }

    private static double[] pontoNaDistancia(
            List<double[]> coordenadas,
            double[] acumulado,
            double distancia
    ) {
        if (distancia <= 0) {
            return coordenadas.get(0);
        }
        double comprimento = acumulado[acumulado.length - 1];
        if (distancia >= comprimento) {
            return coordenadas.get(coordenadas.size() - 1);
        }
        for (int i = 1; i < coordenadas.size(); i += 1) {
            if (acumulado[i] < distancia) {
                continue;
            }
            double vao = acumulado[i] - acumulado[i - 1];
            double fracao = vao <= 0 ? 0 : (distancia - acumulado[i - 1]) / vao;
            double[] anterior = coordenadas.get(i - 1);
            double[] atual = coordenadas.get(i);
            return new double[] {
                    anterior[0] + (atual[0] - anterior[0]) * fracao,
                    anterior[1] + (atual[1] - anterior[1]) * fracao,
            };
        }
        return coordenadas.get(coordenadas.size() - 1);
    }

    record Eixo(
            String id,
            List<double[]> coordenadas,
            double kmInicial,
            double kmFinal
    ) {
    }

    private static boolean temTexto(String valor) {
        return valor != null && !valor.isBlank();
    }

    private record Apontamento(
            String execucaoId,
            String rdoId,
            String numeroRdo,
            String servicoNome,
            double kmInicial,
            double kmFinal,
            String pista,
            String cidade,
            String rodovia,
            String statusValidacao,
            String rdoStatus,
            LocalDateTime dataExecucao
    ) {
    }
}
