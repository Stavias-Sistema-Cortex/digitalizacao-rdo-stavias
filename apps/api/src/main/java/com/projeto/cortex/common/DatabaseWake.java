package com.projeto.cortex.common;

/** Aquecimento do banco: abre uma conexão real sem decidir prontidão. */
public interface DatabaseWake {

    /** {@code WARM}, {@code COLD} ou {@code ABSENT}. Nunca lança. */
    String status();
}
