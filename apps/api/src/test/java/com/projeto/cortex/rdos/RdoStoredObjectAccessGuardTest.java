package com.projeto.cortex.rdos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.projeto.cortex.auth.CurrentUserService;
import com.projeto.cortex.storage.StoredObjectRecord;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.web.server.ResponseStatusException;

/**
 * A foto do RDO tem duas portas — a rota do RDO e a rota genérica de objetos —
 * e as duas precisam cobrar a mesma regra: participação na obra.
 *
 * É a mesma lição do guard de mensagens. Sem este guard, a rota genérica
 * aplicaria a regra padrão de objetos e a porta mais frouxa viraria a porta de
 * verdade; e o anexo removido continuaria legível para quem soubesse o id.
 */
class RdoStoredObjectAccessGuardTest {

    private static final String OBJETO_ID =
            "00000000-0000-4000-8000-0000000000c1";
    private static final String OBRA_ID =
            "00000000-0000-4000-8000-0000000000c2";

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final CurrentUserService currentUser =
            mock(CurrentUserService.class);
    private final RdoStoredObjectAccessGuard guard =
            new RdoStoredObjectAccessGuard(jdbc, currentUser);

    private StoredObjectRecord objeto() {
        return new StoredObjectRecord(
                OBJETO_ID,
                "dono-1",
                OBRA_ID,
                "dedupe-1",
                "hash",
                "LOCAL",
                "objects/ab/" + OBJETO_ID,
                "frente-de-servico.jpg",
                "image/jpeg",
                "image/jpeg",
                12L,
                "DISPONIVEL",
                LocalDateTime.parse("2026-08-13T12:00:00")
        );
    }

    @Test
    void assumeAFotoDeRdoComoTerritorioProprio() {
        when(jdbc.queryForObject(anyString(), eq(Integer.class), any(Object[].class)))
                .thenReturn(1);

        assertThat(guard.protects(objeto())).isTrue();
    }

    @Test
    void naoInterfereEmObjetoQueNaoEFotoDeRdo() {
        when(jdbc.queryForObject(anyString(), eq(Integer.class), any(Object[].class)))
                .thenReturn(0);

        assertThat(guard.protects(objeto())).isFalse();
    }

    /** Quem participa da obra vê a foto — exatamente o que o campo pediu. */
    @Test
    void exigeParticipacaoNaObraDoAnexo() {
        when(jdbc.query(
                anyString(),
                any(ResultSetExtractor.class),
                any(Object[].class)
        )).thenReturn(OBRA_ID);

        assertThatCode(() -> guard.authorize(objeto())).doesNotThrowAnyException();
        verify(currentUser).requireWorksiteAccess(OBRA_ID);
    }

    /**
     * Apagar a foto do RDO tem de tirá-la das duas portas de uma vez. Sem o
     * filtro de removido_em, a remoção sumiria com a foto da tela e a
     * manteria legível para quem soubesse o id do objeto.
     */
    @Test
    void recusaQuandoOAnexoFoiRemovido() {
        when(jdbc.query(
                anyString(),
                any(ResultSetExtractor.class),
                any(Object[].class)
        )).thenReturn(null);

        assertThatThrownBy(() -> guard.authorize(objeto()))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> assertThat(
                        ((ResponseStatusException) error).getStatusCode()
                ).isEqualTo(HttpStatus.NOT_FOUND));
        verify(currentUser, never()).requireWorksiteAccess(anyString());
    }
}
