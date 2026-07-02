package com.projeto.cortex.auth;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class JwtServiceTest {

    private static final String SECRET = "segredo-de-teste-1234567890";

    @Test
    void tokenRecemGeradoEhValido() {
        JwtService service = new JwtService(SECRET, 3600);
        assertTrue(service.tokenValido(service.gerarToken("colab-1")));
    }

    @Test
    void tokenValidoExpoeSubjectAutenticado() {
        JwtService service = new JwtService(SECRET, 3600);
        assertEquals(
                "colab-1",
                service.subject(service.gerarToken("colab-1")).orElseThrow()
        );
    }

    @Test
    void tokenAdulteradoEhInvalido() {
        JwtService service = new JwtService(SECRET, 3600);
        String token = service.gerarToken("colab-1");
        assertFalse(service.tokenValido(token + "x"));
        assertFalse(service.tokenValido(token.substring(0, token.length() - 2)));
    }

    @Test
    void tokenExpiradoEhInvalido() {
        JwtService service = new JwtService(SECRET, -10);
        assertFalse(service.tokenValido(service.gerarToken("colab-1")));
    }

    @Test
    void segredoDiferenteRejeitaToken() {
        String token = new JwtService("segredo-A-0000000000", 3600)
                .gerarToken("colab-1");
        assertFalse(
                new JwtService("segredo-B-0000000000", 3600).tokenValido(token)
        );
    }

    @Test
    void nuloVazioOuMalformadoEhInvalido() {
        JwtService service = new JwtService(SECRET, 3600);
        assertFalse(service.tokenValido(null));
        assertFalse(service.tokenValido(""));
        assertFalse(service.tokenValido("a.b"));
    }
}
