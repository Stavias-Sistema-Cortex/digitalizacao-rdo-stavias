package com.projeto.cortex.financeiro.catalog;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Correção de um preço já registrado, no lugar, sem criar versão nova.
 *
 * <p>Corrigir e revisar são coisas diferentes, e o catálogo só sabia revisar.
 * Revisão é fato do contrato — o aditivo que mudou o valor a partir de uma data
 * — e continua sendo {@link SupersedeServicePriceCommand}, que preserva as duas
 * versões e a fronteira entre elas. Correção é erro de digitação: o registro
 * nunca descreveu o preço acordado, e guardar as duas versões inventaria uma
 * revisão contratual que não aconteceu.
 *
 * <p>Por isso ela vale enquanto o registro não produziu consequência. Assim que
 * uma execução copia o valor para dentro de si, o preço deixa de ser cadastro e
 * passa a ser a prova do quanto aquela execução vale — e o banco recusa a
 * correção.
 *
 * <p>A unidade vem junto porque ela é o erro mais comum a corrigir: antes de o
 * cadastro aceitar expoente, todo serviço medido em área ou volume entrava como
 * metro linear, que era o que passava. Ela muda sob a mesma guarda do resto, e
 * arrasta consigo o número da versão — que é contado por unidade. A moeda fica
 * de fora: ela é BRL e só.
 */
public record UpdateServicePriceCommand(
        String clientMutationId,
        String unit,
        BigDecimal unitPrice,
        BigDecimal contractedQuantity,
        LocalDate validFrom,
        LocalDate validTo,
        String source
) {
}
