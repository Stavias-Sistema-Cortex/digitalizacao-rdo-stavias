package com.projeto.cortex.obras.trecho;

import com.projeto.cortex.auth.CurrentUserService;
import com.projeto.cortex.obras.Obra;
import com.projeto.cortex.obras.ObraRepository;
import com.projeto.cortex.obras.trecho.ObraTrechoResponse.DiaExecutado;
import com.projeto.cortex.obras.trecho.ObraTrechoResponse.Origem;
import com.projeto.cortex.obras.trecho.ObraTrechoResponse.Periodo;
import com.projeto.cortex.obras.trecho.ObraTrechoResponse.ResumoTrecho;
import com.projeto.cortex.obras.trecho.ObraTrechoResponse.SegmentoTrecho;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Deriva a projeção linear do trecho a partir do que já está persistido.
 *
 * <p>Não existe tabela de trecho: o trecho é a leitura conjunta do que foi
 * programado ({@code programacao_operacional}) e do que foi efetivamente
 * executado ({@code rdo_controle_geometrico} e {@code execucao_servico_rdo}).
 * Nenhuma linha é inventada e nenhum valor ausente vira zero.</p>
 */
@Service
public class ObraTrechoService {

    private static final String SEGMENTOS_PROGRAMACAO = """
            SELECT p.id, p.data_programacao, p.servico, p.rodovia, p.sentido,
                   p.faixa, p.km_inicial, p.km_final, p.extensao_m, p.largura_m,
                   p.area_m2, p.tonelada_massa, p.status
              FROM programacao_operacional p
             WHERE p.obra_id = ?
               AND p.cancelado_em IS NULL
             ORDER BY p.data_programacao ASC, p.id ASC
            """;

    private static final String SEGMENTOS_CONTROLE_GEOMETRICO = """
            SELECT c.id, c.rdo_id, r.numero_rdo, r.data_rdo,
                   r.status AS rdo_status,
                   c.subtrecho, c.pista, c.faixa, c.km_inicial, c.km_final,
                   c.estaca_inicial, c.estaca_final, c.comprimento_m,
                   c.largura_m, c.area_m2, c.massa_tonelada,
                   c.atividade_observacoes
              FROM rdo_controle_geometrico c
              JOIN rdo r ON r.id = c.rdo_id
             WHERE r.obra_id = ?
               AND r.cancelado_em IS NULL
             ORDER BY r.data_rdo ASC, c.id ASC
            """;

    private static final String SEGMENTOS_EXECUCAO_SERVICO = """
            SELECT e.id, e.rdo_id, r.numero_rdo, r.status AS rdo_status,
                   e.data_execucao, e.servico_nome,
                   e.trecho_inicial, e.trecho_final, e.localizacao,
                   e.pista, e.faixa,
                   e.status_validacao, e.quantidade_executada, e.unidade_medida
              FROM execucao_servico_rdo e
              JOIN rdo r ON r.id = e.rdo_id
             WHERE e.obra_id = ?
               AND e.cancelada = false
               AND r.cancelado_em IS NULL
             ORDER BY e.data_execucao ASC, e.id ASC
            """;

    /**
     * Trecho desenhado sobre o mapa, que é o RDO visto por outro ângulo.
     *
     * <p>As chaves lidas são as mesmas que a política de payload da ontologia
     * já autoriza para geometria. Só entra o que está vigente: uma geometria
     * encerrada descreve um acordo que deixou de valer.
     *
     * <p>O {@code JOIN} com o RDO é o que faltava, e a ausência dele era
     * visível na tela. As outras três fontes deste serviço descendem do
     * apontamento e somem quando ele é apagado; esta filtrava obra, categoria e
     * status e nada mais, então o desenho sobrevivia ao RDO que ele
     * representava — e, sem amarração, a rodovia digitada à mão podia ser outra
     * que ninguém conferia. Desenhar e apontar são duas portas para o mesmo
     * registro, e agora as duas fecham juntas.
     */
    private static final String SEGMENTOS_TRECHO_CADASTRADO = """
            SELECT g.id,
                   g.valido_desde,
                   g.objeto_id AS rdo_id,
                   r.numero_rdo,
                   r.data_rdo,
                   r.status AS rdo_status,
                   g.propriedades_json ->> 'nome' AS nome,
                   g.propriedades_json ->> 'rodovia' AS rodovia,
                   g.propriedades_json ->> 'sentido' AS sentido,
                   g.propriedades_json ->> 'faixa' AS faixa,
                   g.propriedades_json ->> 'status' AS status,
                   g.propriedades_json ->> 'kmInicial' AS km_inicial,
                   g.propriedades_json ->> 'kmFinal' AS km_final,
                   g.propriedades_json ->> 'extensaoM' AS extensao_m
              FROM obra_geometria g
              JOIN rdo r
                ON r.id = g.objeto_id
               AND r.obra_id = g.obra_id
             WHERE g.obra_id = ?
               AND g.categoria = 'TRECHO'
               AND g.status = 'ATIVA'
               AND g.objeto_tipo = 'RDO'
               AND r.cancelado_em IS NULL
             ORDER BY g.valido_desde ASC, g.id ASC
            """;

    /** Interdição declarada nas duas faixas de uma vez. */
    private static final String FAIXA_AMBAS = "AMBAS";

    /** RDO ainda em preenchimento pelo apontador. */
    private static final String RASCUNHO = "RASCUNHO";

    private static final String ULTIMA_ATUALIZACAO = """
            SELECT MAX(momento) FROM (
                SELECT MAX(p.atualizado_em) AS momento
                  FROM programacao_operacional p
                 WHERE p.obra_id = ? AND p.cancelado_em IS NULL
                UNION ALL
                SELECT MAX(c.atualizado_em)
                  FROM rdo_controle_geometrico c
                  JOIN rdo r ON r.id = c.rdo_id
                 WHERE r.obra_id = ? AND r.cancelado_em IS NULL
                UNION ALL
                SELECT MAX(e.atualizado_em)
                  FROM execucao_servico_rdo e WHERE e.obra_id = ?
                UNION ALL
                SELECT MAX(g.atualizado_em)
                  FROM obra_geometria g
                 WHERE g.obra_id = ?
                   AND g.categoria = 'TRECHO'
                   AND g.status = 'ATIVA'
            ) AS fontes
            """;

    private final JdbcTemplate jdbcTemplate;
    private final ObraRepository obraRepository;
    private final CurrentUserService currentUserService;

    public ObraTrechoService(
            JdbcTemplate jdbcTemplate,
            ObraRepository obraRepository,
            CurrentUserService currentUserService
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.obraRepository = obraRepository;
        this.currentUserService = currentUserService;
    }

    @Transactional(readOnly = true)
    public ObraTrechoResponse buscarTrecho(String obraId) {
        return buscarTrecho(obraId, null, null);
    }

    /**
     * @param de  primeira data considerada, ou {@code null} para não limitar
     * @param ate última data considerada, ou {@code null} para não limitar
     */
    @Transactional(readOnly = true)
    public ObraTrechoResponse buscarTrecho(String obraId, LocalDate de, LocalDate ate) {
        currentUserService.requireWorksiteAccess(obraId);
        if (de != null && ate != null && de.isAfter(ate)) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "A data inicial do período não pode ser posterior à final."
            );
        }
        Obra obra = obraRepository.findById(obraId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Obra não encontrada."
                ));

        List<SegmentoTrecho> todos = new ArrayList<>();
        todos.addAll(jdbcTemplate.query(
                SEGMENTOS_PROGRAMACAO, this::mapProgramacao, obraId
        ));
        todos.addAll(jdbcTemplate.query(
                SEGMENTOS_CONTROLE_GEOMETRICO, this::mapControleGeometrico, obraId
        ));
        todos.addAll(jdbcTemplate.query(
                SEGMENTOS_EXECUCAO_SERVICO, this::mapExecucaoServico, obraId
        ));
        todos.addAll(segmentosDeclarados(obraId));
        // Antes de qualquer recorte: a herança precisa enxergar todos os
        // controles do RDO, inclusive os que um filtro de data removeria.
        todos = inferirPistaDoControle(todos);
        todos.sort(
                Comparator.comparing(
                        SegmentoTrecho::data,
                        Comparator.nullsLast(Comparator.naturalOrder())
                ).thenComparing(SegmentoTrecho::id)
        );

        // O período disponível descreve o que a obra realmente registrou, para
        // que a interface consiga limitar o seletor de datas ao que existe.
        Periodo disponivel = periodoDisponivel(todos);
        List<SegmentoTrecho> segmentos = filtrarPorPeriodo(todos, de, ate);

        return new ObraTrechoResponse(
                obra.getId(),
                obra.getNome(),
                obra.getRodovia(),
                operavel(obra),
                distintos(segmentos.stream().map(SegmentoTrecho::sentido).toList()),
                distintos(segmentos.stream().map(SegmentoTrecho::faixa).toList()),
                menorKm(segmentos),
                maiorKm(segmentos),
                ultimaAtualizacao(obraId),
                disponivel,
                new Periodo(de, ate),
                List.copyOf(segmentos),
                diasExecutados(segmentos),
                resumo(segmentos)
        );
    }

    /**
     * Completa a pista do serviço executado com a do controle geométrico do
     * mesmo RDO.
     *
     * <p>{@code execucao_servico_rdo} só passou a registrar pista e faixa a
     * partir de V45, e todo o histórico veio sem elas. Um serviço sem pista cai
     * numa faixa "não declarada" que não corresponde a lugar nenhum da rodovia,
     * some do lado certo do canteiro e atrapalha a leitura do trecho. O
     * controle geométrico do mesmo RDO descreve o mesmo dia e a mesma frente de
     * trabalho — é a única fonte dentro do próprio registro capaz de dizer onde
     * aquilo aconteceu.</p>
     *
     * <p>A conclusão é conservadora e sempre marcada em
     * {@code pistaInferida}: vale o controle cuja faixa de quilômetros encosta
     * na do serviço e, quando essa interseção não resolve, apenas o consenso de
     * todos os controles daquele RDO. Qualquer divergência deixa o segmento sem
     * pista, porque um palpite errado põe o serviço na pista contrária — o que
     * é pior do que admitir que a pista não foi declarada.</p>
     */
    private List<SegmentoTrecho> inferirPistaDoControle(List<SegmentoTrecho> segmentos) {
        Map<String, List<SegmentoTrecho>> controlesPorRdo = new java.util.HashMap<>();
        for (SegmentoTrecho segmento : segmentos) {
            if (segmento.origem() == Origem.RDO_CONTROLE
                    && segmento.rdoId() != null
                    && segmento.pista() != null) {
                controlesPorRdo
                        .computeIfAbsent(segmento.rdoId(), chave -> new ArrayList<>())
                        .add(segmento);
            }
        }
        if (controlesPorRdo.isEmpty()) {
            return segmentos;
        }

        List<SegmentoTrecho> resultado = new ArrayList<>(segmentos.size());
        for (SegmentoTrecho segmento : segmentos) {
            resultado.add(herdarPista(segmento, controlesPorRdo));
        }
        return resultado;
    }

    private SegmentoTrecho herdarPista(
            SegmentoTrecho segmento,
            Map<String, List<SegmentoTrecho>> controlesPorRdo
    ) {
        if (segmento.origem() != Origem.EXECUCAO_SERVICO
                || segmento.pista() != null
                || segmento.rdoId() == null) {
            return segmento;
        }
        List<SegmentoTrecho> candidatos = controlesPorRdo.get(segmento.rdoId());
        if (candidatos == null) {
            return segmento;
        }
        List<SegmentoTrecho> sobrepostos = candidatos.stream()
                .filter(controle -> sobrepoeEmKm(controle, segmento))
                .toList();
        List<SegmentoTrecho> base = sobrepostos.isEmpty() ? candidatos : sobrepostos;

        String pista = consenso(base, SegmentoTrecho::pista);
        if (pista == null) {
            return segmento;
        }
        String faixa = segmento.faixa() != null
                ? segmento.faixa()
                : consenso(base, SegmentoTrecho::faixa);

        return new SegmentoTrecho(
                segmento.id(), segmento.origem(), segmento.rdoId(),
                segmento.numeroRdo(), segmento.data(), segmento.servicoNome(),
                segmento.subtrecho(), segmento.sentido(), pista, faixa,
                segmento.kmInicial(), segmento.kmFinal(),
                segmento.estacaInicial(), segmento.estacaFinal(),
                segmento.extensaoM(), segmento.larguraM(), segmento.areaM2(),
                segmento.massaTonelada(), segmento.status(),
                segmento.rdoStatus(), true
        );
    }

    /**
     * Um serviço sem os dois quilômetros não encosta em controle nenhum, e é aí
     * que a decisão cai no consenso do RDO inteiro.
     */
    private static boolean sobrepoeEmKm(SegmentoTrecho a, SegmentoTrecho b) {
        BigDecimal aMin = menorKm(List.of(a));
        BigDecimal aMax = maiorKm(List.of(a));
        BigDecimal bMin = menorKm(List.of(b));
        BigDecimal bMax = maiorKm(List.of(b));
        if (aMin == null || aMax == null || bMin == null || bMax == null) {
            return false;
        }
        return aMin.compareTo(bMax) <= 0 && bMin.compareTo(aMax) <= 0;
    }

    /** O valor único declarado pelo grupo, ou nulo quando eles discordam. */
    private static String consenso(
            List<SegmentoTrecho> segmentos,
            java.util.function.Function<SegmentoTrecho, String> campo
    ) {
        Set<String> valores = new LinkedHashSet<>();
        for (SegmentoTrecho segmento : segmentos) {
            String valor = campo.apply(segmento);
            if (valor != null) {
                valores.add(valor);
            }
        }
        return valores.size() == 1 ? valores.iterator().next() : null;
    }

    /**
     * Mantém o segmento cuja data cai no período pedido.
     *
     * Segmento sem data não é descartado quando não há filtro, mas sai assim que
     * um período é escolhido: não há como afirmar que ele pertence ao intervalo.
     */
    private List<SegmentoTrecho> filtrarPorPeriodo(
            List<SegmentoTrecho> segmentos,
            LocalDate de,
            LocalDate ate
    ) {
        if (de == null && ate == null) {
            return segmentos;
        }
        return segmentos.stream()
                .filter(segmento -> segmento.data() != null)
                .filter(segmento -> de == null || !segmento.data().isBefore(de))
                .filter(segmento -> ate == null || !segmento.data().isAfter(ate))
                .toList();
    }

    private Periodo periodoDisponivel(List<SegmentoTrecho> segmentos) {
        List<LocalDate> datas = segmentos.stream()
                .map(SegmentoTrecho::data)
                .filter(java.util.Objects::nonNull)
                .sorted()
                .toList();
        return datas.isEmpty()
                ? new Periodo(null, null)
                : new Periodo(datas.get(0), datas.get(datas.size() - 1));
    }

    /** Agrega a produção executada por dia, na ordem cronológica. */
    private List<DiaExecutado> diasExecutados(List<SegmentoTrecho> segmentos) {
        Map<LocalDate, List<SegmentoTrecho>> porDia = new TreeMap<>();
        for (SegmentoTrecho segmento : segmentos) {
            if (segmento.origem() == Origem.PROGRAMACAO || segmento.data() == null) {
                continue;
            }
            porDia.computeIfAbsent(segmento.data(), chave -> new ArrayList<>())
                    .add(segmento);
        }

        List<DiaExecutado> dias = new ArrayList<>();
        for (Map.Entry<LocalDate, List<SegmentoTrecho>> entrada : porDia.entrySet()) {
            List<SegmentoTrecho> doDia = entrada.getValue();
            BigDecimal extensao = BigDecimal.ZERO;
            BigDecimal massa = BigDecimal.ZERO;
            Set<String> rdos = new LinkedHashSet<>();
            for (SegmentoTrecho segmento : doDia) {
                extensao = soma(extensao, segmento.extensaoM());
                massa = soma(massa, segmento.massaTonelada());
                if (segmento.rdoId() != null) {
                    rdos.add(segmento.rdoId());
                }
            }
            dias.add(new DiaExecutado(
                    entrada.getKey(),
                    doDia.size(),
                    rdos.size(),
                    extensao,
                    massa,
                    menorKm(doDia),
                    maiorKm(doDia)
            ));
        }
        return List.copyOf(dias);
    }

    private boolean operavel(Obra obra) {
        String status = obra.getStatus() == null
                ? ""
                : obra.getStatus().trim().toUpperCase(Locale.ROOT);
        return obra.getArquivadoEm() == null && !"INATIVA".equals(status);
    }

    private SegmentoTrecho mapProgramacao(ResultSet rs, int rowNum) throws SQLException {
        return new SegmentoTrecho(
                rs.getString("id"),
                Origem.PROGRAMACAO,
                null,
                null,
                localDate(rs, "data_programacao"),
                rs.getString("servico"),
                null,
                texto(rs.getString("sentido")),
                null,
                texto(rs.getString("faixa")),
                QuilometroParser.parse(rs.getString("km_inicial")),
                QuilometroParser.parse(rs.getString("km_final")),
                null,
                null,
                rs.getBigDecimal("extensao_m"),
                rs.getBigDecimal("largura_m"),
                rs.getBigDecimal("area_m2"),
                rs.getBigDecimal("tonelada_massa"),
                texto(rs.getString("status")),
                null,
                false
        );
    }

    private SegmentoTrecho mapControleGeometrico(ResultSet rs, int rowNum) throws SQLException {
        return new SegmentoTrecho(
                rs.getString("id"),
                Origem.RDO_CONTROLE,
                rs.getString("rdo_id"),
                texto(rs.getString("numero_rdo")),
                localDate(rs, "data_rdo"),
                texto(rs.getString("atividade_observacoes")),
                texto(rs.getString("subtrecho")),
                null,
                texto(rs.getString("pista")),
                texto(rs.getString("faixa")),
                QuilometroParser.parse(rs.getString("km_inicial")),
                QuilometroParser.parse(rs.getString("km_final")),
                texto(rs.getString("estaca_inicial")),
                texto(rs.getString("estaca_final")),
                rs.getBigDecimal("comprimento_m"),
                rs.getBigDecimal("largura_m"),
                rs.getBigDecimal("area_m2"),
                rs.getBigDecimal("massa_tonelada"),
                texto(rs.getString("rdo_status")),
                texto(rs.getString("rdo_status")),
                false
        );
    }

    /**
     * Trechos declarados sobre o mapa, projetados como segmentos.
     *
     * "Ambas as faixas" vira dois segmentos, um por faixa: a interdição ocupa
     * as duas de verdade, e um bloco só deixaria metade da pista sem marcação
     * no esquemático. A extensão fica no primeiro — reparti-la entre as faixas
     * inventaria uma medição por faixa que ninguém fez.
     */
    private List<SegmentoTrecho> segmentosDeclarados(String obraId) {
        List<TrechoDeclarado> declarados = jdbcTemplate.query(
                SEGMENTOS_TRECHO_CADASTRADO,
                (rs, rowNum) -> new TrechoDeclarado(
                        rs.getString("id"),
                        rs.getString("rdo_id"),
                        texto(rs.getString("numero_rdo")),
                        texto(rs.getString("rdo_status")),
                        // A data do bloco é a do apontamento, não a do
                        // desenho: o trecho conta o dia do trabalho.
                        localDate(rs, "data_rdo") != null
                                ? localDate(rs, "data_rdo")
                                : localDate(rs, "valido_desde"),
                        texto(rs.getString("nome")),
                        texto(rs.getString("sentido")),
                        texto(rs.getString("faixa")),
                        texto(rs.getString("status")),
                        QuilometroParser.parse(rs.getString("km_inicial")),
                        QuilometroParser.parse(rs.getString("km_final")),
                        decimal(rs.getString("extensao_m"))
                ),
                obraId
        );

        List<SegmentoTrecho> segmentos = new ArrayList<>();
        for (TrechoDeclarado declarado : declarados) {
            if (declarado.kmInicial() == null || declarado.kmFinal() == null) {
                // Sem os dois extremos não há bloco desenhável; o registro
                // continua no mapa, mas não entra na régua.
                continue;
            }
            List<String> faixas = FAIXA_AMBAS.equalsIgnoreCase(declarado.faixa())
                    ? List.of("DIREITA", "ESQUERDA")
                    : java.util.Collections.singletonList(declarado.faixa());
            for (int indice = 0; indice < faixas.size(); indice++) {
                String faixa = faixas.get(indice);
                segmentos.add(new SegmentoTrecho(
                        faixas.size() > 1
                                ? declarado.id() + ":" + faixa
                                : declarado.id(),
                        Origem.CADASTRO_MAPA,
                        declarado.rdoId(),
                        declarado.numeroRdo(),
                        declarado.data(),
                        declarado.nome(),
                        null,
                        declarado.sentido(),
                        null,
                        faixa,
                        declarado.kmInicial(),
                        declarado.kmFinal(),
                        null,
                        null,
                        indice == 0 ? declarado.extensaoM() : null,
                        null,
                        null,
                        null,
                        declarado.status(),
                        declarado.rdoStatus(),
                        false
                ));
            }
        }
        return segmentos;
    }

    /** Leitura crua de um trecho declarado, antes de virar segmento. */
    private record TrechoDeclarado(
            String id,
            String rdoId,
            String numeroRdo,
            String rdoStatus,
            LocalDate data,
            String nome,
            String sentido,
            String faixa,
            String status,
            BigDecimal kmInicial,
            BigDecimal kmFinal,
            BigDecimal extensaoM
    ) {
    }

    private SegmentoTrecho mapExecucaoServico(ResultSet rs, int rowNum) throws SQLException {
        return new SegmentoTrecho(
                rs.getString("id"),
                Origem.EXECUCAO_SERVICO,
                rs.getString("rdo_id"),
                texto(rs.getString("numero_rdo")),
                localDate(rs, "data_execucao"),
                texto(rs.getString("servico_nome")),
                texto(rs.getString("localizacao")),
                null,
                texto(rs.getString("pista")),
                texto(rs.getString("faixa")),
                QuilometroParser.parse(rs.getString("trecho_inicial")),
                QuilometroParser.parse(rs.getString("trecho_final")),
                null,
                null,
                extensaoDeServico(rs),
                null,
                null,
                null,
                texto(rs.getString("status_validacao")),
                texto(rs.getString("rdo_status")),
                false
        );
    }

    /**
     * A extensão só é aproveitada quando a unidade medida é metro linear. Para
     * qualquer outra unidade a quantidade não descreve comprimento e o campo
     * permanece nulo em vez de virar um número enganoso.
     */
    private BigDecimal extensaoDeServico(ResultSet rs) throws SQLException {
        String unidade = rs.getString("unidade_medida");
        if (unidade == null) {
            return null;
        }
        String normalizada = unidade.trim().toUpperCase(Locale.ROOT);
        return Set.of("M", "ML", "METRO", "METROS").contains(normalizada)
                ? rs.getBigDecimal("quantidade_executada")
                : null;
    }

    private ResumoTrecho resumo(List<SegmentoTrecho> segmentos) {
        Set<String> rdos = new LinkedHashSet<>();
        BigDecimal extensao = BigDecimal.ZERO;
        BigDecimal area = BigDecimal.ZERO;
        BigDecimal massa = BigDecimal.ZERO;
        LocalDate primeira = null;
        LocalDate ultima = null;

        int rascunhos = 0;
        for (SegmentoTrecho segmento : segmentos) {
            if (segmento.origem() == Origem.PROGRAMACAO) {
                continue;
            }
            if (RASCUNHO.equals(segmento.rdoStatus())) {
                rascunhos += 1;
                continue;
            }
            if (segmento.rdoId() != null) {
                rdos.add(segmento.rdoId());
            }
            extensao = soma(extensao, segmento.extensaoM());
            area = soma(area, segmento.areaM2());
            massa = soma(massa, segmento.massaTonelada());
            if (segmento.data() != null) {
                primeira = primeira == null || segmento.data().isBefore(primeira)
                        ? segmento.data()
                        : primeira;
                ultima = ultima == null || segmento.data().isAfter(ultima)
                        ? segmento.data()
                        : ultima;
            }
        }

        return new ResumoTrecho(
                segmentos.size(), rdos.size(), rascunhos,
                extensao, area, massa, primeira, ultima
        );
    }

    private LocalDateTime ultimaAtualizacao(String obraId) {
        return jdbcTemplate.queryForObject(
                ULTIMA_ATUALIZACAO,
                (rs, rowNum) -> {
                    java.sql.Timestamp momento = rs.getTimestamp(1);
                    return momento == null ? null : momento.toLocalDateTime();
                },
                obraId, obraId, obraId, obraId
        );
    }

    private static BigDecimal soma(BigDecimal acumulado, BigDecimal valor) {
        return valor == null ? acumulado : acumulado.add(valor);
    }

    private static BigDecimal menorKm(java.util.Collection<SegmentoTrecho> segmentos) {
        return segmentos.stream()
                .flatMap(s -> java.util.stream.Stream.of(s.kmInicial(), s.kmFinal()))
                .filter(java.util.Objects::nonNull)
                .min(BigDecimal::compareTo)
                .orElse(null);
    }

    private static BigDecimal maiorKm(java.util.Collection<SegmentoTrecho> segmentos) {
        return segmentos.stream()
                .flatMap(s -> java.util.stream.Stream.of(s.kmInicial(), s.kmFinal()))
                .filter(java.util.Objects::nonNull)
                .max(BigDecimal::compareTo)
                .orElse(null);
    }

    private static List<String> distintos(List<String> valores) {
        return List.copyOf(new LinkedHashSet<>(
                valores.stream().filter(java.util.Objects::nonNull).sorted().toList()
        ));
    }

    private static LocalDate localDate(ResultSet rs, String coluna) throws SQLException {
        java.sql.Date data = rs.getDate(coluna);
        return data == null ? null : data.toLocalDate();
    }

    private static String texto(String valor) {
        return valor == null || valor.isBlank() ? null : valor.trim();
    }

    /**
     * Número lido de uma chave JSON.
     *
     * Texto que não é número devolve nulo em vez de zero: zero é medida válida
     * e inventá-lo faria o esquemático afirmar uma extensão que ninguém mediu.
     */
    private static BigDecimal decimal(String valor) {
        String limpo = texto(valor);
        if (limpo == null) {
            return null;
        }
        try {
            return new BigDecimal(limpo);
        } catch (NumberFormatException ignorado) {
            return null;
        }
    }
}
