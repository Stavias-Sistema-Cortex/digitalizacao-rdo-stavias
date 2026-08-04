package com.projeto.cortex.mensagens.domain;

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

import com.projeto.cortex.storage.StoredObjectRecord;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.web.server.ResponseStatusException;

/**
 * Os mesmos bytes tinham duas portas com regras diferentes.
 *
 * Pela rota de mensagens, quem autoriza é a participação na conversa. Pela
 * rota genérica de objetos valia a regra padrão — dono, ou qualquer pessoa com
 * acesso à obra quando o objeto trazia obraId. Como o anexo de uma conversa de
 * obra é gravado com esse obraId, bastava conhecer o id do objeto para ler
 * documento de conversa alheia; e num grupo direto, sem obra, a regra caía em
 * "dono ou administrador", abrindo conversa privada para o papel Alfa.
 *
 * Conversa é onde circula foto de documento, atestado, endereço. Uma segunda
 * porta mais frouxa que a primeira é acesso indevido a dado pessoal, não
 * detalhe de implementação.
 */
class MessagingStoredObjectAccessGuardTest {

    private static final String OBJETO_ID =
            "00000000-0000-4000-8000-0000000000b1";
    private static final String CONVERSA_ID =
            "00000000-0000-4000-8000-0000000000b2";

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final ConversaAccessPolicy accessPolicy =
            mock(ConversaAccessPolicy.class);
    private final MessagingStoredObjectAccessGuard guard =
            new MessagingStoredObjectAccessGuard(jdbc, accessPolicy);

    private StoredObjectRecord objeto() {
        return new StoredObjectRecord(
                OBJETO_ID,
                "dono-1",
                "obra-1",
                "dedupe-1",
                "hash",
                "LOCAL",
                "objects/ab/" + OBJETO_ID,
                "atestado.pdf",
                "application/pdf",
                "application/pdf",
                12L,
                "DISPONIVEL",
                LocalDateTime.parse("2026-08-03T12:00:00")
        );
    }

    @Test
    void assumeOAnexoDeConversaComoTerritorioProprio() {
        when(jdbc.queryForObject(anyString(), eq(Integer.class), any(Object[].class)))
                .thenReturn(1);

        assertThat(guard.protects(objeto())).isTrue();
    }

    @Test
    void naoInterfereEmObjetoQueNaoEAnexoDeConversa() {
        when(jdbc.queryForObject(anyString(), eq(Integer.class), any(Object[].class)))
                .thenReturn(0);

        assertThat(guard.protects(objeto())).isFalse();
    }

    /** Participar da conversa é a única credencial que abre o anexo. */
    @Test
    void exigeParticipacaoNaConversaEmVezDeAcessoAObra() {
        when(jdbc.query(
                anyString(),
                any(ResultSetExtractor.class),
                any(Object[].class)
        )).thenReturn(CONVERSA_ID);

        assertThatCode(() -> guard.authorize(objeto())).doesNotThrowAnyException();
        verify(accessPolicy).requireAccess(CONVERSA_ID);
    }

    /**
     * Apagar a mensagem tem de tirar o arquivo do alcance também. Sem isso, a
     * remoção sumiria com o anexo da tela e o manteria legível para quem
     * soubesse o id do objeto.
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
        verify(accessPolicy, never()).requireAccess(anyString());
    }
}
