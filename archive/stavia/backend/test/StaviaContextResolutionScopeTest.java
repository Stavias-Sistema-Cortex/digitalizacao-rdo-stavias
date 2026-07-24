package com.projeto.cortex.intelligence.stavia;

import com.projeto.cortex.intelligence.stavia.access.StaviaAccessPolicy;
import com.projeto.cortex.intelligence.stavia.context.StaviaContextResolution;
import com.projeto.cortex.intelligence.stavia.context.StaviaContextResolutionService;
import com.projeto.cortex.intelligence.stavia.model.StaviaQuestion;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Garante que o resolvedor de contexto da Stav.IA respeita o escopo do usuário:
 * mesmo informando o id exato de uma obra, um usuário sem acesso não a resolve
 * (não confirma existência nem vaza o rótulo). Com acesso, resolve normalmente.
 */
class StaviaContextResolutionScopeTest {

    private static final String OBRA_ID =
            "11111111-1111-1111-1111-111111111111";

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final StaviaAccessPolicy accessPolicy = mock(StaviaAccessPolicy.class);
    private final StaviaContextResolutionService service =
            new StaviaContextResolutionService(jdbc, accessPolicy);

    @SuppressWarnings("unchecked")
    private void obraEncontradaPorId() {
        when(jdbc.query(
                contains("FROM obra"),
                any(RowMapper.class),
                eq(OBRA_ID)
        )).thenReturn(List.of(
                StaviaContextResolution.resolved(OBRA_ID, "Obra Restrita")
        ));
    }

    @Test
    void naoResolveObraQuandoUsuarioNaoTemAcesso() {
        obraEncontradaPorId();
        when(accessPolicy.canAccessWorksite("beta", OBRA_ID)).thenReturn(false);

        StaviaContextResolution resolucao = service.resolve(
                new StaviaQuestion("Quero os dados da obra " + OBRA_ID, "beta", null)
        );

        assertThat(resolucao.found()).isFalse();
        assertThat(resolucao.ambiguous()).isFalse();
    }

    @Test
    void resolveObraQuandoUsuarioTemAcesso() {
        obraEncontradaPorId();
        when(accessPolicy.canAccessWorksite("alfa", OBRA_ID)).thenReturn(true);

        StaviaContextResolution resolucao = service.resolve(
                new StaviaQuestion("Quero os dados da obra " + OBRA_ID, "alfa", null)
        );

        assertThat(resolucao.found()).isTrue();
        assertThat(resolucao.obraId()).isEqualTo(OBRA_ID);
    }
}
