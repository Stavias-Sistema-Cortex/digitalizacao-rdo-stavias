package com.projeto.cortex.obras;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ObraResponseTest {

    @Test
    void fromCopiaCamposIncluindoCoordenadasNulas() {
        Obra obra = Obra.criar(
                "CT-2026-001", null, null, "Obra Teste", "DNIT",
                null, "Campo Grande", "MS", "BR-262",
                "ATIVA", "MANUAL", null, "Obs geral"
        );

        ObraResponse response = ObraResponse.from(obra);

        assertEquals("Obra Teste", response.nome());
        assertEquals("BR-262", response.rodovia());
        assertNull(response.latitude());
        assertNull(response.longitude());
    }
}
