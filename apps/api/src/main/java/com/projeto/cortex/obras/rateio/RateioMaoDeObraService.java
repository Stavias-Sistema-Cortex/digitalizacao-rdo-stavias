package com.projeto.cortex.obras.rateio;

import com.projeto.cortex.auth.AutorizacaoDeObra;
import com.projeto.cortex.auth.CurrentUserService;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Entrega os apontamentos de mão de obra de um período, para o rateio.
 *
 * <p>Uma consulta só, do RDO para a mão de obra, em {@code LEFT JOIN}: o RDO
 * sem ninguém apontado precisa aparecer na resposta com a lista vazia, porque
 * "ninguém trabalhou neste dia" e "o conteúdo deste RDO ainda não chegou" são
 * coisas diferentes, e só quem lê a resposta consegue distingui-las se as duas
 * existirem. Um {@code INNER JOIN} apagaria a primeira.
 *
 * <p>O escopo é o mesmo do resto do produto: Alfa alcança todas as obras; quem
 * não é alcança as suas, pelo vínculo ou pela equipe alocada. A regra não é
 * reescrita aqui — vem de {@link AutorizacaoDeObra}, que existe justamente para
 * não haver três versões dela.
 */
@Service
public class RateioMaoDeObraService {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(RateioMaoDeObraService.class);

    /**
     * Quantas linhas de apontamento uma resposta carrega.
     *
     * <p>Um mês de uma carteira grande — duzentas pessoas em cinco frentes,
     * trinta dias — dá algo em torno de seis mil linhas. O teto está uma ordem
     * de grandeza acima disso para caber o ano inteiro de quem pedir, e existe
     * porque a resposta viaja para um celular em campo. Encostar nele não
     * corta a resposta em silêncio: devolve {@code completo = false}, e a tela
     * diz que o retrato está parcial.
     */
    static final int TETO_DE_APONTAMENTOS = 120_000;

    /**
     * O maior período que uma consulta aceita.
     *
     * <p>Ano e um mês: cobre o exercício fechado com folga. Acima disso é quase
     * sempre data digitada errada — ano trocado, campo vazio virando 1970 — e
     * uma varredura da tabela inteira não deve nascer de um engano de digitação.
     */
    static final int MAXIMO_DE_DIAS = 400;

    private static final String CONSULTA = """
            SELECT
                r.id                AS rdo_id,
                r.obra_id           AS obra_id,
                o.nome              AS obra_nome,
                r.data_rdo          AS data_rdo,
                r.numero_rdo        AS numero_rdo,
                r.encarregado_obra  AS encarregado_obra,
                r.apontador_rdo     AS apontador_rdo,
                m.colaborador_id    AS colaborador_id,
                m.nome_colaborador  AS nome_colaborador,
                m.cargo             AS cargo
            FROM rdo r
            JOIN obra o ON o.id = r.obra_id
            LEFT JOIN rdo_mao_obra m ON m.rdo_id = r.id
            WHERE r.cancelado_em IS NULL
              AND r.status <> 'CANCELADA'
              AND r.data_rdo BETWEEN ? AND ?
            """;

    private static final String ORDENACAO = """
            ORDER BY r.data_rdo, r.id, m.id
            LIMIT %d
            """.formatted(TETO_DE_APONTAMENTOS + 1);

    private final JdbcTemplate jdbcTemplate;
    private final CurrentUserService currentUserService;

    public RateioMaoDeObraService(
            JdbcTemplate jdbcTemplate,
            CurrentUserService currentUserService
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.currentUserService = currentUserService;
    }

    public RateioMaoDeObraResponse apontamentosDoPeriodo(
            LocalDate inicio,
            LocalDate fim
    ) {
        validarPeriodo(inicio, fim);

        String userId = currentUserService.requireUserId();
        boolean global = currentUserService.isAlfa(userId);

        String sql = global
                ? CONSULTA + ORDENACAO
                : CONSULTA
                        + " AND "
                        + AutorizacaoDeObra.existeCaminhoParaObra("r.obra_id")
                        + ORDENACAO;

        Object[] parametros = global
                ? new Object[] {inicio, fim}
                : new Object[] {inicio, fim, userId, userId};

        // LinkedHashMap para o RDO sair na ordem em que a consulta o entregou:
        // a resposta fica estável entre chamadas, o que torna diferenças entre
        // dois retratos legíveis a olho.
        Map<String, ConstrucaoDeRdo> porRdo = new LinkedHashMap<>();
        int[] linhas = {0};

        jdbcTemplate.query(sql, resultado -> {
            linhas[0] += 1;
            if (linhas[0] > TETO_DE_APONTAMENTOS) return;

            String rdoId = resultado.getString("rdo_id");
            ConstrucaoDeRdo construcao = porRdo.computeIfAbsent(
                    rdoId,
                    ignorado -> {
                        try {
                            return new ConstrucaoDeRdo(
                                    rdoId,
                                    resultado.getString("obra_id"),
                                    resultado.getString("obra_nome"),
                                    resultado.getDate("data_rdo") == null
                                            ? null
                                            : resultado.getDate("data_rdo")
                                                    .toLocalDate(),
                                    resultado.getString("numero_rdo"),
                                    resultado.getString("encarregado_obra"),
                                    resultado.getString("apontador_rdo")
                            );
                        } catch (java.sql.SQLException erro) {
                            throw new IllegalStateException(
                                    "Falha ao ler o RDO do rateio.",
                                    erro
                            );
                        }
                    }
            );

            String colaboradorId = resultado.getString("colaborador_id");
            String nome = resultado.getString("nome_colaborador");
            // O LEFT JOIN devolve a linha do RDO sem ninguém com as colunas da
            // mão de obra nulas; ela existe para o RDO entrar na resposta, e
            // não vira apontamento.
            if (colaboradorId == null && (nome == null || nome.isBlank())) {
                return;
            }
            construcao.maoObra.add(
                    new RateioMaoDeObraResponse.MaoDeObraDoRateio(
                            colaboradorId,
                            nome,
                            resultado.getString("cargo")
                    )
            );
        }, parametros);

        boolean completo = linhas[0] <= TETO_DE_APONTAMENTOS;
        if (!completo) {
            LOGGER.warn(
                    "O rateio de {} a {} encostou no teto de {} apontamentos;"
                            + " a resposta saiu parcial.",
                    inicio,
                    fim,
                    TETO_DE_APONTAMENTOS
            );
        }

        List<RateioMaoDeObraResponse.RdoDoRateio> rdos =
                new ArrayList<>(porRdo.size());
        for (ConstrucaoDeRdo construcao : porRdo.values()) {
            rdos.add(construcao.concluir());
        }

        return new RateioMaoDeObraResponse(inicio, fim, rdos, completo);
    }

    private void validarPeriodo(LocalDate inicio, LocalDate fim) {
        if (inicio == null || fim == null) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Informe o início e o fim do período."
            );
        }
        if (fim.isBefore(inicio)) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "O fim do período não pode ser anterior ao início."
            );
        }
        if (ChronoUnit.DAYS.between(inicio, fim) + 1 > MAXIMO_DE_DIAS) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "O período pedido é maior que "
                            + MAXIMO_DE_DIAS
                            + " dias."
            );
        }
    }

    /** Acumula as pessoas de um RDO enquanto as linhas do join chegam. */
    private record ConstrucaoDeRdo(
            String id,
            String obraId,
            String obraNome,
            LocalDate dataRdo,
            String numeroRdo,
            String encarregadoObra,
            String apontadorRdo,
            List<RateioMaoDeObraResponse.MaoDeObraDoRateio> maoObra
    ) {
        ConstrucaoDeRdo(
                String id,
                String obraId,
                String obraNome,
                LocalDate dataRdo,
                String numeroRdo,
                String encarregadoObra,
                String apontadorRdo
        ) {
            this(
                    id,
                    obraId,
                    obraNome,
                    dataRdo,
                    numeroRdo,
                    encarregadoObra,
                    apontadorRdo,
                    new ArrayList<>()
            );
        }

        RateioMaoDeObraResponse.RdoDoRateio concluir() {
            return new RateioMaoDeObraResponse.RdoDoRateio(
                    id,
                    obraId,
                    obraNome,
                    dataRdo,
                    numeroRdo,
                    encarregadoObra,
                    apontadorRdo,
                    List.copyOf(maoObra)
            );
        }
    }
}
