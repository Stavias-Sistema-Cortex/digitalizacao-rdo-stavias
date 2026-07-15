package com.projeto.cortex.mensagens;

import com.projeto.cortex.auth.CurrentUserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MessageReferenceResolverTest {

    private MessageReferenceLookup lookup;
    private CurrentUserService currentUserService;
    private MessageReferenceResolver resolver;

    @BeforeEach
    void setUp() {
        lookup = mock(MessageReferenceLookup.class);
        currentUserService = mock(CurrentUserService.class);
        resolver = new MessageReferenceResolver(lookup, currentUserService);
        when(currentUserService.requireUserId()).thenReturn("user-1");
    }

    @Test
    void resolvesWorksiteReferenceInsideConversationScope() {
        when(lookup.worksiteForKnownObject("OBRA", "obra-1"))
                .thenReturn(Optional.of("obra-1"));

        ResolvedMessageReference resolved = resolver.resolve(
                new MensagemReferenciaRequest("reference-1", "OBRA", "obra-1"),
                scope()
        );

        assertThat(resolved).isEqualTo(new ResolvedMessageReference(
                "reference-1", "OBRA", "obra-1", "obra-1"
        ));
    }

    @Test
    void rejectsRdoFromAnotherWorksite() {
        when(lookup.worksiteForKnownObject("RDO", "rdo-2"))
                .thenReturn(Optional.of("obra-2"));

        assertThatThrownBy(() -> resolver.resolve(
                new MensagemReferenciaRequest("reference-1", "RDO", "rdo-2"),
                scope()
        )).isInstanceOfSatisfying(ResponseStatusException.class, exception ->
                assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN)
        );
    }

    @Test
    void userReferenceMustBeAnActiveConversationParticipant() {
        when(lookup.activeConversationParticipant("conversation-1", "user-2"))
                .thenReturn(false);

        assertThatThrownBy(() -> resolver.resolve(
                new MensagemReferenciaRequest("reference-1", "USUARIO", "user-2"),
                scope()
        )).isInstanceOfSatisfying(ResponseStatusException.class, exception ->
                assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST)
        );
    }

    @Test
    void genericOntologyReferenceRequiresRealRelationToConversationWorksite() {
        when(lookup.ontologyObjectRelatedToWorksite(
                "EQUIPAMENTO", "asset-1", "obra-1"
        )).thenReturn(true);

        ResolvedMessageReference resolved = resolver.resolve(
                new MensagemReferenciaRequest(
                        "reference-1", "EQUIPAMENTO", "asset-1"
                ),
                scope()
        );

        assertThat(resolved.worksiteId()).isEqualTo("obra-1");
    }

    @Test
    void missingKnownReferenceIsNotFound() {
        when(lookup.worksiteForKnownObject("PROGRAMACAO", "program-1"))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> resolver.resolve(
                new MensagemReferenciaRequest(
                        "reference-1", "PROGRAMACAO", "program-1"
                ),
                scope()
        )).isInstanceOfSatisfying(ResponseStatusException.class, exception ->
                assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND)
        );
    }

    private ConversationScope scope() {
        return new ConversationScope(
                "conversation-1", "OBRA", "Frente norte",
                "obra-1", null, "ATIVA"
        );
    }
}
