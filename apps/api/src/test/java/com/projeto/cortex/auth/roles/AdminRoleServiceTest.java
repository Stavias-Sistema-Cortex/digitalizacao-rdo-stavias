package com.projeto.cortex.auth.roles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.projeto.cortex.auth.PapelAcesso;
import com.projeto.cortex.memory.CortexOperationalMemoryService;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
class AdminRoleServiceTest {

    private static final String ACTOR = "11111111-1111-4111-8111-111111111111";
    private static final String TARGET = "22222222-2222-4222-8222-222222222222";

    @Mock
    private AdminRoleRepository repository;

    @Mock
    private CortexOperationalMemoryService memory;

    private AdminRoleService service;

    @BeforeEach
    void setUp() {
        service = new AdminRoleService(repository, memory);
    }

    @Test
    void deniesAlfaWithoutTheAdministrativeCapability() {
        when(repository.findActiveAccount(ACTOR))
                .thenReturn(Optional.of(new RoleAccount(ACTOR, "Alfa", PapelAcesso.ALFA)));
        when(repository.hasActiveCapability(ACTOR)).thenReturn(false);

        assertThatThrownBy(() -> service.changeRole(
                ACTOR,
                TARGET,
                new AdminRoleChangeRequest("BETA", "Mudança aprovada pela direção.")
        )).isInstanceOfSatisfying(ResponseStatusException.class, error ->
                assertThat(error.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN)
        );

        verify(repository, never()).updateRole(any(), any());
    }

    @Test
    void protectsTheLastActiveAlfa() {
        authorizeActor();
        when(repository.findActiveAccount(TARGET))
                .thenReturn(Optional.of(new RoleAccount(TARGET, "Última Alfa", PapelAcesso.ALFA)));
        when(repository.countActiveAlfas()).thenReturn(1L);

        assertThatThrownBy(() -> service.changeRole(
                ACTOR,
                TARGET,
                new AdminRoleChangeRequest("BETA", "Reorganização operacional aprovada.")
        )).isInstanceOfSatisfying(ResponseStatusException.class, error ->
                assertThat(error.getStatusCode()).isEqualTo(HttpStatus.CONFLICT)
        );

        verify(repository, never()).updateRole(any(), any());
    }

    @Test
    void downgradeRevokesCapabilityAndSessionsAndPublishesOntology() {
        authorizeActor();
        when(repository.findActiveAccount(TARGET))
                .thenReturn(Optional.of(new RoleAccount(TARGET, "Gestora", PapelAcesso.ALFA)));
        when(repository.countActiveAlfas()).thenReturn(2L);
        when(repository.hasActiveCapability(TARGET)).thenReturn(true);
        when(repository.countActiveRoleAdministrators()).thenReturn(2L);
        when(repository.revokeActiveSessions(TARGET, "PAPEL_REBAIXADO_PARA_BETA"))
                .thenReturn(2);
        when(memory.registrarEventoAuditado(
                any(), any(), any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any()
        )).thenReturn(73L);

        AdminRoleChangeResponse response = service.changeRole(
                ACTOR,
                TARGET,
                new AdminRoleChangeRequest("BETA", "Reorganização operacional aprovada.")
        );

        assertThat(response.papelAnterior()).isEqualTo("ALFA");
        assertThat(response.papelAcesso()).isEqualTo("BETA");
        assertThat(response.sessoesRevogadas()).isEqualTo(2);
        assertThat(response.commitSeq()).isEqualTo(73L);
        verify(repository).revokeCapability(
                TARGET,
                ACTOR,
                "Reorganização operacional aprovada."
        );
        verify(repository).updateRole(TARGET, PapelAcesso.BETA);
        verify(repository).insertHistory(
                eq(TARGET),
                eq(PapelAcesso.ALFA),
                eq(PapelAcesso.BETA),
                eq(ACTOR),
                eq("Reorganização operacional aprovada.")
        );
        verify(memory).registrarRelacaoAtiva(
                "PESSOA",
                ACTOR,
                "PESSOA",
                TARGET,
                "ALTEROU_PAPEL_DE",
                "CORTEX_AUTH",
                "ALFA -> BETA"
        );
    }

    @Test
    void promotionPreservesSpecificGrantsAndDoesNotRevokeSessions() {
        authorizeActor();
        when(repository.findActiveAccount(TARGET))
                .thenReturn(Optional.of(new RoleAccount(TARGET, "Operadora", PapelAcesso.BETA)));

        service.changeRole(
                ACTOR,
                TARGET,
                new AdminRoleChangeRequest("ALFA", "Promoção aprovada pela administração.")
        );

        verify(repository).updateRole(TARGET, PapelAcesso.ALFA);
        verify(repository, never()).revokeActiveSessions(any(), any());
    }

    private void authorizeActor() {
        when(repository.findActiveAccount(ACTOR))
                .thenReturn(Optional.of(new RoleAccount(ACTOR, "Administradora", PapelAcesso.ALFA)));
        when(repository.hasActiveCapability(ACTOR)).thenReturn(true);
    }
}
