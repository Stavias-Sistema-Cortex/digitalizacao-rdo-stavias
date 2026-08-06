package com.projeto.cortex.equipes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

import com.projeto.cortex.auth.CurrentUserService;

/**
 * As regras do apagar equipe, provadas sem banco.
 *
 * <p>O que importa: só o Alfa apaga; a conversa se desamarra em vez de sumir;
 * a ordem respeita as chaves estrangeiras; e um DELETE que não pegou
 * exatamente uma linha não publica nada na Memória.
 */
class EquipeDeletionServiceTest {

    private static final String EQUIPE_ID = "equipe-1";

    private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    private final CurrentUserService currentUserService =
            mock(CurrentUserService.class);
    private final EquipeService equipeService = mock(EquipeService.class);
    private final EquipeMemoryPublisher memoryPublisher =
            mock(EquipeMemoryPublisher.class);
    private final EquipeDeletionService service = new EquipeDeletionService(
            jdbcTemplate, currentUserService, equipeService, memoryPublisher
    );

    @BeforeEach
    void alfaComEquipeExistente() {
        when(currentUserService.requireUserId()).thenReturn("alfa-1");
        LocalDateTime agora = LocalDateTime.of(2026, 8, 6, 9, 0);
        when(equipeService.buscarPorId(EQUIPE_ID)).thenReturn(new EquipeResponse(
                EQUIPE_ID, "obra-1", "Quarta intervencao", "FR Carlos",
                "Equipe de fresagem.", "ATIVA", agora, null, 18, agora, agora,
                List.of()
        ));
        when(jdbcTemplate.update(anyString(), eq(EQUIPE_ID))).thenReturn(1);
    }

    /*
     * A conversa se desamarra ANTES do delete da equipe — a FK dela barraria o
     * delete —, e membros e associações saem antes da própria equipe.
     */
    @Test
    void apagaNaOrdemDasChavesEstrangeiras() {
        EquipeDeletionResponse resposta = service.apagar(EQUIPE_ID);

        assertThat(resposta.equipeId()).isEqualTo(EQUIPE_ID);
        assertThat(resposta.nome()).isEqualTo("FR Carlos");

        InOrder ordem = inOrder(jdbcTemplate);
        // A conversa vira GRUPO em vez de só perder a chave: o CHECK de escopo
        // exige que conversa de tipo EQUIPE tenha equipe_id preenchido.
        ordem.verify(jdbcTemplate).update(
                contains("SET tipo = 'GRUPO'"), eq(EQUIPE_ID)
        );
        ordem.verify(jdbcTemplate).update(
                contains("DELETE FROM equipe_membro"), eq(EQUIPE_ID)
        );
        ordem.verify(jdbcTemplate).update(
                contains("DELETE FROM equipe_obra"), eq(EQUIPE_ID)
        );
        ordem.verify(jdbcTemplate).update(
                contains("DELETE FROM equipe WHERE id"), eq(EQUIPE_ID)
        );
        verify(memoryPublisher).equipeApagada(any(), eq("alfa-1"));
    }

    @Test
    void soOAlfaApaga() {
        doThrow(new ResponseStatusException(HttpStatus.FORBIDDEN, "Apenas ALFA."))
                .when(currentUserService).requireAlfa();

        assertThatThrownBy(() -> service.apagar(EQUIPE_ID))
                .isInstanceOf(ResponseStatusException.class);

        verify(jdbcTemplate, never()).update(anyString(), any(Object[].class));
        verify(memoryPublisher, never()).equipeApagada(any(), anyString());
    }

    /*
     * DELETE que não pegou exatamente uma linha significa corrida — alguém
     * apagou antes. Publicar na Memória um apagamento que este ator não fez
     * inventaria autoria.
     */
    @Test
    void naoPublicaQuandoODeleteFinalNaoPegaUmaLinha() {
        when(jdbcTemplate.update(
                contains("DELETE FROM equipe WHERE id"), eq(EQUIPE_ID)
        )).thenReturn(0);

        assertThatThrownBy(() -> service.apagar(EQUIPE_ID))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("mudou durante a exclusão");

        verify(memoryPublisher, never()).equipeApagada(any(), anyString());
    }

    @Test
    void exigeIdentificador() {
        assertThatThrownBy(() -> service.apagar("  "))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("equipeId é obrigatório");
    }
}
