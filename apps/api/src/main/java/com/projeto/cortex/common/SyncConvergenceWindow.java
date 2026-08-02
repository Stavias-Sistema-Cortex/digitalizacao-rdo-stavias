package com.projeto.cortex.common;

/**
 * Janela em que uma mutação vinda da sincronização está sendo aplicada.
 *
 * Arquivar uma obra a esconde da operação; não desfaz o que já foi vivido em
 * campo. Um dispositivo que trabalhou offline pode carregar lançamentos de uma
 * obra arquivada antes de ele conseguir sincronizar — e recusá-los prenderia
 * no aparelho a única cópia do que aconteceu. A Lixeira é visibilidade, não
 * cerca de dados.
 *
 * Dentro desta janela, a guarda de operabilidade aceita escrita em obra
 * arquivada; obra inexistente continua recusada. Fora dela — endpoints
 * interativos, agendadores — o arquivamento segue barrando escrita, porque aí
 * não há dado de campo em risco: há alguém tentando operar uma obra encerrada.
 *
 * O escopo é a thread da requisição de push, aberta em um único ponto pelo
 * SyncService e fechada via try-with-resources; aninhamento restaura o estado
 * anterior.
 */
public final class SyncConvergenceWindow implements AutoCloseable {

    private static final ThreadLocal<Boolean> OPEN = new ThreadLocal<>();

    private final Boolean previous;

    private SyncConvergenceWindow() {
        this.previous = OPEN.get();
        OPEN.set(Boolean.TRUE);
    }

    public static SyncConvergenceWindow open() {
        return new SyncConvergenceWindow();
    }

    public static boolean isOpen() {
        return Boolean.TRUE.equals(OPEN.get());
    }

    @Override
    public void close() {
        if (previous == null) {
            OPEN.remove();
        } else {
            OPEN.set(previous);
        }
    }
}
