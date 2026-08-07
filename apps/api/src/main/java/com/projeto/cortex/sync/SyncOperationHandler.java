package com.projeto.cortex.sync;

import java.util.Set;

public interface SyncOperationHandler {

    String entityType();

    Set<String> operations();

    boolean requiresBaseVersion(String operation);

    /**
     * Se este handler só sabe ler o envelope canônico v13.
     *
     * <p>O envelope legado carrega a entidade apenas em português
     * ({@code entidadeId}, {@code operacao}, {@code baseVersao}); o canônico
     * carrega o mesmo dado também em inglês ({@code entityId},
     * {@code operation}, {@code baseVersion}). Um handler escrito contra os
     * apelidos ingleses recebe {@code null} em todos eles quando o envelope é
     * legado — e {@code SyncService} não o barrava, porque
     * {@code validarRastroCanonicoParaAplicacao} devolve cedo justamente para
     * o legado. O resultado era NullPointerException dentro do handler, que a
     * captura genérica traduzia em erro interno: 500 para o aparelho, sem
     * nenhuma pista do que estava errado no envelope.</p>
     *
     * <p>Completar os apelidos que faltam não serve como conserto: as garantias
     * que esses handlers assumem — proveniência, dispositivo e usuário
     * conferidos, escopo da obra, apelidos idênticos entre si — vêm de uma
     * validação que o envelope legado nunca atravessou. Fabricá-las aqui
     * entregaria ao handler um envelope que ninguém verificou. A recusa é a
     * resposta honesta, e chega ao cliente como uma recusa nomeada.</p>
     *
     * <p>O padrão é {@code false}: a maioria das entidades ainda aceita fila
     * antiga de aparelho, e quem exige o envelope novo diz isso explicitamente,
     * ao lado do código que depende dele.</p>
     */
    default boolean requiresCanonicalEnvelope() {
        return false;
    }

    AppliedSyncMutation apply(
            SyncPushRequest.MutacaoCliente mutation,
            SyncMutationContext context
    );
}
