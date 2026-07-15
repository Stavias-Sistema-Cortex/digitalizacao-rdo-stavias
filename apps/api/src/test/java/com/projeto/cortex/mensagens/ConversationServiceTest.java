package com.projeto.cortex.mensagens;

import com.projeto.cortex.auth.CurrentUserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ConversationServiceTest {

    private ConversationRepository repository;
    private CurrentUserService currentUserService;
    private ConversationAccessPolicy accessPolicy;
    private ConversationMemoryPublisher memoryPublisher;
    private ConversationService service;

    @BeforeEach
    void setUp() {
        repository = mock(ConversationRepository.class);
        currentUserService = mock(CurrentUserService.class);
        accessPolicy = mock(ConversationAccessPolicy.class);
        memoryPublisher = mock(ConversationMemoryPublisher.class);
        service = new ConversationService(
                repository,
                currentUserService,
                accessPolicy,
                memoryPublisher
        );
        when(currentUserService.requireUserId()).thenReturn("alfa-1");
        when(currentUserService.isAlfa("alfa-1")).thenReturn(true);
        when(currentUserService.allowedObraIds("alfa-1"))
                .thenReturn(Optional.empty());
    }

    @Test
    void alfaCreatesTeamConversationWithRealAuthorizedTeamMembers() {
        ConversaCreateRequest request = new ConversaCreateRequest(
                "conversation-1",
                "EQUIPE",
                "Equipe Norte",
                "obra-1",
                "team-1",
                List.of()
        );
        ConversaResponse expected = conversation(
                "EQUIPE",
                "obra-1",
                "team-1",
                List.of(participant("participant-a", "alfa-1"),
                        participant("participant-b", "beta-1"))
        );

        when(repository.findById("conversation-1"))
                .thenReturn(Optional.empty(), Optional.of(expected));
        when(repository.worksiteExists("obra-1")).thenReturn(true);
        when(repository.teamActiveAtWorksite("team-1", "obra-1"))
                .thenReturn(true);
        when(repository.activeTeamMemberIds("team-1"))
                .thenReturn(List.of("alfa-1", "beta-1"));
        when(repository.activeCollaboratorIds(Set.of("alfa-1", "beta-1")))
                .thenReturn(Set.of("alfa-1", "beta-1"));
        when(currentUserService.podeAcessarObra("alfa-1", "obra-1"))
                .thenReturn(true);
        when(currentUserService.podeAcessarObra("beta-1", "obra-1"))
                .thenReturn(true);

        ConversaResponse created = service.criar(request);

        assertThat(created).isEqualTo(expected);
        verify(repository).insertConversation(
                eq(request),
                eq("alfa-1"),
                any(LocalDateTime.class)
        );
        verify(repository).insertParticipant(
                eq("conversation-1"),
                eq("alfa-1"),
                eq("ADMINISTRADOR"),
                eq("alfa-1"),
                any(LocalDateTime.class)
        );
        verify(repository).insertParticipant(
                eq("conversation-1"),
                eq("beta-1"),
                eq("MEMBRO"),
                eq("alfa-1"),
                any(LocalDateTime.class)
        );
        verify(memoryPublisher).conversaCriada(expected, "alfa-1");
    }

    @Test
    void betaCannotCreateTeamConversationWithoutActiveMembership() {
        when(currentUserService.requireUserId()).thenReturn("beta-1");
        when(currentUserService.isAlfa("beta-1")).thenReturn(false);
        when(repository.worksiteExists("obra-1")).thenReturn(true);
        when(repository.teamActiveAtWorksite("team-1", "obra-1"))
                .thenReturn(true);
        when(repository.activeTeamMemberIds("team-1"))
                .thenReturn(List.of("other-user"));

        ConversaCreateRequest request = new ConversaCreateRequest(
                "conversation-1", "EQUIPE", null,
                "obra-1", "team-1", List.of()
        );

        assertForbidden(() -> service.criar(request));
        verify(repository, never()).insertConversation(any(), any(), any());
    }

    @Test
    void betaDirectConversationRequiresWorksiteContext() {
        when(currentUserService.requireUserId()).thenReturn("beta-1");
        when(currentUserService.isAlfa("beta-1")).thenReturn(false);

        ConversaCreateRequest request = new ConversaCreateRequest(
                "conversation-1", "DIRETA", null,
                null, null, List.of("other-user")
        );

        assertThatThrownBy(() -> service.criar(request))
                .isInstanceOfSatisfying(ResponseStatusException.class, exception ->
                        assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST)
                );
    }

    @Test
    void participantWithoutWorksiteAccessIsRejected() {
        when(repository.worksiteExists("obra-1")).thenReturn(true);
        when(repository.activeCollaboratorIds(Set.of("alfa-1", "beta-1")))
                .thenReturn(Set.of("alfa-1", "beta-1"));
        when(currentUserService.podeAcessarObra("alfa-1", "obra-1"))
                .thenReturn(true);

        ConversaCreateRequest request = new ConversaCreateRequest(
                "conversation-1", "OBRA", "Frente norte",
                "obra-1", null, List.of("beta-1")
        );

        assertThatThrownBy(() -> service.criar(request))
                .isInstanceOfSatisfying(ResponseStatusException.class, exception -> {
                    assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(exception.getReason()).contains("acesso à obra");
                });
        verify(repository, never()).insertConversation(any(), any(), any());
    }

    @Test
    void retryWithSameCreationIdReturnsExistingConversation() {
        ConversaResponse existing = conversation(
                "OBRA", "obra-1", null,
                List.of(participant("participant-a", "alfa-1"))
        );
        when(repository.findById("conversation-1"))
                .thenReturn(Optional.of(existing));
        when(accessPolicy.requireRead("conversation-1")).thenReturn(new ConversationScope(
                "conversation-1", "OBRA", "Conversa", "obra-1", null, "ATIVA"
        ));

        ConversaResponse retried = service.criar(new ConversaCreateRequest(
                "conversation-1", "OBRA", "Conversa",
                "obra-1", null, List.of()
        ));

        assertThat(retried).isEqualTo(existing);
        verify(repository, never()).insertConversation(any(), any(), any());
    }

    @Test
    void betaWithoutWorksitesReturnsEmptyPageWithoutRepositoryQuery() {
        when(currentUserService.requireUserId()).thenReturn("beta-1");
        when(currentUserService.allowedObraIds("beta-1"))
                .thenReturn(Optional.of(Set.of()));

        ConversaPageResponse page = service.listar(new ConversaFilter(
                null, null, null, 1, 25
        ));

        assertThat(page.items()).isEmpty();
        assertThat(page.totalElements()).isZero();
        verify(repository, never()).list(any(), any(), any());
    }

    @Test
    void alfaAddsAuthorizedParticipantAndPublishesTheRelation() {
        ConversationScope scope = new ConversationScope(
                "conversation-1", "OBRA", "Conversa", "obra-1", null, "ATIVA"
        );
        ConversaParticipantRequest request = new ConversaParticipantRequest(
                "participant-b", "beta-1", "MEMBRO",
                LocalDateTime.of(2026, 7, 15, 10, 0)
        );
        ConversaParticipantResponse expected = participant("participant-b", "beta-1");
        when(accessPolicy.requireAdministration("conversation-1")).thenReturn(scope);
        when(repository.activeCollaboratorIds(Set.of("beta-1")))
                .thenReturn(Set.of("beta-1"));
        when(currentUserService.podeAcessarObra("beta-1", "obra-1"))
                .thenReturn(true);
        when(repository.findActiveParticipant("conversation-1", "beta-1"))
                .thenReturn(Optional.empty());
        when(repository.findParticipantById("participant-b"))
                .thenReturn(Optional.of(expected));

        ConversaParticipantResponse added = service.adicionarParticipante(
                "conversation-1", request
        );

        assertThat(added).isEqualTo(expected);
        verify(repository).insertParticipant(
                "participant-b",
                "conversation-1",
                "beta-1",
                "MEMBRO",
                request.entrouEm(),
                "alfa-1"
        );
        verify(memoryPublisher).participanteAdicionado(
                scope, expected, "alfa-1"
        );
    }

    @Test
    void alfaUpdatesConversationWithOptimisticVersionAndAudit() {
        ConversationScope scope = new ConversationScope(
                "conversation-1", "OBRA", "Conversa", "obra-1", null, "ATIVA"
        );
        ConversaResponse before = conversation(
                "OBRA", "obra-1", null,
                List.of(participant("participant-a", "alfa-1"))
        );
        LocalDateTime now = LocalDateTime.of(2026, 7, 15, 11, 0);
        ConversaResponse after = new ConversaResponse(
                before.id(), before.tipo(), "Decisões da frente norte",
                before.obraId(), before.obraNome(), before.equipeId(),
                before.equipeNome(), before.criadoPor(), "ATIVA",
                now, before.criadoEm(), now, 2, before.participantes()
        );
        when(accessPolicy.requireAdministration("conversation-1")).thenReturn(scope);
        when(repository.findById("conversation-1"))
                .thenReturn(Optional.of(before), Optional.of(after));
        when(repository.updateConversation(
                eq("conversation-1"),
                eq("Decisões da frente norte"),
                eq("ATIVA"),
                eq(1L),
                any(LocalDateTime.class)
        )).thenReturn(1);

        ConversaResponse updated = service.atualizar(
                "conversation-1",
                new ConversaUpdateRequest(
                        "Decisões da frente norte",
                        "ATIVA",
                        1L,
                        "Consolidar decisões"
                )
        );

        assertThat(updated).isEqualTo(after);
        verify(memoryPublisher).conversaAtualizada(
                before, after, "alfa-1", 1L, "Consolidar decisões"
        );
    }

    @Test
    void staleParticipantEndIsRejectedWithoutDeletingHistory() {
        ConversationScope scope = new ConversationScope(
                "conversation-1", "DIRETA", null, null, null, "ATIVA"
        );
        ConversaParticipantResponse participant = participant("participant-b", "beta-1");
        when(accessPolicy.requireAdministration("conversation-1")).thenReturn(scope);
        when(repository.findParticipantById("participant-b"))
                .thenReturn(Optional.of(participant));
        when(repository.endParticipant(
                eq("participant-b"), eq(3L), eq("alfa-1"),
                any(LocalDateTime.class)
        )).thenReturn(0);

        assertThatThrownBy(() -> service.encerrarParticipante(
                "conversation-1",
                "participant-b",
                new ConversaParticipantEndRequest(3L, "Saída", null)
        )).isInstanceOfSatisfying(ResponseStatusException.class, exception ->
                assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.CONFLICT)
        );

    }

    private ConversaResponse conversation(
            String type,
            String worksiteId,
            String teamId,
            List<ConversaParticipantResponse> participants
    ) {
        LocalDateTime now = LocalDateTime.of(2026, 7, 15, 9, 0);
        return new ConversaResponse(
                "conversation-1", type, "Conversa", worksiteId,
                worksiteId == null ? null : "Obra Norte",
                teamId, teamId == null ? null : "Equipe Norte",
                "alfa-1", "ATIVA", now, now, now, 1,
                participants
        );
    }

    private ConversaParticipantResponse participant(String id, String collaboratorId) {
        LocalDateTime now = LocalDateTime.of(2026, 7, 15, 9, 0);
        return new ConversaParticipantResponse(
                id, "conversation-1", collaboratorId,
                collaboratorId.equals("alfa-1") ? "Alfa" : "Beta",
                collaboratorId.equals("alfa-1") ? "ADMINISTRADOR" : "MEMBRO",
                "ATIVO", now, null, null, 1
        );
    }

    private void assertForbidden(Runnable action) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(ResponseStatusException.class, exception ->
                        assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN)
                );
    }
}
