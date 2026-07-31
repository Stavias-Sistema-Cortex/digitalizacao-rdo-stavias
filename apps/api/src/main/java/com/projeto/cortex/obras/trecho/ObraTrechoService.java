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
            SELECT c.id, c.rdo_id, r.numero_rdo, r.data_rdo, r.status,
                   c.subtrecho, c.pista, c.faixa, c.km_inicial, c.km_final,
                   c.estaca_inicial, c.estaca_final, c.comprimento_m,
                   c.largura_m, c.area_m2, c.massa_tonelada,
                   c.atividade_observacoes
              FROM rdo_controle_geometrico c
              JOIN rdo r ON r.id = c.rdo_id
             WHERE r.obra_id = ?
             ORDER BY r.data_rdo ASC, c.id ASC
            """;

    private static final String SEGMENTOS_EXECUCAO_SERVICO = """
            SELECT e.id, e.rdo_id, r.numero_rdo, e.data_execucao, e.servico_nome,
                   e.trecho_inicial, e.trecho_final, e.localizacao,
                   e.status_validacao, e.quantidade_executada, e.unidade_medida
              FROM execucao_servico_rdo e
              JOIN rdo r ON r.id = e.rdo_id
             WHERE e.obra_id = ?
               AND e.cancelada = false
             ORDER BY e.data_execucao ASC, e.id ASC
            """;

    private static final String ULTIMA_ATUALIZACAO = """
            SELECT MAX(momento) FROM (
                SELECT MAX(p.atualizado_em) AS momento
                  FROM programacao_operacional p
                 WHERE p.obra_id = ? AND p.cancelado_em IS NULL
                UNION ALL
                SELECT MAX(c.atualizado_em)
                  FROM rdo_controle_geometrico c
                  JOIN rdo r ON r.id = c.rdo_id WHERE r.obra_id = ?
                UNION ALL
                SELECT MAX(e.atualizado_em)
                  FROM execucao_servico_rdo e WHERE e.obra_id = ?
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
                texto(rs.getString("status"))
        );
    }

    private SegmentoTrecho mapControleGeometrico(ResultSet rs, int rowNum) throws SQLException {
        String pista = texto(rs.getString("pista"));
        return new SegmentoTrecho(
                rs.getString("id"),
                Origem.RDO_CONTROLE,
                rs.getString("rdo_id"),
                texto(rs.getString("numero_rdo")),
                localDate(rs, "data_rdo"),
                texto(rs.getString("atividade_observacoes")),
                texto(rs.getString("subtrecho")),
                pista,
                pista,
                texto(rs.getString("faixa")),
                QuilometroParser.parse(rs.getString("km_inicial")),
                QuilometroParser.parse(rs.getString("km_final")),
                texto(rs.getString("estaca_inicial")),
                texto(rs.getString("estaca_final")),
                rs.getBigDecimal("comprimento_m"),
                rs.getBigDecimal("largura_m"),
                rs.getBigDecimal("area_m2"),
                rs.getBigDecimal("massa_tonelada"),
                texto(rs.getString("status"))
        );
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
                null,
                null,
                QuilometroParser.parse(rs.getString("trecho_inicial")),
                QuilometroParser.parse(rs.getString("trecho_final")),
                null,
                null,
                extensaoDeServico(rs),
                null,
                null,
                null,
                texto(rs.getString("status_validacao"))
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

        for (SegmentoTrecho segmento : segmentos) {
            if (segmento.origem() == Origem.PROGRAMACAO) {
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
                segmentos.size(), rdos.size(), extensao, area, massa, primeira, ultima
        );
    }

    private LocalDateTime ultimaAtualizacao(String obraId) {
        return jdbcTemplate.queryForObject(
                ULTIMA_ATUALIZACAO,
                (rs, rowNum) -> {
                    java.sql.Timestamp momento = rs.getTimestamp(1);
                    return momento == null ? null : momento.toLocalDateTime();
                },
                obraId, obraId, obraId
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
}
