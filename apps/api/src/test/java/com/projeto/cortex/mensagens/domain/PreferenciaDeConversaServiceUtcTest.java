package com.projeto.cortex.mensagens.domain;

import com.projeto.cortex.auth.CurrentUserService;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.TimeZone;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PreferenciaDeConversaServiceUtcTest {

    @Test
    void clearCurtainUsesUtcEvenWhenTheJvmRunsInBrasilia() {
        TimeZone previous = TimeZone.getDefault();
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("America/Sao_Paulo"));
            JdbcTemplate jdbc = mock(JdbcTemplate.class);
            CurrentUserService currentUser = mock(CurrentUserService.class);
            ConversaAccessPolicy accessPolicy = mock(ConversaAccessPolicy.class);
            when(currentUser.requireUserId()).thenReturn("user-1");
            when(accessPolicy.requireAccess("conversation-1")).thenReturn(
                    new ConversationScope(
                            "conversation-1",
                            ConversationType.GRUPO,
                            null,
                            null,
                            "user-1",
                            "ATIVA"
                    )
            );
            doReturn(1).when(jdbc).update(
                    contains("limpo_ate"),
                    any(Object[].class)
            );
            PreferenciaDeConversaService service =
                    new PreferenciaDeConversaService(
                            jdbc,
                            currentUser,
                            accessPolicy
                    );
            Instant before = Instant.now().minusSeconds(1);

            service.limpar("conversation-1");

            Instant after = Instant.now().plusSeconds(1);
            ArgumentCaptor<Object[]> arguments =
                    ArgumentCaptor.forClass(Object[].class);
            verify(jdbc).update(contains("limpo_ate"), arguments.capture());
            LocalDateTime curtain = (LocalDateTime) arguments.getValue()[0];
            assertThat(curtain.toInstant(ZoneOffset.UTC))
                    .isBetween(before, after);
        } finally {
            TimeZone.setDefault(previous);
        }
    }

    @Test
    void clearCurtainPreservesTheOriginalOfflineInstant() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        CurrentUserService currentUser = mock(CurrentUserService.class);
        ConversaAccessPolicy accessPolicy = mock(ConversaAccessPolicy.class);
        when(currentUser.requireUserId()).thenReturn("user-1");
        when(accessPolicy.requireAccess("conversation-1")).thenReturn(
                new ConversationScope(
                        "conversation-1",
                        ConversationType.GRUPO,
                        null,
                        null,
                        "user-1",
                        "ATIVA"
                )
        );
        doReturn(1).when(jdbc).update(
                contains("limpo_ate"),
                any(Object[].class)
        );
        PreferenciaDeConversaService service =
                new PreferenciaDeConversaService(
                        jdbc,
                        currentUser,
                        accessPolicy
                );

        service.limpar(
                "conversation-1",
                Instant.parse("2026-08-19T10:00:00Z")
        );

        ArgumentCaptor<Object[]> arguments =
                ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).update(contains("limpo_ate"), arguments.capture());
        assertThat(arguments.getValue()[0]).isEqualTo(
                LocalDateTime.parse("2026-08-19T10:00:00")
        );
    }

    @Test
    void clearCurtainRejectsAnAbusiveFutureInstantAfterAuthorization() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        CurrentUserService currentUser = mock(CurrentUserService.class);
        ConversaAccessPolicy accessPolicy = mock(ConversaAccessPolicy.class);
        when(accessPolicy.requireAccess("conversation-1")).thenReturn(
                new ConversationScope(
                        "conversation-1",
                        ConversationType.GRUPO,
                        null,
                        null,
                        "user-1",
                        "ATIVA"
                )
        );
        PreferenciaDeConversaService service =
                new PreferenciaDeConversaService(
                        jdbc,
                        currentUser,
                        accessPolicy
                );

        assertThatThrownBy(() -> service.limpar(
                "conversation-1",
                Instant.parse("2999-01-01T00:00:00Z")
        )).isInstanceOfSatisfying(
                ResponseStatusException.class,
                exception -> assertThat(exception.getStatusCode())
                        .isEqualTo(HttpStatus.BAD_REQUEST)
        );

        verify(accessPolicy).requireAccess("conversation-1");
    }
}
