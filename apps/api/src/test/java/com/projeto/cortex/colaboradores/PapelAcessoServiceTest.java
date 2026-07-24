package com.projeto.cortex.colaboradores;

import com.projeto.cortex.auth.CurrentUserService;
import com.projeto.cortex.auth.roles.AdminRoleService;
import com.projeto.cortex.auth.roles.AdminRoleVersionedChangeRequest;
import com.projeto.cortex.auth.roles.AdminRoleVersionedChangeResponse;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PapelAcessoServiceTest {

    private final CurrentUserService currentUserService = mock(CurrentUserService.class);
    private final AdminRoleService adminRoleService = mock(AdminRoleService.class);
    private final PapelAcessoService service = new PapelAcessoService(
            currentUserService,
            adminRoleService
    );

    @Test
    void confirmedVersionedRequestDelegatesToCanonicalGovernanceAndPreservesResponse() {
        LocalDateTime now = LocalDateTime.of(2026, 7, 15, 13, 0);
        String actorId = "11111111-1111-4111-8111-111111111111";
        String targetId = "22222222-2222-4222-8222-222222222222";
        PapelAcessoUpdateRequest request = new PapelAcessoUpdateRequest(
                "BETA", "ALFA", 4L, "Responsável pela administração regional", true
        );

        when(currentUserService.requireUserId()).thenReturn(actorId);
        when(adminRoleService.changeRoleVersioned(
                actorId,
                targetId,
                new AdminRoleVersionedChangeRequest(
                        "BETA",
                        "ALFA",
                        4L,
                        "Responsável pela administração regional"
                )
        )).thenReturn(new AdminRoleVersionedChangeResponse(
                targetId,
                "Bruno",
                "ALFA",
                5L,
                now
        ));

        PapelAcessoUpdateResponse response = service.alterar(targetId, request);

        assertThat(response).isEqualTo(new PapelAcessoUpdateResponse(
                targetId,
                "Bruno",
                "ALFA",
                5L,
                now
        ));
        verify(adminRoleService).changeRoleVersioned(
                actorId,
                targetId,
                new AdminRoleVersionedChangeRequest(
                        "BETA",
                        "ALFA",
                        4L,
                        "Responsável pela administração regional"
                )
        );
    }

    @Test
    void preservesCanonicalCapabilityDenialFromGovernanceService() {
        String actorId = "11111111-1111-4111-8111-111111111111";
        String targetId = "22222222-2222-4222-8222-222222222222";
        PapelAcessoUpdateRequest request = new PapelAcessoUpdateRequest(
                "BETA", "ALFA", 7L, "Promoção aprovada pela direção", true
        );

        when(currentUserService.requireUserId()).thenReturn(actorId);
        when(adminRoleService.changeRoleVersioned(
                eq(actorId),
                eq(targetId),
                any()
        )).thenThrow(new ResponseStatusException(
                HttpStatus.FORBIDDEN,
                "A operação exige a capacidade ADMINISTRAR_PAPEIS."
        ));

        assertThatThrownBy(() -> service.alterar(targetId, request))
                .isInstanceOfSatisfying(ResponseStatusException.class, exception -> {
                    assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
                    assertThat(exception.getReason())
                            .isEqualTo("A operação exige a capacidade ADMINISTRAR_PAPEIS.");
                });
    }

    @Test
    void requiresExplicitConfirmationForSensitiveRoleChange() {
        PapelAcessoUpdateRequest request = new PapelAcessoUpdateRequest(
                "BETA", "ALFA", 1L, "Promoção", false
        );

        assertThatThrownBy(() -> service.alterar("colab-2", request))
                .isInstanceOfSatisfying(ResponseStatusException.class, exception -> {
                    assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(exception.getReason())
                            .isEqualTo("A mudança de acesso exige confirmação explícita.");
                });

        verify(adminRoleService, never()).changeRoleVersioned(any(), any(), any());
    }
}
