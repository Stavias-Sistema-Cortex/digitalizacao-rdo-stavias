package com.projeto.cortex.auth.roles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.projeto.cortex.auth.PapelAcesso;
import com.projeto.cortex.memory.CortexOperationalMemoryService;
import java.time.LocalDateTime;
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

    @Test
    void versionedLegacyChangeRejectsAlfaWithoutAdministrativeCapability() {
        when(repository.findActiveAccount(ACTOR))
                .thenReturn(Optional.of(new RoleAccount(
                        ACTOR,
                        "Alfa sem capacidade",
                        PapelAcesso.ALFA,
                        9L,
                        LocalDateTime.of(2026, 7, 23, 16, 0)
                )));
        when(repository.hasActiveCapability(ACTOR)).thenReturn(false);

        assertThatThrownBy(() -> service.changeRoleVersioned(
                ACTOR,
                TARGET,
                new AdminRoleVersionedChangeRequest(
                        "BETA",
                        "ALFA",
                        4L,
                        "Promoção aprovada pela direção."
                )
        )).isInstanceOfSatisfying(ResponseStatusException.class, error ->
                assertThat(error.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN)
        );

        verify(repository).lockGovernanceRows();
        verify(repository, never()).updateRole(any(), any());
        verify(repository, never()).updateRoleIfCurrent(
                any(), any(), anyLong(), any()
        );
        verify(repository, never()).insertHistory(any(), any(), any(), any(), any());
        verify(repository, never()).revokeActiveSessions(any(), any());
    }

    @Test
    void versionedLegacyChangeChecksExpectedStateInsideGovernanceLock() {
        authorizeActor();
        when(repository.findActiveAccount(TARGET))
                .thenReturn(Optional.of(new RoleAccount(
                        TARGET,
                        "Operadora",
                        PapelAcesso.BETA,
                        5L,
                        LocalDateTime.of(2026, 7, 23, 16, 0)
                )));

        assertThatThrownBy(() -> service.changeRoleVersioned(
                ACTOR,
                TARGET,
                new AdminRoleVersionedChangeRequest(
                        "BETA",
                        "ALFA",
                        4L,
                        "Promoção aprovada pela direção."
                )
        )).isInstanceOfSatisfying(ResponseStatusException.class, error -> {
            assertThat(error.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(error.getReason())
                    .isEqualTo("Conflito de versão ou papel de acesso do colaborador.");
        });

        verify(repository).lockGovernanceRows();
        verify(repository, never()).updateRole(any(), any());
        verify(repository, never()).updateRoleIfCurrent(
                any(), any(), anyLong(), any()
        );
        verify(repository, never()).insertHistory(any(), any(), any(), any(), any());
    }

    @Test
    void versionedLegacyChangeReturnsPersistedSnapshotAndWritesCanonicalAudit() {
        LocalDateTime beforeTime = LocalDateTime.of(2026, 7, 22, 16, 0);
        LocalDateTime afterTime = LocalDateTime.of(2026, 7, 23, 16, 0);
        authorizeActor();
        when(repository.findActiveAccount(TARGET))
                .thenReturn(
                        Optional.of(new RoleAccount(
                                TARGET,
                                "Operadora",
                                PapelAcesso.BETA,
                                4L,
                                beforeTime
                        )),
                        Optional.of(new RoleAccount(
                                TARGET,
                                "Operadora",
                                PapelAcesso.ALFA,
                                5L,
                                afterTime
                        ))
                );
        when(memory.registrarEventoAuditado(
                any(), any(), any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any()
        )).thenReturn(89L);
        when(repository.updateRoleIfCurrent(
                TARGET,
                PapelAcesso.BETA,
                4L,
                PapelAcesso.ALFA
        )).thenReturn(true);

        AdminRoleVersionedChangeResponse response = service.changeRoleVersioned(
                ACTOR,
                TARGET,
                new AdminRoleVersionedChangeRequest(
                        "BETA",
                        "ALFA",
                        4L,
                        "Promoção aprovada pela direção."
                )
        );

        assertThat(response).isEqualTo(new AdminRoleVersionedChangeResponse(
                TARGET,
                "Operadora",
                "ALFA",
                5L,
                afterTime
        ));
        verify(repository).updateRoleIfCurrent(
                TARGET,
                PapelAcesso.BETA,
                4L,
                PapelAcesso.ALFA
        );
        verify(repository).insertHistory(
                TARGET,
                PapelAcesso.BETA,
                PapelAcesso.ALFA,
                ACTOR,
                "Promoção aprovada pela direção."
        );
        verify(memory).registrarEventoAuditado(
                any(), any(), any(), eq("PAPEL_ACESSO_ALTERADO"), any(),
                any(), any(), any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any(), any(), any(), any()
        );
    }

    @Test
    void versionedLegacyDowngradeUsesCanonicalLocksRevocationsAndHistory() {
        LocalDateTime beforeTime = LocalDateTime.of(2026, 7, 22, 17, 0);
        LocalDateTime afterTime = LocalDateTime.of(2026, 7, 23, 17, 0);
        authorizeActor();
        when(repository.findActiveAccount(TARGET))
                .thenReturn(
                        Optional.of(new RoleAccount(
                                TARGET,
                                "Gestora",
                                PapelAcesso.ALFA,
                                6L,
                                beforeTime
                        )),
                        Optional.of(new RoleAccount(
                                TARGET,
                                "Gestora",
                                PapelAcesso.BETA,
                                7L,
                                afterTime
                        ))
                );
        when(repository.countActiveAlfas()).thenReturn(2L);
        when(repository.hasActiveCapability(TARGET)).thenReturn(true);
        when(repository.countActiveRoleAdministrators()).thenReturn(2L);
        when(repository.updateRoleIfCurrent(
                TARGET,
                PapelAcesso.ALFA,
                6L,
                PapelAcesso.BETA
        )).thenReturn(true);
        when(repository.revokeActiveSessions(
                TARGET,
                "PAPEL_REBAIXADO_PARA_BETA"
        )).thenReturn(3);

        AdminRoleVersionedChangeResponse response = service.changeRoleVersioned(
                ACTOR,
                TARGET,
                new AdminRoleVersionedChangeRequest(
                        "ALFA",
                        "BETA",
                        6L,
                        "Rebaixamento aprovado pela direção."
                )
        );

        assertThat(response.papelAcesso()).isEqualTo("BETA");
        assertThat(response.versaoLinha()).isEqualTo(7L);
        verify(repository).lockGovernanceRows();
        verify(repository).countActiveAlfas();
        verify(repository).countActiveRoleAdministrators();
        verify(repository).revokeCapability(
                TARGET,
                ACTOR,
                "Rebaixamento aprovado pela direção."
        );
        verify(repository).revokeActiveSessions(
                TARGET,
                "PAPEL_REBAIXADO_PARA_BETA"
        );
        verify(repository).insertHistory(
                TARGET,
                PapelAcesso.ALFA,
                PapelAcesso.BETA,
                ACTOR,
                "Rebaixamento aprovado pela direção."
        );
        verify(memory).registrarEventoAuditado(
                any(), any(), any(), eq("PAPEL_ACESSO_ALTERADO"), any(),
                any(), any(), any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any(), any(), any(), any()
        );
    }

    private void authorizeActor() {
        when(repository.findActiveAccount(ACTOR))
                .thenReturn(Optional.of(new RoleAccount(ACTOR, "Administradora", PapelAcesso.ALFA)));
        when(repository.hasActiveCapability(ACTOR)).thenReturn(true);
    }
}
