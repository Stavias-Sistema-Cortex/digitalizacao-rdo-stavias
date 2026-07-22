package com.projeto.cortex.financeiro;

import com.projeto.cortex.financeiro.core.FinanceValidation;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class RastreioReceitaService {
    private final JdbcTemplate jdbc;

    public RastreioReceitaService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public RastreioReceitaResponse buscar(
            Set<String> obraIds, String obraId, LocalDate de, LocalDate ate
    ) {
        if (de != null && ate != null && ate.isBefore(de)) {
            throw FinanceValidation.badRequest("Período inválido.");
        }
        List<String> escopo = obraIds.stream().sorted().toList();
        if (obraId != null && !obraId.isBlank()) {
            String worksite = FinanceValidation.uuid(obraId, "obraId");
            if (!obraIds.contains(worksite)) {
                throw FinanceValidation.badRequest("Obra fora do escopo financeiro autorizado.");
            }
            escopo = List.of(worksite);
        }
        if (escopo.isEmpty()) return new RastreioReceitaResponse(de, ate, zeros(), List.of(), List.of());
        Sql sql = new Sql(escopo, de, ate);
        List<Linha> linhas = jdbc.query(sql.query(), (rs, row) -> new Linha(
                rs.getString("obra_id"), rs.getString("obra_nome"),
                rs.getString("servico_nome"), rs.getString("unidade_medida"),
                rs.getString("item_contratual_id"), rs.getString("codigo_item_contratual"),
                totais(rs), rs.getLong("quantidade_rdos"), List.of(rs.getString("rdo_ids").split(",")), rs.getLong("receita_disponivel") > 0
        ), sql.args().toArray());
        Map<String, RastreioReceitaResponse.Totais> porObra = new LinkedHashMap<>();
        Map<String, String> nomesObra = new LinkedHashMap<>();
        Map<String, List<Linha>> porServico = new LinkedHashMap<>();
        for (Linha linha : linhas) {
            porObra.merge(linha.obraId, linha.totais, this::somar);
            nomesObra.put(linha.obraId, linha.obraNome);
            porServico.computeIfAbsent(linha.servico + "\u0000" + linha.unidade + "\u0000" + nullSafe(linha.itemContratualId), ignored -> new ArrayList<>()).add(linha);
        }
        List<RastreioReceitaResponse.Obra> obras = porObra.entrySet().stream()
                .map(entry -> new RastreioReceitaResponse.Obra(entry.getKey(), nomesObra.get(entry.getKey()), entry.getValue())).toList();
        List<RastreioReceitaResponse.TipoServico> tipos = porServico.values().stream().map(grupo -> {
            Linha primeira = grupo.getFirst();
            RastreioReceitaResponse.Totais total = grupo.stream().map(Linha::totais).reduce(zeros(), this::somar);
            return new RastreioReceitaResponse.TipoServico(primeira.servico, primeira.unidade, total,
                    grupo.stream().map(item -> new RastreioReceitaResponse.ContribuicaoObra(item.obraId, item.obraNome, item.itemContratualId, item.codigoItemContratual, item.totais, item.rdos, item.rdoIds, item.receitaDisponivel)).toList(),
                    grupo.stream().mapToLong(Linha::rdos).sum(), grupo.stream().anyMatch(Linha::receitaDisponivel));
        }).toList();
        RastreioReceitaResponse.Totais consolidado = linhas.stream().map(Linha::totais).reduce(zeros(), this::somar);
        return new RastreioReceitaResponse(de, ate, consolidado, obras, tipos);
    }

    private RastreioReceitaResponse.Totais totais(java.sql.ResultSet rs) throws java.sql.SQLException {
        BigDecimal receita = zero(rs.getBigDecimal("receita_estimada"));
        BigDecimal custo = zero(rs.getBigDecimal("custo"));
        return new RastreioReceitaResponse.Totais(zero(rs.getBigDecimal("producao")), custo, receita, receita.subtract(custo),
                zero(rs.getBigDecimal("medida")), zero(rs.getBigDecimal("aprovada")), zero(rs.getBigDecimal("faturada")), zero(rs.getBigDecimal("recebida")));
    }
    private RastreioReceitaResponse.Totais somar(RastreioReceitaResponse.Totais a, RastreioReceitaResponse.Totais b) { return new RastreioReceitaResponse.Totais(a.producao().add(b.producao()), a.custo().add(b.custo()), a.receitaEstimada().add(b.receitaEstimada()), a.margem().add(b.margem()), a.receitaMedida().add(b.receitaMedida()), a.receitaAprovada().add(b.receitaAprovada()), a.receitaFaturada().add(b.receitaFaturada()), a.receitaRecebida().add(b.receitaRecebida())); }
    private RastreioReceitaResponse.Totais zeros() { return new RastreioReceitaResponse.Totais(BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO); }
    private static BigDecimal zero(BigDecimal value) { return value == null ? BigDecimal.ZERO : value; }
    private static String nullSafe(String value) { return value == null ? "" : value; }
    private record Linha(String obraId, String obraNome, String servico, String unidade, String itemContratualId, String codigoItemContratual, RastreioReceitaResponse.Totais totais, long rdos, List<String> rdoIds, boolean receitaDisponivel) { }
    private record Sql(List<String> obras, LocalDate de, LocalDate ate) {
        String query() { String in = String.join(",", obras.stream().map(id -> "?").toList()); return "SELECT e.obra_id, o.nome obra_nome, e.servico_nome, e.unidade_medida, e.item_contratual_id, i.codigo_item codigo_item_contratual, SUM(e.quantidade_executada) producao, SUM(e.custo_realizado) custo, SUM(e.receita_operacional_estimativa) receita_estimada, COUNT(e.receita_operacional_estimativa) receita_disponivel, SUM(CASE WHEN e.estado_receita IN ('RECEITA_MEDIDA','RECEITA_APROVADA','RECEITA_FATURADA','RECEITA_RECEBIDA') THEN e.receita_operacional_estimativa ELSE 0 END) medida, SUM(CASE WHEN e.estado_receita IN ('RECEITA_APROVADA','RECEITA_FATURADA','RECEITA_RECEBIDA') THEN e.receita_operacional_estimativa ELSE 0 END) aprovada, SUM(CASE WHEN e.estado_receita IN ('RECEITA_FATURADA','RECEITA_RECEBIDA') THEN e.receita_operacional_estimativa ELSE 0 END) faturada, SUM(CASE WHEN e.estado_receita = 'RECEITA_RECEBIDA' THEN e.receita_operacional_estimativa ELSE 0 END) recebida, COUNT(DISTINCT e.rdo_id) quantidade_rdos, GROUP_CONCAT(DISTINCT e.rdo_id ORDER BY e.rdo_id SEPARATOR ',') rdo_ids FROM execucao_servico_rdo e JOIN obra o ON o.id = e.obra_id LEFT JOIN item_contratual i ON i.id = e.item_contratual_id WHERE e.obra_id IN (" + in + ") AND e.cancelada = 0 AND e.status_validacao IN ('REGISTRADA','VALIDADA') AND e.producao_rejeitada = 0 AND (? IS NULL OR e.data_execucao >= ?) AND (? IS NULL OR e.data_execucao <= ?) GROUP BY e.obra_id, o.nome, e.servico_nome, e.unidade_medida, e.item_contratual_id, i.codigo_item ORDER BY e.servico_nome, e.unidade_medida, o.nome"; }
        List<Object> args() { List<Object> args = new ArrayList<>(obras); args.add(de); args.add(de); args.add(ate); args.add(ate); return args; }
    }
}

