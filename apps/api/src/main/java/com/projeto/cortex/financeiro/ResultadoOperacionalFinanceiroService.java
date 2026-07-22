package com.projeto.cortex.financeiro;

import com.projeto.cortex.financeiro.core.FinanceValidation;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class ResultadoOperacionalFinanceiroService {
    private final JdbcTemplate jdbc;
    private final PrevisaoFinanceiraService previsao;

    public ResultadoOperacionalFinanceiroService(
            JdbcTemplate jdbc, PrevisaoFinanceiraService previsao
    ) {
        this.jdbc = jdbc;
        this.previsao = previsao;
    }

    public ResultadoOperacionalFinanceiroResponse buscar(
            String obraId, LocalDate de, LocalDate ate
    ) {
        String obra = FinanceValidation.uuid(obraId, "obraId");
        if (de != null && ate != null && ate.isBefore(de)) {
            throw FinanceValidation.badRequest("Período inválido.");
        }
        Filtro filtro = new Filtro(obra, de, ate);
        Totais totais = jdbc.queryForObject(SQL_TOTAIS, (rs, row) -> new Totais(
                zero(rs.getBigDecimal("producao")), zero(rs.getBigDecimal("receita")),
                zero(rs.getBigDecimal("custo")), zero(rs.getBigDecimal("medida")),
                zero(rs.getBigDecimal("aprovada")), zero(rs.getBigDecimal("faturada")),
                zero(rs.getBigDecimal("recebida"))
        ), filtro.args().toArray());
        List<ResultadoOperacionalFinanceiroResponse.Servico> servicos = jdbc.query(
                SQL_SERVICOS, (rs, row) -> {
                    BigDecimal receita = rs.getBigDecimal("receita");
                    BigDecimal custo = zero(rs.getBigDecimal("custo"));
                    BigDecimal margem = receita == null ? null : receita.subtract(custo);
                    return new ResultadoOperacionalFinanceiroResponse.Servico(
                            rs.getString("nome"), rs.getString("unidade"),
                            zero(rs.getBigDecimal("quantidade")), custo, receita, margem,
                            receita == null || receita.signum() == 0 ? null
                                    : margem.divide(receita, 4, RoundingMode.HALF_UP),
                            rs.getLong("rdos")
                    );
                }, filtro.args().toArray());
        BigDecimal margem = totais.receita.subtract(totais.custo);
        return new ResultadoOperacionalFinanceiroResponse(
                obra, de, ate, totais.producao, totais.receita, totais.custo, margem,
                totais.receita.signum() == 0 ? null
                        : margem.divide(totais.receita, 4, RoundingMode.HALF_UP),
                totais.medida, totais.aprovada, totais.faturada, totais.recebida,
                servicos, previsao.buscarAtual(obra)
        );
    }

    private record Totais(BigDecimal producao, BigDecimal receita, BigDecimal custo,
                          BigDecimal medida, BigDecimal aprovada, BigDecimal faturada,
                          BigDecimal recebida) { }
    private record Filtro(String obraId, LocalDate de, LocalDate ate) {
        List<Object> args() {
            List<Object> args = new ArrayList<>(List.of(obraId));
            args.add(de);
            args.add(de);
            args.add(ate);
            args.add(ate);
            return args;
        }
    }
    private static BigDecimal zero(BigDecimal value) { return value == null ? BigDecimal.ZERO : value; }
    private static final String BASE = " FROM execucao_servico_rdo WHERE obra_id = ? AND cancelada = 0 AND status_validacao IN ('REGISTRADA', 'VALIDADA') AND producao_rejeitada = 0";
    private static final String SQL_TOTAIS = "SELECT COALESCE(SUM(quantidade_executada), 0) producao, COALESCE(SUM(receita_operacional_estimativa), 0) receita, COALESCE(SUM(custo_realizado), 0) custo, COALESCE(SUM(CASE WHEN estado_receita IN ('RECEITA_MEDIDA','RECEITA_APROVADA','RECEITA_FATURADA','RECEITA_RECEBIDA') THEN receita_operacional_estimativa ELSE 0 END), 0) medida, COALESCE(SUM(CASE WHEN estado_receita IN ('RECEITA_APROVADA','RECEITA_FATURADA','RECEITA_RECEBIDA') THEN receita_operacional_estimativa ELSE 0 END), 0) aprovada, COALESCE(SUM(CASE WHEN estado_receita IN ('RECEITA_FATURADA','RECEITA_RECEBIDA') THEN receita_operacional_estimativa ELSE 0 END), 0) faturada, COALESCE(SUM(CASE WHEN estado_receita = 'RECEITA_RECEBIDA' THEN receita_operacional_estimativa ELSE 0 END), 0) recebida" + BASE + " AND (? IS NULL OR data_execucao >= ?) AND (? IS NULL OR data_execucao <= ?)";
    private static final String SQL_SERVICOS = "SELECT servico_nome nome, unidade_medida unidade, SUM(quantidade_executada) quantidade, SUM(custo_realizado) custo, SUM(receita_operacional_estimativa) receita, COUNT(DISTINCT rdo_id) rdos" + BASE + " AND (? IS NULL OR data_execucao >= ?) AND (? IS NULL OR data_execucao <= ?) GROUP BY servico_nome, unidade_medida ORDER BY servico_nome, unidade_medida";
}

