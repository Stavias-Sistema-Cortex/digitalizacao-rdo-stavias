package com.projeto.cortex.rdos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.projeto.cortex.auth.CurrentUserService;
import com.projeto.cortex.storage.StoredObjectDownload;
import com.projeto.cortex.storage.StoredObjectRecord;
import com.projeto.cortex.storage.StoredObjectRepository;
import com.projeto.cortex.storage.StoredObjectService;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.web.server.ResponseStatusException;

/**
 * O vínculo entre a ficha do anexo e o binário cobra as mesmas réguas do anexo
 * de mensagem: objeto disponível, dono igual a quem amarra, hash conferido e
 * obra do objeto igual à obra do RDO. Sem elas, qualquer pessoa com acesso ao
 * RDO penduraria nele um objeto alheio.
 *
 * E o vínculo é idempotente de propósito: o aplicativo reenvia quando a
 * resposta se perde, e amarrar de novo o mesmo objeto não é erro — trocar de
 * objeto é.
 */
class RdoAttachmentObjectServiceTest {

    private static final String RDO_ID =
            "00000000-0000-4000-8000-0000000000d1";
    private static final String ANEXO_ID =
            "00000000-0000-4000-8000-0000000000d2";
    private static final String OBJETO_ID =
            "00000000-0000-4000-8000-0000000000d3";
    private static final String OBRA_ID =
            "00000000-0000-4000-8000-0000000000d4";
    private static final String DONO_ID =
            "00000000-0000-4000-8000-0000000000d5";
    private static final String SHA256 = "a".repeat(64);

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final StoredObjectRepository repository =
            mock(StoredObjectRepository.class);
    private final StoredObjectService storedObjects =
            mock(StoredObjectService.class);
    private final CurrentUserService currentUser =
            mock(CurrentUserService.class);
    private final RdoAttachmentObjectService service =
            new RdoAttachmentObjectService(
                    jdbc, repository, storedObjects, currentUser);

    private void anexoNoBanco(String vinculado) {
        when(jdbc.query(
                anyString(),
                any(ResultSetExtractor.class),
                any(Object[].class)
        )).thenReturn(Map.of(
                "obraId", OBRA_ID,
                "vinculado", String.valueOf(vinculado)
        ));
    }

    private StoredObjectRecord objeto(String owner, String obraId, String sha) {
        return new StoredObjectRecord(
                OBJETO_ID,
                owner,
                obraId,
                "dedupe-1",
                sha,
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
    void amarraOObjetoEDeixaAFichaSincronizada() {
        anexoNoBanco(null);
        when(repository.findAvailableById(OBJETO_ID))
                .thenReturn(Optional.of(objeto(DONO_ID, OBRA_ID, SHA256)));
        when(currentUser.requireUserId()).thenReturn(DONO_ID);

        service.vincularObjeto(RDO_ID, ANEXO_ID, OBJETO_ID, SHA256);

        verify(jdbc).update(
                contains("SET stored_object_id"),
                eq(OBJETO_ID),
                eq(ANEXO_ID),
                eq(RDO_ID)
        );
    }

    /**
     * O carimbo do RDO é o que os outros aparelhos comparam para decidir
     * rebuscar o conteúdo. Sem o avanço, quem puxou o RDO antes de a foto
     * subir nunca rebuscaria — a foto existiria no servidor e não chegaria a
     * ninguém.
     */
    @Test
    void oVinculoAvancaOCarimboDoRdoParaOsOutrosAparelhosRebuscarem() {
        anexoNoBanco(null);
        when(repository.findAvailableById(OBJETO_ID))
                .thenReturn(Optional.of(objeto(DONO_ID, OBRA_ID, SHA256)));
        when(currentUser.requireUserId()).thenReturn(DONO_ID);

        service.vincularObjeto(RDO_ID, ANEXO_ID, OBJETO_ID, SHA256);

        verify(jdbc).update(
                contains("UPDATE rdo\n"),
                eq(RDO_ID)
        );
    }

    @Test
    void amarrarDeNovoOMesmoObjetoNaoEErro() {
        anexoNoBanco(OBJETO_ID);

        assertThatCode(() ->
                service.vincularObjeto(RDO_ID, ANEXO_ID, OBJETO_ID, SHA256)
        ).doesNotThrowAnyException();

        verify(repository, never()).findAvailableById(anyString());
        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }

    @Test
    void trocarDeObjetoEConflito() {
        anexoNoBanco("outro-objeto");

        assertThatThrownBy(() ->
                service.vincularObjeto(RDO_ID, ANEXO_ID, OBJETO_ID, SHA256)
        ).satisfies(error -> assertThat(
                ((ResponseStatusException) error).getStatusCode()
        ).isEqualTo(HttpStatus.CONFLICT));
    }

    @Test
    void anexoInexistenteOuRemovidoE404() {
        when(jdbc.query(
                anyString(),
                any(ResultSetExtractor.class),
                any(Object[].class)
        )).thenReturn(null);

        assertThatThrownBy(() ->
                service.vincularObjeto(RDO_ID, ANEXO_ID, OBJETO_ID, SHA256)
        ).satisfies(error -> assertThat(
                ((ResponseStatusException) error).getStatusCode()
        ).isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    void objetoIndisponivelE404() {
        anexoNoBanco(null);
        when(repository.findAvailableById(OBJETO_ID))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() ->
                service.vincularObjeto(RDO_ID, ANEXO_ID, OBJETO_ID, SHA256)
        ).satisfies(error -> assertThat(
                ((ResponseStatusException) error).getStatusCode()
        ).isEqualTo(HttpStatus.NOT_FOUND));
    }

    /** Amarrar upload alheio penduraria no RDO um objeto de outra pessoa. */
    @Test
    void somenteODonoDoUploadAmarra() {
        anexoNoBanco(null);
        when(repository.findAvailableById(OBJETO_ID))
                .thenReturn(Optional.of(objeto(DONO_ID, OBRA_ID, SHA256)));
        when(currentUser.requireUserId()).thenReturn("outra-pessoa");

        assertThatThrownBy(() ->
                service.vincularObjeto(RDO_ID, ANEXO_ID, OBJETO_ID, SHA256)
        ).satisfies(error -> assertThat(
                ((ResponseStatusException) error).getStatusCode()
        ).isEqualTo(HttpStatus.FORBIDDEN));
        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }

    @Test
    void hashDivergenteEConflito() {
        anexoNoBanco(null);
        when(repository.findAvailableById(OBJETO_ID))
                .thenReturn(Optional.of(objeto(DONO_ID, OBRA_ID, SHA256)));
        when(currentUser.requireUserId()).thenReturn(DONO_ID);

        assertThatThrownBy(() ->
                service.vincularObjeto(RDO_ID, ANEXO_ID, OBJETO_ID, "b".repeat(64))
        ).satisfies(error -> assertThat(
                ((ResponseStatusException) error).getStatusCode()
        ).isEqualTo(HttpStatus.CONFLICT));
    }

    @Test
    void objetoDeOutraObraNaoEntra() {
        anexoNoBanco(null);
        when(repository.findAvailableById(OBJETO_ID))
                .thenReturn(Optional.of(objeto(DONO_ID, "outra-obra", SHA256)));
        when(currentUser.requireUserId()).thenReturn(DONO_ID);

        assertThatThrownBy(() ->
                service.vincularObjeto(RDO_ID, ANEXO_ID, OBJETO_ID, SHA256)
        ).satisfies(error -> assertThat(
                ((ResponseStatusException) error).getStatusCode()
        ).isEqualTo(HttpStatus.BAD_REQUEST));
        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }

    /**
     * "Ainda não subiu" e "não existe" pedem reações diferentes de quem lê —
     * o anexo sem vínculo responde 404 com a frase própria.
     */
    @Test
    void anexoSemBinarioNoServidorE404ComFrasePropria() {
        when(jdbc.query(
                anyString(),
                any(ResultSetExtractor.class),
                any(Object[].class)
        )).thenReturn(null);

        assertThatThrownBy(() -> service.conteudoDoAnexo(RDO_ID, ANEXO_ID))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("ainda não chegou ao servidor");
    }

    @Test
    void anexoComVinculoDelegaODownloadAutorizado() {
        when(jdbc.query(
                anyString(),
                any(ResultSetExtractor.class),
                any(Object[].class)
        )).thenReturn(OBJETO_ID);
        StoredObjectDownload download = mock(StoredObjectDownload.class);
        when(storedObjects.downloadAuthorized(eq(OBJETO_ID), any()))
                .thenReturn(download);

        assertThat(service.conteudoDoAnexo(RDO_ID, ANEXO_ID))
                .isSameAs(download);
    }
}
