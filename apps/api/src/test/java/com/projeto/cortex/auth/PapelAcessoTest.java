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
    void fromNullableRetornaNuloParaAusenteOuDesconhecido() {
        assertThat(PapelAcesso.fromNullable(null)).isNull();
        assertThat(PapelAcesso.fromNullable("")).isNull();
        assertThat(PapelAcesso.fromNullable("GAMA")).isNull();
    }

    @Test
    void fromPerfilGrupoDerivaAlfaDeTextoAdministrativo() {
        assertThat(PapelAcesso.fromPerfilGrupo("Administrador", null))
                .isEqualTo(PapelAcesso.ALFA);
        assertThat(PapelAcesso.fromPerfilGrupo(null, "ADMIN"))
                .isEqualTo(PapelAcesso.ALFA);
        assertThat(PapelAcesso.fromPerfilGrupo("Administradora Geral", null))
                .isEqualTo(PapelAcesso.ALFA);
    }

    @Test
    void fromPerfilGrupoDerivaBetaParaDemais() {
        assertThat(PapelAcesso.fromPerfilGrupo("Encarregado", "Operacional"))
                .isEqualTo(PapelAcesso.BETA);
        assertThat(PapelAcesso.fromPerfilGrupo(null, null))
                .isEqualTo(PapelAcesso.BETA);
    }
}
