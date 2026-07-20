package com.projeto.cortex.pdor;

import com.projeto.cortex.auth.CurrentUserService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class PdorExecutionInitiatorResolverTest {

    @AfterEach
    void clearRequestContext() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void shouldIdentifyBackgroundExecutionAsProcess() {
        CurrentUserService currentUserService = mock(CurrentUserService.class);
        PdorExecutionInitiatorResolver resolver =
                new PdorExecutionInitiatorResolver(currentUserService);

        assertThat(resolver.resolve())
                .isEqualTo(PdorExecutionInitiator.process());
        verifyNoInteractions(currentUserService);
    }

    @Test
    void shouldPreserveAuthenticatedHttpUser() {
        CurrentUserService currentUserService = mock(CurrentUserService.class);
        when(currentUserService.requireUserId()).thenReturn("usuario-1");
        RequestContextHolder.setRequestAttributes(
                new ServletRequestAttributes(new MockHttpServletRequest())
        );

        PdorExecutionInitiatorResolver resolver =
                new PdorExecutionInitiatorResolver(currentUserService);

        assertThat(resolver.resolve())
                .isEqualTo(new PdorExecutionInitiator("usuario-1", "USER"));
    }

    @Test
    void shouldNotDisguiseMissingHttpIdentityAsProcess() {
        CurrentUserService currentUserService = mock(CurrentUserService.class);
        when(currentUserService.requireUserId()).thenThrow(
                new ResponseStatusException(HttpStatus.UNAUTHORIZED)
        );
        RequestContextHolder.setRequestAttributes(
                new ServletRequestAttributes(new MockHttpServletRequest())
        );

        PdorExecutionInitiatorResolver resolver =
                new PdorExecutionInitiatorResolver(currentUserService);

        assertThatThrownBy(resolver::resolve)
                .isInstanceOf(ResponseStatusException.class)
                .extracting("statusCode")
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }
}
