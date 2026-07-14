package com.projeto.cortex.financeiro.access;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.projeto.cortex.auth.CurrentUserService;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
class FinancialAccessServiceTest {

    @Mock
    private CurrentUserService currentUserService;

    @Mock
    private FinancialGrantRepository repository;

    private FinancialAccessService service;

    @BeforeEach
    void setUp() {
        service = new FinancialAccessService(currentUserService, repository);
    }

    @Test
    void alfaHasEveryFinancialPermissionWithoutGrantRows() {
        when(currentUserService.isAlfa("alfa-1")).thenReturn(true);

        assertThat(service.hasPermission(
                "alfa-1",
                "obra-1",
                FinancialPermission.FINANCEIRO_ADMINISTRAR
        )).isTrue();

        verifyNoInteractions(repository);
        verify(currentUserService, never()).podeAcessarObra("alfa-1", "obra-1");
    }

    @Test
    void betaNeedsBothWorksiteLinkAndExactActiveGrant() {
        when(currentUserService.isAlfa("beta-1")).thenReturn(false);
        when(currentUserService.podeAcessarObra("beta-1", "obra-1"))
                .thenReturn(true);
        when(repository.existsActive(
                "beta-1",
                "obra-1",
                FinancialPermission.FINANCEIRO_VISUALIZAR
        )).thenReturn(true);

        assertThat(service.hasPermission(
                "beta-1",
                "obra-1",
                FinancialPermission.FINANCEIRO_VISUALIZAR
        )).isTrue();

        when(currentUserService.podeAcessarObra("beta-1", "obra-2"))
                .thenReturn(false);
        assertThat(service.hasPermission(
                "beta-1",
                "obra-2",
                FinancialPermission.FINANCEIRO_VISUALIZAR
        )).isFalse();
        verify(repository, never()).existsActive(
                "beta-1",
                "obra-2",
                FinancialPermission.FINANCEIRO_VISUALIZAR
        );
    }

    @Test
    void requirePermissionDeniesBeforeDomainAccess() {
        when(currentUserService.requireUserId()).thenReturn("beta-1");
        when(currentUserService.isAlfa("beta-1")).thenReturn(false);
        when(currentUserService.podeAcessarObra("beta-1", "obra-1"))
                .thenReturn(true);
        when(repository.existsActive(
                "beta-1",
                "obra-1",
                FinancialPermission.FINANCEIRO_VISUALIZAR
        )).thenReturn(false);

        assertThatThrownBy(() -> service.requirePermission(
                "obra-1",
                FinancialPermission.FINANCEIRO_VISUALIZAR
        ))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> assertThat(
                        ((ResponseStatusException) error).getStatusCode()
                ).isEqualTo(HttpStatus.FORBIDDEN));
    }

    @Test
    void allowedObraIdsIntersectsLinksAndActiveGrantsForBeta() {
        when(currentUserService.isAlfa("beta-1")).thenReturn(false);
        when(currentUserService.allowedObraIds("beta-1"))
                .thenReturn(java.util.Optional.of(Set.of("obra-1", "obra-2")));
        when(repository.findActiveObraIds(
                "beta-1",
                FinancialPermission.FINANCEIRO_VISUALIZAR
        )).thenReturn(Set.of("obra-2", "obra-3"));

        assertThat(service.allowedObraIds(
                "beta-1",
                FinancialPermission.FINANCEIRO_VISUALIZAR
        )).containsExactly("obra-2");
    }
}
