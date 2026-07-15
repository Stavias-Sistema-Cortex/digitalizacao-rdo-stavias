package com.projeto.cortex.mensagens;

import com.projeto.cortex.auth.CurrentUserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ConversationAccessPolicyTest {

    private CurrentUserService currentUserService;
    private ConversationScopeRepository repository;
    private ConversationAccessPolicy policy;

    @BeforeEach
    void setUp() {
        currentUserService = mock(CurrentUserService.class);
        repository = mock(ConversationScopeRepository.class);
        policy = new ConversationAccessPolicy(currentUserService, repository);
        when(currentUserService.requireUserId()).thenReturn("user-1");
    }

    @Test
    void betaParticipantCanReadConversationInAuthorizedWorksite() {
        ConversationScope scope = scope("OBRA", "obra-1", null, "ATIVA");
        when(repository.findConversation("conversation-1"))
                .thenReturn(Optional.of(scope));
        when(repository.hasActiveParticipant("conversation-1", "user-1"))
                .thenReturn(true);
        when(currentUserService.podeAcessarObra("user-1", "obra-1"))
                .thenReturn(true);

        assertThat(policy.requireRead("conversation-1")).isEqualTo(scope);
    }

    @Test
    void betaCannotReadAuthorizedWorksiteConversationWithoutParticipation() {
        when(repository.findConversation("conversation-1"))
                .thenReturn(Optional.of(scope("OBRA", "obra-1", null, "ATIVA")));
        when(currentUserService.podeAcessarObra("user-1", "obra-1"))
                .thenReturn(true);

        assertForbidden(() -> policy.requireRead("conversation-1"));
    }

    @Test
    void betaCannotReadConversationOutsideWorksiteScope() {
        when(repository.findConversation("conversation-1"))
                .thenReturn(Optional.of(scope("OBRA", "obra-2", null, "ATIVA")));
        when(repository.hasActiveParticipant("conversation-1", "user-1"))
                .thenReturn(true);

        assertForbidden(() -> policy.requireRead("conversation-1"));
    }

    @Test
    void betaCanReadTeamConversationWhenAnyActiveTeamWorksiteIsAuthorized() {
        ConversationScope scope = scope("EQUIPE", null, "team-1", "ATIVA");
        when(repository.findConversation("conversation-1"))
                .thenReturn(Optional.of(scope));
        when(repository.hasActiveParticipant("conversation-1", "user-1"))
                .thenReturn(true);
        when(repository.activeTeamWorksiteIds("team-1"))
                .thenReturn(List.of("obra-2", "obra-1"));
        when(currentUserService.podeAcessarObra("user-1", "obra-1"))
                .thenReturn(true);

        assertThat(policy.requireRead("conversation-1")).isEqualTo(scope);
    }

    @Test
    void betaCannotReadTeamConversationWithoutAuthorizedWorksiteIntersection() {
        when(repository.findConversation("conversation-1"))
                .thenReturn(Optional.of(scope("EQUIPE", null, "team-1", "ATIVA")));
        when(repository.hasActiveParticipant("conversation-1", "user-1"))
                .thenReturn(true);
        when(repository.activeTeamWorksiteIds("team-1"))
                .thenReturn(List.of("obra-2"));

        assertForbidden(() -> policy.requireRead("conversation-1"));
    }

    @Test
    void directConversationUsesActiveParticipationAsItsScope() {
        ConversationScope scope = scope("DIRETA", null, null, "ATIVA");
        when(repository.findConversation("conversation-1"))
                .thenReturn(Optional.of(scope));
        when(repository.hasActiveParticipant("conversation-1", "user-1"))
                .thenReturn(true);

        assertThat(policy.requireRead("conversation-1")).isEqualTo(scope);
    }

    @Test
    void alfaCanReadForAdministrationButCannotSendWithoutJoining() {
        ConversationScope scope = scope("OBRA", "obra-2", null, "ATIVA");
        when(repository.findConversation("conversation-1"))
                .thenReturn(Optional.of(scope));
        when(currentUserService.isAlfa("user-1")).thenReturn(true);

        assertThat(policy.requireRead("conversation-1")).isEqualTo(scope);
        assertForbidden(() -> policy.requireSend("conversation-1"));
    }

    @Test
    void archivedConversationRejectsNewMessages() {
        when(repository.findConversation("conversation-1"))
                .thenReturn(Optional.of(scope("DIRETA", null, null, "ARQUIVADA")));
        when(repository.hasActiveParticipant("conversation-1", "user-1"))
                .thenReturn(true);

        assertThatThrownBy(() -> policy.requireSend("conversation-1"))
                .isInstanceOfSatisfying(ResponseStatusException.class, exception ->
                        assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.CONFLICT)
                );
    }

    @Test
    void messageAndAttachmentAccessResolveTheOwningConversation() {
        ConversationScope scope = scope("DIRETA", null, null, "ATIVA");
        when(repository.findConversationIdByMessage("message-1"))
                .thenReturn(Optional.of("conversation-1"));
        when(repository.findConversationIdByAttachment("attachment-1"))
                .thenReturn(Optional.of("conversation-1"));
        when(repository.findConversation("conversation-1"))
                .thenReturn(Optional.of(scope));
        when(repository.hasActiveParticipant("conversation-1", "user-1"))
                .thenReturn(true);

        assertThat(policy.requireMessageRead("message-1")).isEqualTo(scope);
        assertThat(policy.requireAttachmentRead("attachment-1")).isEqualTo(scope);
    }

    @Test
    void missingConversationIsNotFound() {
        when(repository.findConversation("missing"))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> policy.requireRead("missing"))
                .isInstanceOfSatisfying(ResponseStatusException.class, exception ->
                        assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND)
                );
    }

    @Test
    void participantAdministrationRequiresAlfa() {
        ConversationScope scope = scope("OBRA", "obra-1", null, "ATIVA");
        when(repository.findConversation("conversation-1"))
                .thenReturn(Optional.of(scope));

        assertThat(policy.requireAdministration("conversation-1")).isEqualTo(scope);
        verify(currentUserService).requireAlfa();
    }

    private ConversationScope scope(
            String type,
            String worksiteId,
            String teamId,
            String status
    ) {
        return new ConversationScope(
                "conversation-1",
                type,
                "Conversa",
                worksiteId,
                teamId,
                status
        );
    }

    private void assertForbidden(Runnable action) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(ResponseStatusException.class, exception ->
                        assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN)
                );
    }
}
