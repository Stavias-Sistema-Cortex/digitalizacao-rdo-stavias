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
 * correção. Unidade e moeda também não vêm aqui: elas fazem parte do endereço da
 * versão, e trocá-las é apontar para outro preço.
 */
public record UpdateServicePriceCommand(
        String clientMutationId,
        BigDecimal unitPrice,
        BigDecimal contractedQuantity,
        LocalDate validFrom,
        LocalDate validTo,
        String source
) {
}
