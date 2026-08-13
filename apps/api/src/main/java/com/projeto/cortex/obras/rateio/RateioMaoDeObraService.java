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
                r.apontador_colaborador_id AS apontador_colaborador_id,
                ap.funcao           AS apontador_funcao,
                r.preenchido_por    AS preenchido_por,
                m.colaborador_id    AS colaborador_id,
                m.nome_colaborador  AS nome_colaborador,
                m.cargo             AS cargo,
                mc.funcao           AS colaborador_funcao
            FROM rdo r
            JOIN obra o ON o.id = r.obra_id
            LEFT JOIN colaborador ap ON ap.id = r.apontador_colaborador_id
            LEFT JOIN rdo_mao_obra m ON m.rdo_id = r.id
            LEFT JOIN colaborador mc ON mc.id = m.colaborador_id
            WHERE r.cancelado_em IS NULL
              AND r.status <> 'CANCELADA'
              AND r.data_rdo BETWEEN ? AND ?
            """;

    private static final String ORDENACAO = """
            ORDER BY r.data_rdo, r.id, m.id
            LIMIT %d
            """.formatted(TETO_DE_APONTAMENTOS + 1);

    /**
     * O cadastro que sabe o ofício de cada um.
     *
     * <p>Vem inteiro, e de propósito: a alternativa seria comparar nomes dentro
     * do SQL, o que exigiria repetir ali a mesma normalização que o produto já
     * faz em dois lugares — e uma terceira cópia da regra de "mesmo nome" é
     * exatamente o tipo de divergência que faz a mesma pessoa aparecer duas
     * vezes. A tabela tem o tamanho do quadro da empresa, e a consulta só sai
     * quando há assinatura para resolver.
     */
    private static final String CONSULTA_OFICIOS = """
            SELECT nome, funcao
            FROM colaborador
            WHERE funcao IS NOT NULL AND btrim(funcao) <> ''
            """;

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
                                    resultado.getString("apontador_rdo"),
                                    resultado.getString(
                                            "apontador_colaborador_id"
                                    ),
                                    resultado.getString("apontador_funcao"),
                                    resultado.getString("preenchido_por")
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
                            resultado.getString("cargo"),
                            resultado.getString("colaborador_funcao")
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

        Map<String, String> oficios = oficiosPorNome(porRdo.values());

        List<RateioMaoDeObraResponse.RdoDoRateio> rdos =
                new ArrayList<>(porRdo.size());
        for (ConstrucaoDeRdo construcao : porRdo.values()) {
            rdos.add(construcao.concluir(oficios));
        }

        return new RateioMaoDeObraResponse(inicio, fim, rdos, completo);
    }

    /**
     * O nome como duas grafias do mesmo nome ficam iguais.
     *
     * <p>Sem acento, sem caixa e com um espaço só entre as palavras: é assim
     * que "José da Silva" digitado em campo alcança "JOSE DA  SILVA" vindo da
     * planilha. A mesma regra que o aparelho usa para não contar a pessoa duas
     * vezes — se as duas divergirem, o rateio soma um dia a quem trabalhou um.
     */
    static String nomeComparavel(String nome) {
        if (nome == null) return "";
        return java.text.Normalizer
                .normalize(nome, java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .toUpperCase(java.util.Locale.ROOT)
                .trim()
                .replaceAll("\\s+", " ");
    }

    /**
     * O ofício de cada assinatura, quando o nome aponta para uma pessoa só.
     *
     * <p>Assinar o RDO é texto livre: não há identificador para ligar quem
     * preencheu ao seu cadastro, e o ofício que o Academy guarda ficaria
     * inalcançável justamente para quem mais aparece no rateio. O nome resolve
     * isso na esmagadora maioria dos casos — ele nasce da sessão de quem criou
     * o documento, logo é o nome do cadastro.
     *
     * <p>O que não se resolve pelo nome é o homônimo, e aí o silêncio é a
     * resposta certa: dois cadastros com o mesmo nome e ofícios diferentes não
     * entregam ofício nenhum. Errar o ofício de alguém é pior do que mostrar o
     * rótulo do papel, que é sempre verdadeiro.
     */
    private Map<String, String> oficiosPorNome(
            Iterable<ConstrucaoDeRdo> construcoes
    ) {
        java.util.Set<String> procurados = new java.util.HashSet<>();
        for (ConstrucaoDeRdo construcao : construcoes) {
            String preenchidoPor = nomeComparavel(construcao.preenchidoPor());
            if (!preenchidoPor.isEmpty()) procurados.add(preenchidoPor);
            // O apontador escolhido da lista já chegou com o ofício pelo
            // identificador; só o digitado precisa ser achado pelo nome.
            if (construcao.apontadorFuncao() == null) {
                String apontador = nomeComparavel(construcao.apontadorRdo());
                if (!apontador.isEmpty()) procurados.add(apontador);
            }
            // Quem entrou na mão de obra sem cadastro ligado — somado à mão,
            // ou de um RDO antigo em que o vínculo não foi gravado — também
            // tem ofício, e o nome é a única porta que sobrou para ele.
            for (RateioMaoDeObraResponse.MaoDeObraDoRateio pessoa
                    : construcao.maoObra()) {
                if (pessoa.funcaoCadastro() != null) continue;
                String nome = nomeComparavel(pessoa.nomeColaborador());
                if (!nome.isEmpty()) procurados.add(nome);
            }
        }
        if (procurados.isEmpty()) return Map.of();

        Map<String, String> oficios = new java.util.HashMap<>();
        java.util.Set<String> homonimos = new java.util.HashSet<>();
        jdbcTemplate.query(CONSULTA_OFICIOS, resultado -> {
            String chave = nomeComparavel(resultado.getString("nome"));
            if (chave.isEmpty() || !procurados.contains(chave)) return;
            String funcao = resultado.getString("funcao").trim();
            String jaVisto = oficios.putIfAbsent(chave, funcao);
            if (jaVisto != null && !jaVisto.equals(funcao)) {
                homonimos.add(chave);
            }
        });
        for (String chave : homonimos) {
            oficios.remove(chave);
        }
        return oficios;
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
            String apontadorColaboradorId,
            String apontadorFuncao,
            String preenchidoPor,
            List<RateioMaoDeObraResponse.MaoDeObraDoRateio> maoObra
    ) {
        ConstrucaoDeRdo(
                String id,
                String obraId,
                String obraNome,
                LocalDate dataRdo,
                String numeroRdo,
                String encarregadoObra,
                String apontadorRdo,
                String apontadorColaboradorId,
                String apontadorFuncao,
                String preenchidoPor
        ) {
            this(
                    id,
                    obraId,
                    obraNome,
                    dataRdo,
                    numeroRdo,
                    encarregadoObra,
                    apontadorRdo,
                    apontadorColaboradorId,
                    apontadorFuncao,
                    preenchidoPor,
                    new ArrayList<>()
            );
        }

        RateioMaoDeObraResponse.RdoDoRateio concluir(
                Map<String, String> oficiosPorNome
        ) {
            // O identificador manda sobre o nome: quem foi escolhido da lista
            // já veio ligado ao seu cadastro, e nenhum homônimo desfaz isso.
            String oficioDoApontador = apontadorFuncao != null
                    ? apontadorFuncao
                    : oficiosPorNome.get(nomeComparavel(apontadorRdo));
            List<RateioMaoDeObraResponse.MaoDeObraDoRateio> comOficio =
                    new ArrayList<>(maoObra.size());
            for (RateioMaoDeObraResponse.MaoDeObraDoRateio pessoa : maoObra) {
                comOficio.add(pessoa.funcaoCadastro() != null
                        ? pessoa
                        : pessoa.comFuncaoCadastro(oficiosPorNome.get(
                                nomeComparavel(pessoa.nomeColaborador())))
                );
            }
            return new RateioMaoDeObraResponse.RdoDoRateio(
                    id,
                    obraId,
                    obraNome,
                    dataRdo,
                    numeroRdo,
                    encarregadoObra,
                    apontadorRdo,
                    apontadorColaboradorId,
                    oficioDoApontador,
                    preenchidoPor,
                    oficiosPorNome.get(nomeComparavel(preenchidoPor)),
                    List.copyOf(comOficio)
            );
        }
    }
}
