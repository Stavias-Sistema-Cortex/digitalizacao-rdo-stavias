package com.projeto.cortex.obras.usinados;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Totais de usinagem e aplicação de uma obra, material a material.
 *
 * <p>Cada linha soma o que os RDOs não cancelados da obra declararam sobre um
 * material usinado: o previsto, o usinado, o aplicado (o feito), a sobra (o
 * desperdiçado) — na unidade em que o apontador lançou (T, M³, M²…). Nenhum
 * valor ausente vira zero: material sem previsão declarada fica sem previsão,
 * e a diferença "não aplicado" só existe quando há previsão para comparar.</p>
 *
 * <p>O dinheiro segue a mesma honestidade. O preço vem do catálogo de serviços
 * da obra quando existe exatamente um preço vigente com o mesmo nome e a mesma
 * unidade do material; sem esse casamento, a linha diz por quê
 * ({@code precoMotivo}) em vez de inventar um número. E preço é dado
 * financeiro: quem não tem {@code FINANCEIRO_VISUALIZAR} na obra recebe as
 * quantidades com {@code precosVisiveis=false} e nenhum campo monetário.</p>
 */
public record ObraUsinadosResponse(
        String obraId,
        String obraNome,
        boolean precosVisiveis,
        List<MaterialUsinado> materiais,
        TotaisFinanceiros totais
) {

    /** Por que uma linha ficou sem preço, quando o financeiro é visível. */
    public static final String SEM_PRECO = "SEM_PRECO";
    public static final String PRECO_AMBIGUO = "PRECO_AMBIGUO";

    public record MaterialUsinado(
            String material,
            String unidade,
            BigDecimal quantidadePrevista,
            BigDecimal quantidadeUsinada,
            BigDecimal quantidadeAplicada,
            BigDecimal quantidadeSobra,
            /** Previsto menos aplicado, nunca negativo; nulo sem previsão. */
            BigDecimal quantidadeNaoAplicada,
            int totalRdos,
            LocalDate primeiraData,
            LocalDate ultimaData,
            BigDecimal precoUnitario,
            String precoMotivo,
            BigDecimal valorAplicado,
            BigDecimal valorDesperdicado
    ) {
    }

    /** Somas apenas das linhas com preço casado; nulo sem financeiro visível. */
    public record TotaisFinanceiros(
            BigDecimal valorAplicado,
            BigDecimal valorDesperdicado,
            int materiaisSemPreco
    ) {
    }
}
