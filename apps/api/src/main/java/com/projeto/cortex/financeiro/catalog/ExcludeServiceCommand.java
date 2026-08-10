package com.projeto.cortex.financeiro.catalog;

/**
 * Pedido de exclusão ou restauração de um serviço do catálogo.
 *
 * <p>O identificador do cliente é o que torna a operação repetível: a fila
 * offline reenvia o mesmo pedido quando a rede volta e vacila, e sem ele o
 * segundo envio seria uma segunda exclusão em vez do resultado da primeira.
 */
public record ExcludeServiceCommand(
        String clientMutationId,
        String motivo
) {
}
