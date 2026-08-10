package com.projeto.cortex.financeiro.catalog;

/**
 * Correção do que foi escrito no cadastro de um serviço.
 *
 * <p>Não é uma versão nova: é dizer que o registro nunca descreveu o que se
 * quis dizer. O identificador do serviço não vem aqui porque ele é o endereço
 * da correção, não o conteúdo dela — mudá-lo apontaria para outro serviço, e os
 * RDOs que já citam este continuariam citando o antigo.
 *
 * <p>O identificador do cliente é o que torna a correção repetível: a fila
 * offline reenvia o mesmo pedido quando a rede vacila, e sem ele o segundo
 * envio seria uma segunda correção sobre uma terceira que veio depois.
 */
public record UpdateServiceCommand(
        String clientMutationId,
        String code,
        String name,
        String description
) {
}
