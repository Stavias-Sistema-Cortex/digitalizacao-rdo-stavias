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

    @Test
    void authenticationParserAcceptsOnlyCanonicalPersistedRoles() {
        assertThat(PapelAcesso.fromPersistedExact("ALFA"))
                .contains(PapelAcesso.ALFA);
        assertThat(PapelAcesso.fromPersistedExact("BETA"))
                .contains(PapelAcesso.BETA);
        assertThat(PapelAcesso.fromPersistedExact("alfa")).isEmpty();
        assertThat(PapelAcesso.fromPersistedExact(" GAMA ")).isEmpty();
        assertThat(PapelAcesso.fromPersistedExact(null)).isEmpty();
    }
}
