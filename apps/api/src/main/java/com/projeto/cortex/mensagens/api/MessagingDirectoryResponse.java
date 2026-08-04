package com.projeto.cortex.mensagens.api;

/**
 * Uma pessoa como destinatário possível, e nada além disso.
 *
 * <p>Deliberadamente menor que {@code ColaboradorResponse}: para endereçar uma
 * mensagem basta saber quem é e o que faz. CPF mascarado, e-mail, grupo e data
 * de atualização são do cadastro administrativo e não têm por que circular só
 * porque alguém quis mandar uma mensagem — abrir o catálogo inteiro para
 * resolver a busca seria dar muito mais do que a tarefa pede.
 */
public record MessagingDirectoryResponse(
        String id,
        String nome,
        String nomePerfil
) {
}
