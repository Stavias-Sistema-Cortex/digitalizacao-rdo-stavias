package com.projeto.cortex.auth;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PapelAcessoTest {

    @Test
    void fromNullableReconheceValoresValidos() {
        assertThat(PapelAcesso.fromNullable("ALFA")).isEqualTo(PapelAcesso.ALFA);
        assertThat(PapelAcesso.fromNullable("beta")).isEqualTo(PapelAcesso.BETA);
        assertThat(PapelAcesso.fromNullable("  Alfa  ")).isEqualTo(PapelAcesso.ALFA);
    }

    @Test
    void fromNullableResolveAusenteOuDesconhecidoComoBeta() {
        assertThat(PapelAcesso.fromNullable(null)).isEqualTo(PapelAcesso.BETA);
        assertThat(PapelAcesso.fromNullable("")).isEqualTo(PapelAcesso.BETA);
        assertThat(PapelAcesso.fromNullable("GAMA")).isEqualTo(PapelAcesso.BETA);
    }
}
