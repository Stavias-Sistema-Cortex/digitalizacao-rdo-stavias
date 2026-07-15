package com.projeto.cortex.intelligence.stavia;

import com.projeto.cortex.auth.CurrentUserService;
import com.projeto.cortex.auth.PapelAcesso;
import com.projeto.cortex.intelligence.stavia.access.CortexStaviaAccessPolicy;
import com.projeto.cortex.financeiro.access.FinancialAccessService;
import com.projeto.cortex.financeiro.access.FinancialPermission;
import com.projeto.cortex.mensagens.domain.MessageWorksiteAccessService;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Garante que a Stav.IA herda exatamente a autorização do usuário: Alfa e Beta
 * podem consultar; Beta só acessa suas obras; usuário sem papel válido é negado
 * antes de qualquer dado chegar ao modelo de linguagem.
 */
class CortexStaviaAccessPolicyTest {

    private final CurrentUserService currentUserService =
            mock(CurrentUserService.class);
    private final FinancialAccessService financialAccessService =
            mock(FinancialAccessService.class);
    private final MessageWorksiteAccessService messageWorksiteAccessService =
            mock(MessageWorksiteAccessService.class);
    private final CortexStaviaAccessPolicy policy =
            new CortexStaviaAccessPolicy(
                    currentUserService,
                    financialAccessService,
                    messageWorksiteAccessService
            );

    @Test
    void alfaEBetaPodemConsultar() {
        when(currentUserService.papelAcesso("alfa")).thenReturn(PapelAcesso.ALFA);
        when(currentUserService.papelAcesso("beta")).thenReturn(PapelAcesso.BETA);

        assertThat(policy.permissionsFor("alfa"))
                .containsExactly(StaviaEngine.REQUIRED_PERMISSION);
        assertThat(policy.permissionsFor("beta"))
                .containsExactly(StaviaEngine.REQUIRED_PERMISSION);
    }

    @Test
    void usuarioSemPapelNaoRecebePermissao() {
        when(currentUserService.papelAcesso("fantasma")).thenReturn(null);

        assertThat(policy.permissionsFor("fantasma")).isEmpty();
        assertThat(policy.permissionsFor(null)).isEmpty();
        assertThat(policy.permissionsFor("  ")).isEmpty();
    }

    @Test
    void acessoAObraSegueOModeloDeAutorizacao() {
        when(currentUserService.podeAcessarObra("beta", "obra-1")).thenReturn(true);
        when(currentUserService.podeAcessarObra("beta", "obra-2")).thenReturn(false);

        assertThat(policy.canAccessWorksite("beta", "obra-1")).isTrue();
        assertThat(policy.canAccessWorksite("beta", "obra-2")).isFalse();
    }

    @Test
    void acessoFinanceiroExigeConcessaoNaObraExata() {
        when(financialAccessService.hasPermission(
                "beta",
                "obra-1",
                FinancialPermission.FINANCEIRO_VISUALIZAR
        )).thenReturn(true);
        when(financialAccessService.hasPermission(
                "beta",
                "obra-2",
                FinancialPermission.FINANCEIRO_VISUALIZAR
        )).thenReturn(false);

        assertThat(policy.canAccessFinancial("beta", "obra-1")).isTrue();
        assertThat(policy.canAccessFinancial("beta", "obra-2")).isFalse();
    }

    @Test
    void acessoAMensagensExigeParticipacaoNaObraExata() {
        when(messageWorksiteAccessService.canRead("beta", "obra-1"))
                .thenReturn(true);
        when(messageWorksiteAccessService.canRead("beta", "obra-2"))
                .thenReturn(false);

        assertThat(policy.canAccessMessages("beta", "obra-1")).isTrue();
        assertThat(policy.canAccessMessages("beta", "obra-2")).isFalse();
    }
}
