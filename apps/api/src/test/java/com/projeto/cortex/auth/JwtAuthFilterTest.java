package com.projeto.cortex.auth;

import java.util.Optional;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class JwtAuthFilterTest {

    @Test
    void rejectsAValidTokenWhenItsCollaboratorIsNoLongerActive() throws Exception {
        JwtService jwtService = mock(JwtService.class);
        CurrentUserService currentUserService = mock(CurrentUserService.class);
        JwtAuthFilter filter = new JwtAuthFilter(jwtService, currentUserService);
        FilterChain chain = mock(FilterChain.class);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/rdos");
        MockHttpServletResponse response = new MockHttpServletResponse();
        request.addHeader("Authorization", "Bearer token-valido");
        when(jwtService.subject("token-valido")).thenReturn(Optional.of("colaborador-inativo"));
        when(currentUserService.papelAcesso("colaborador-inativo")).thenReturn(null);

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(401);
        verify(chain, never()).doFilter(request, response);
    }

    @Test
    void keepsAValidActiveCollaboratorInTheRequestContext() throws Exception {
        JwtService jwtService = mock(JwtService.class);
        CurrentUserService currentUserService = mock(CurrentUserService.class);
        JwtAuthFilter filter = new JwtAuthFilter(jwtService, currentUserService);
        FilterChain chain = mock(FilterChain.class);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/rdos");
        MockHttpServletResponse response = new MockHttpServletResponse();
        request.addHeader("Authorization", "Bearer token-valido");
        when(jwtService.subject("token-valido")).thenReturn(Optional.of("colaborador-ativo"));
        when(currentUserService.papelAcesso("colaborador-ativo")).thenReturn(PapelAcesso.BETA);

        filter.doFilter(request, response, chain);

        assertThat(request.getAttribute(CurrentUserService.REQUEST_ATTRIBUTE_USER_ID))
                .isEqualTo("colaborador-ativo");
        verify(chain).doFilter(request, response);
    }
}
