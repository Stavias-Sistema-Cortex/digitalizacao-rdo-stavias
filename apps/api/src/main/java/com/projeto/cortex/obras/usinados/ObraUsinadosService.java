package com.projeto.cortex.obras.usinados;

import com.projeto.cortex.auth.CurrentUserService;
import com.projeto.cortex.financeiro.access.FinancialAccessService;
import com.projeto.cortex.financeiro.access.FinancialPermission;
import com.projeto.cortex.obras.usinados.ObraUsinadosResponse.MaterialUsinado;
import com.projeto.cortex.obras.usinados.ObraUsinadosResponse.TotaisFinanceiros;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Deriva os totais de usinagem da obra a partir do que já está persistido.
 *
 * <p>Não existe tabela própria: a fonte é {@code rdo_material}, que é onde o
 * RDO declara, por dia, o previsto, o usinado, o aplicado e a sobra de cada
 * material. A agregação junta o histórico inteiro da obra por nome
 * normalizado e unidade — o mesmo material grafado com caixa diferente em
 * meses diferentes é uma linha só, e unidades diferentes permanecem linhas
 * separadas porque somá-las produziria um número sem unidade.</p>
 *
 * <p>O preço reutiliza a regra do motor de receita ({@code exactActivePrice}):
 * vale o preço vigente hoje no catálogo desta obra cujo serviço tem o mesmo
 * nome e a mesma unidade do material — exatamente um. Zero preços é
 * {@code SEM_PRECO}; mais de um é {@code PRECO_AMBIGUO}. Nas duas situações a
 * linha viaja sem valor monetário e com o motivo declarado.</p>
 */
@Service
public class ObraUsinadosService {

    private static final String AGREGADO_POR_MATERIAL = """
            SELECT
                MIN(TRIM(m.material_nome)) AS material_nome,
                UPPER(TRIM(COALESCE(m.unidade, ''))) AS unidade,
                SUM(m.quantidade_prevista) AS prevista,
                SUM(m.quantidade_usinada) AS usinada,
                SUM(m.quantidade_aplicada) AS aplicada,
                SUM(m.quantidade_sobra) AS sobra,
                COUNT(DISTINCT m.rdo_id) AS total_rdos,
                MIN(r.data_rdo) AS primeira_data,
                MAX(r.data_rdo) AS ultima_data
            FROM rdo_material m
            JOIN rdo r ON r.id = m.rdo_id
            WHERE r.obra_id = ?
              AND r.cancelado_em IS NULL
              AND TRIM(COALESCE(m.material_nome, '')) <> ''
            GROUP BY LOWER(TRIM(m.material_nome)),
                     UPPER(TRIM(COALESCE(m.unidade, '')))
            ORDER BY material_nome
            """;

    /**
     * A mesma seleção de vigência de {@code RdoExecutionDecisionService}:
     * preço BRL da obra, vigente na data de referência, considerando
     * cancelamento e supersessão pela função de vigência efetiva.
     */
    private static final String PRECOS_VIGENTES_DO_NOME = """
            SELECT p.valor_unitario
            FROM service_price_version p
            JOIN catalogo_servico s ON s.id = p.service_id
            WHERE p.obra_id = ?
              AND p.moeda = 'BRL'
              AND UPPER(TRIM(p.unidade)) = ?
              AND LOWER(TRIM(s.nome)) = ?
              AND s.status = 'ACTIVE'
              AND p.vigencia_inicio <= ?
              AND (
                  cortex_price_effective_valid_to(p.id) IS NULL
                  OR cortex_price_effective_valid_to(p.id) >= ?
              )
            """;

    private final JdbcTemplate jdbcTemplate;
    private final CurrentUserService currentUserService;
    private final FinancialAccessService financialAccessService;

    public ObraUsinadosService(
            JdbcTemplate jdbcTemplate,
            CurrentUserService currentUserService,
            FinancialAccessService financialAccessService
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.currentUserService = currentUserService;
        this.financialAccessService = financialAccessService;
    }

    @Transactional(readOnly = true)
    public ObraUsinadosResponse buscarUsinados(String obraId) {
        return buscarUsinados(obraId, LocalDate.now());
    }

    @Transactional(readOnly = true)
    public ObraUsinadosResponse buscarUsinados(
            String obraId,
            LocalDate dataReferencia
    ) {
        String userId = currentUserService.requireUserId();
        currentUserService.requireWorksiteAccess(obraId);
        List<String> nomes = jdbcTemplate.queryForList(
                "SELECT nome FROM obra WHERE id = ?",
                String.class,
                obraId
        );
        if (nomes.isEmpty()) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND, "Obra não encontrada."
            );
        }
        String obraNome = nomes.getFirst();

        boolean precosVisiveis = financialAccessService.hasPermission(
                userId,
                obraId,
                FinancialPermission.FINANCEIRO_VISUALIZAR
        );

        List<LinhaAgregada> linhas = jdbcTemplate.query(
                AGREGADO_POR_MATERIAL,
                this::mapLinha,
                obraId
        );

        List<MaterialUsinado> materiais = new ArrayList<>(linhas.size());
        BigDecimal valorAplicadoTotal = BigDecimal.ZERO;
        BigDecimal valorDesperdicadoTotal = BigDecimal.ZERO;
        boolean algumPreco = false;
        int semPreco = 0;

        for (LinhaAgregada linha : linhas) {
            BigDecimal precoUnitario = null;
            String precoMotivo = null;
            BigDecimal valorAplicado = null;
            BigDecimal valorDesperdicado = null;

            if (precosVisiveis) {
                List<BigDecimal> precos = jdbcTemplate.queryForList(
                        PRECOS_VIGENTES_DO_NOME,
                        BigDecimal.class,
                        obraId,
                        linha.unidade(),
                        linha.material().toLowerCase(Locale.ROOT),
                        dataReferencia,
                        dataReferencia
                );
                if (precos.size() == 1) {
                    precoUnitario = precos.getFirst();
                    algumPreco = true;
                    valorAplicado = valor(linha.aplicada(), precoUnitario);
                    valorDesperdicado = valor(linha.sobra(), precoUnitario);
                    valorAplicadoTotal = soma(valorAplicadoTotal, valorAplicado);
                    valorDesperdicadoTotal =
                            soma(valorDesperdicadoTotal, valorDesperdicado);
                } else {
                    semPreco += 1;
                    precoMotivo = precos.isEmpty()
                            ? ObraUsinadosResponse.SEM_PRECO
                            : ObraUsinadosResponse.PRECO_AMBIGUO;
                }
            }

            materiais.add(new MaterialUsinado(
                    linha.material(),
                    linha.unidade().isEmpty() ? null : linha.unidade(),
                    linha.prevista(),
                    linha.usinada(),
                    linha.aplicada(),
                    linha.sobra(),
                    naoAplicada(linha.prevista(), linha.aplicada()),
                    linha.totalRdos(),
                    linha.primeiraData(),
                    linha.ultimaData(),
                    precoUnitario,
                    precoMotivo,
                    valorAplicado,
                    valorDesperdicado
            ));
        }

        // Total zero por não haver preço nenhum e total zero somado de preços
        // reais são informações diferentes; sem nenhum casamento os totais
        // ficam nulos para a tela não afirmar um "R$ 0" que não foi apurado.
        TotaisFinanceiros totais = precosVisiveis
                ? new TotaisFinanceiros(
                        algumPreco ? valorAplicadoTotal : null,
                        algumPreco ? valorDesperdicadoTotal : null,
                        semPreco
                )
                : null;

        return new ObraUsinadosResponse(
                obraId,
                obraNome,
                precosVisiveis,
                List.copyOf(materiais),
                totais
        );
    }

    private LinhaAgregada mapLinha(ResultSet rs, int rowNum)
            throws SQLException {
        return new LinhaAgregada(
                rs.getString("material_nome"),
                rs.getString("unidade"),
                rs.getBigDecimal("prevista"),
                rs.getBigDecimal("usinada"),
                rs.getBigDecimal("aplicada"),
                rs.getBigDecimal("sobra"),
                rs.getInt("total_rdos"),
                localDate(rs, "primeira_data"),
                localDate(rs, "ultima_data")
        );
    }

    /**
     * O que estava previsto e não foi aplicado. Só existe quando alguma
     * previsão foi declarada; aplicado acima do previsto não vira dívida
     * negativa — o excedente já aparece no próprio aplicado.
     */
    private static BigDecimal naoAplicada(
            BigDecimal prevista,
            BigDecimal aplicada
    ) {
        if (prevista == null) {
            return null;
        }
        BigDecimal diferenca = prevista.subtract(
                aplicada == null ? BigDecimal.ZERO : aplicada
        );
        return diferenca.signum() < 0 ? BigDecimal.ZERO : diferenca;
    }

    private static BigDecimal valor(
            BigDecimal quantidade,
            BigDecimal precoUnitario
    ) {
        if (quantidade == null) {
            return null;
        }
        return quantidade.multiply(precoUnitario)
                .setScale(2, RoundingMode.HALF_UP);
    }

    private static BigDecimal soma(BigDecimal acumulado, BigDecimal valor) {
        return valor == null ? acumulado : acumulado.add(valor);
    }

    private static LocalDate localDate(ResultSet rs, String coluna)
            throws SQLException {
        java.sql.Date data = rs.getDate(coluna);
        return data == null ? null : data.toLocalDate();
    }

    private record LinhaAgregada(
            String material,
            String unidade,
            BigDecimal prevista,
            BigDecimal usinada,
            BigDecimal aplicada,
            BigDecimal sobra,
            int totalRdos,
            LocalDate primeiraData,
            LocalDate ultimaData
    ) {
    }
}
