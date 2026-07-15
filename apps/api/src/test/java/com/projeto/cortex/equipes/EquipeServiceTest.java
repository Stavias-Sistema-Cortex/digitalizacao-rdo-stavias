package com.projeto.cortex.equipes;

import com.projeto.cortex.auth.CurrentUserService;
import com.projeto.cortex.obras.VinculoColaboradorObraService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EquipeServiceTest {

    private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    private final CurrentUserService currentUserService = mock(CurrentUserService.class);
    private final EquipeMemoryPublisher memoryPublisher = mock(EquipeMemoryPublisher.class);
    private final VinculoColaboradorObraService vinculoService =
            mock(VinculoColaboradorObraService.class);
    private final EquipeService service = new EquipeService(
            jdbcTemplate,
            currentUserService,
            memoryPublisher,
            vinculoService
    );

    @BeforeEach
    void allowAlfaGlobalScopeByDefault() {
        when(currentUserService.requireUserId()).thenReturn("alfa-1");
        when(currentUserService.allowedObraIds("alfa-1")).thenReturn(Optional.empty());
    }

    @Test
    void betaWithoutAuthorizedWorksitesReturnsEmptyPageWithoutGlobalQuery() {
        when(currentUserService.requireUserId()).thenReturn("beta-1");
        when(currentUserService.allowedObraIds("beta-1"))
                .thenReturn(Optional.of(Set.of()));

        EquipePageResponse response = service.listar(new EquipeFilter(
                null,
                null,
                null,
                null,
                null,
                1,
                25
        ));

        assertThat(response.items()).isEmpty();
        assertThat(response.totalElements()).isZero();
        verify(jdbcTemplate, never()).query(any(String.class), any(RowMapper.class), any(Object[].class));
    }

    @Test
    void alfaCreatesTeamAndInitialWorksiteLinkAtomically() {
        LocalDateTime startedAt = LocalDateTime.of(2026, 7, 15, 7, 30);
        EquipeCreateRequest request = new EquipeCreateRequest(
                "equipe-1",
                "obra-1",
                "Equipe Norte",
                "Pavimentação do trecho norte",
                startedAt
        );
        EquipeResponse expected = new EquipeResponse(
                "equipe-1",
                "obra-1",
                "Duplicação SP-000",
                "Equipe Norte",
                "Pavimentação do trecho norte",
                "ATIVA",
                startedAt,
                null,
                0,
                startedAt,
                startedAt,
                List.of()
        );

        when(currentUserService.requireUserId()).thenReturn("alfa-1");
        when(jdbcTemplate.queryForObject(
                contains("FROM obra"),
                eq(Integer.class),
                eq("obra-1")
        )).thenReturn(1);
        when(jdbcTemplate.query(
                contains("FROM equipe WHERE id"),
                any(RowMapper.class),
                eq("equipe-1")
        )).thenReturn(List.of());
        when(jdbcTemplate.query(
                contains("FROM equipe e"),
                any(RowMapper.class),
                eq("equipe-1")
        )).thenReturn(List.of(expected));
        when(jdbcTemplate.query(
                contains("FROM equipe_membro em"),
                any(RowMapper.class),
                eq("equipe-1")
        )).thenReturn(List.of());

        EquipeResponse created = service.criar(request);

        assertThat(created).isEqualTo(expected);
        verify(currentUserService).requireAlfa();
        verify(currentUserService, atLeastOnce()).requireWorksiteAccess("obra-1");
        verify(jdbcTemplate).update(
                contains("versao_linha"),
                eq("equipe-1"),
                eq("obra-1"),
                eq("Equipe Norte"),
                eq("Pavimentação do trecho norte"),
                eq(startedAt),
                eq("alfa-1"),
                eq("alfa-1")
        );
        verify(jdbcTemplate).update(
                contains("INSERT INTO equipe_obra"),
                any(String.class),
                eq("equipe-1"),
                eq("obra-1"),
                eq(startedAt),
                eq("alfa-1")
        );
        verify(memoryPublisher).equipeCriada(expected, "alfa-1");
        verify(vinculoService, never()).vincular(any(), any(), any(), any());
    }

    @Test
    void staleTeamUpdateIsRejectedBeforeAnyWrite() {
        when(currentUserService.requireUserId()).thenReturn("alfa-1");
        when(jdbcTemplate.query(
                contains("FROM equipe WHERE id"),
                any(RowMapper.class),
                eq("equipe-1")
        )).thenReturn(List.of(new EquipeService.TeamIdentity(
                "equipe-1",
                "obra-1",
                "Equipe Norte",
                "ATIVA",
                4
        )));

        EquipeUpdateRequest request = new EquipeUpdateRequest(
                "Equipe Norte B",
                "Novo escopo",
                "ATIVA",
                LocalDateTime.of(2026, 7, 15, 7, 30),
                null,
                3L,
                "Ajuste operacional"
        );

        assertThatThrownBy(() -> service.atualizar("equipe-1", request))
                .isInstanceOfSatisfying(ResponseStatusException.class, exception -> {
                    assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(exception.getReason()).isEqualTo("Conflito de versão da equipe.");
                });

        verify(jdbcTemplate, never()).update(contains("UPDATE equipe"), any(Object[].class));
        verify(memoryPublisher, never()).equipeAtualizada(any(), any(), any(), anyLong(), any());
    }

    @Test
    void addingMemberDoesNotGrantWorksiteAccessWithoutExplicitConfirmation() {
        LocalDateTime startedAt = LocalDateTime.of(2026, 7, 15, 8, 0);
        EquipeResponse team = new EquipeResponse(
                "equipe-1", "obra-1", "Duplicação SP-000", "Equipe Norte",
                null, "ATIVA", startedAt, null, 0,
                startedAt, startedAt, List.of()
        );
        EquipeMemberResponse member = new EquipeMemberResponse(
                "participacao-1",
                "equipe-1",
                "colab-1",
                "Ana Operadora",
                "funcao-1",
                "OPERADOR",
                "Operador de máquinas",
                false,
                "ATIVO",
                startedAt,
                null,
                null,
                1,
                startedAt,
                startedAt
        );
        EquipeMemberRequest request = new EquipeMemberRequest(
                "participacao-1",
                "colab-1",
                "funcao-1",
                false,
                startedAt,
                null,
                null,
                false
        );

        when(currentUserService.requireUserId()).thenReturn("alfa-1");
        when(jdbcTemplate.query(
                contains("FROM equipe e"),
                any(RowMapper.class),
                eq("equipe-1")
        )).thenReturn(List.of(team));
        when(jdbcTemplate.query(
                contains("WHERE em.equipe_id = ?"),
                any(RowMapper.class),
                eq("equipe-1")
        )).thenReturn(List.of());
        when(jdbcTemplate.queryForObject(
                contains("FROM funcao_operacional"),
                eq(Integer.class),
                eq("funcao-1")
        )).thenReturn(1);
        when(jdbcTemplate.queryForObject(
                contains("FROM colaborador"),
                eq(Integer.class),
                eq("colab-1")
        )).thenReturn(1);
        when(jdbcTemplate.query(
                contains("em.colaborador_id = ?"),
                any(RowMapper.class),
                eq("equipe-1"),
                eq("colab-1")
        )).thenReturn(List.of());
        when(jdbcTemplate.query(
                contains("WHERE em.id = ?"),
                any(RowMapper.class),
                eq("participacao-1")
        )).thenReturn(List.of(member));

        EquipeMemberResponse created = service.adicionarMembro("equipe-1", request);

        assertThat(created).isEqualTo(member);
        verify(jdbcTemplate).update(
                contains("versao_linha"),
                eq("participacao-1"),
                eq("equipe-1"),
                eq("colab-1"),
                eq("funcao-1"),
                eq(false),
                eq(startedAt),
                eq("alfa-1"),
                eq("alfa-1")
        );
        verify(vinculoService, never()).vincular(any(), any(), any(), any());
        verify(memoryPublisher).membroAdicionado(team, member, "alfa-1");
    }

    @Test
    void endingMemberPreservesTemporalRowAndPublishesTransition() {
        LocalDateTime startedAt = LocalDateTime.of(2026, 7, 1, 8, 0);
        LocalDateTime endedAt = LocalDateTime.of(2026, 7, 15, 9, 0);
        EquipeMemberResponse before = new EquipeMemberResponse(
                "participacao-1", "equipe-1", "colab-1", "Ana Operadora",
                "funcao-1", "OPERADOR", "Operador de máquinas", false,
                "ATIVO", startedAt, null, null, 3,
                startedAt, startedAt
        );
        EquipeMemberResponse after = new EquipeMemberResponse(
                "participacao-1", "equipe-1", "colab-1", "Ana Operadora",
                "funcao-1", "OPERADOR", "Operador de máquinas", false,
                "ENCERRADO", startedAt, endedAt, "Realocação", 4,
                startedAt, endedAt
        );
        EquipeResponse team = new EquipeResponse(
                "equipe-1", "obra-1", "Duplicação SP-000", "Equipe Norte",
                null, "ATIVA", startedAt, null, 2,
                startedAt, startedAt, List.of(before)
        );

        when(currentUserService.requireUserId()).thenReturn("alfa-1");
        when(jdbcTemplate.query(
                contains("FROM equipe e"),
                any(RowMapper.class),
                eq("equipe-1")
        )).thenReturn(List.of(team));
        when(jdbcTemplate.query(
                contains("WHERE em.equipe_id = ?"),
                any(RowMapper.class),
                eq("equipe-1")
        )).thenReturn(List.of(before));
        when(jdbcTemplate.query(
                contains("WHERE em.id = ?"),
                any(RowMapper.class),
                eq("participacao-1")
        )).thenReturn(List.of(before), List.of(after));
        when(jdbcTemplate.update(
                contains("UPDATE equipe_membro"),
                any(Object[].class)
        )).thenReturn(1);

        EquipeMemberResponse ended = service.encerrarMembro(
                "equipe-1",
                "participacao-1",
                3L,
                "Realocação",
                endedAt
        );

        assertThat(ended).isEqualTo(after);
        verify(jdbcTemplate).update(
                contains("SET status = 'ENCERRADO'"),
                eq(endedAt),
                eq("Realocação"),
                eq("alfa-1"),
                eq("alfa-1"),
                eq("participacao-1"),
                eq(3L)
        );
        verify(memoryPublisher).membroEncerrado(
                team, before, after, "alfa-1", 3L, "Realocação"
        );
        verify(jdbcTemplate, never()).update(contains("DELETE"), any(Object[].class));
    }

    @Test
    void archivingTeamEndsActiveLinksWithoutDeletingHistory() {
        LocalDateTime startedAt = LocalDateTime.of(2026, 7, 1, 8, 0);
        LocalDateTime archivedAt = LocalDateTime.of(2026, 7, 15, 10, 0);
        EquipeMemberResponse member = new EquipeMemberResponse(
                "participacao-1", "equipe-1", "colab-1", "Ana Operadora",
                "funcao-1", "OPERADOR", "Operador de máquinas", true,
                "ATIVO", startedAt, null, null, 2,
                startedAt, startedAt
        );
        EquipeResponse before = new EquipeResponse(
                "equipe-1", "obra-1", "Duplicação SP-000", "Equipe Norte",
                null, "ATIVA", startedAt, null, 5,
                startedAt, startedAt, List.of(member)
        );
        EquipeResponse after = new EquipeResponse(
                "equipe-1", "obra-1", "Duplicação SP-000", "Equipe Norte",
                null, "ARQUIVADA", startedAt, archivedAt, 6,
                startedAt, archivedAt, List.of()
        );

        when(currentUserService.requireUserId()).thenReturn("alfa-1");
        when(jdbcTemplate.query(
                contains("FROM equipe e"),
                any(RowMapper.class),
                eq("equipe-1")
        )).thenReturn(List.of(before), List.of(after));
        when(jdbcTemplate.query(
                contains("WHERE em.equipe_id = ?"),
                any(RowMapper.class),
                eq("equipe-1")
        )).thenReturn(List.of(member), List.of());
        when(jdbcTemplate.update(
                contains("UPDATE equipe\n"),
                any(Object[].class)
        )).thenReturn(1);

        EquipeResponse archived = service.arquivar(
                "equipe-1",
                5L,
                "Fim do trecho",
                archivedAt
        );

        assertThat(archived).isEqualTo(after);
        verify(jdbcTemplate).update(
                contains("UPDATE equipe_obra"),
                eq(archivedAt),
                eq("Fim do trecho"),
                eq("alfa-1"),
                eq("equipe-1")
        );
        verify(jdbcTemplate).update(
                contains("UPDATE equipe_membro"),
                eq(archivedAt),
                eq("Fim do trecho"),
                eq("alfa-1"),
                eq("alfa-1"),
                eq("equipe-1")
        );
        verify(memoryPublisher).equipeArquivada(
                before, after, "alfa-1", 5L, "Fim do trecho"
        );
        verify(jdbcTemplate, never()).update(contains("DELETE"), any(Object[].class));
    }

    @Test
    void updatingMemberChangesFunctionWithOptimisticVersion() {
        LocalDateTime startedAt = LocalDateTime.of(2026, 7, 1, 8, 0);
        EquipeResponse team = new EquipeResponse(
                "equipe-1", "obra-1", "Duplicação SP-000", "Equipe Norte",
                null, "ATIVA", startedAt, null, 2,
                startedAt, startedAt, List.of()
        );
        EquipeMemberResponse before = new EquipeMemberResponse(
                "participacao-1", "equipe-1", "colab-1", "Ana Operadora",
                "funcao-1", "OPERADOR", "Operador", false,
                "ATIVO", startedAt, null, null, 3,
                startedAt, startedAt
        );
        EquipeMemberResponse after = new EquipeMemberResponse(
                "participacao-1", "equipe-1", "colab-1", "Ana Operadora",
                "funcao-2", "LIDER", "Líder de frente", true,
                "ATIVO", startedAt, null, null, 4,
                startedAt, LocalDateTime.of(2026, 7, 15, 11, 0)
        );
        EquipeMemberRequest request = new EquipeMemberRequest(
                null, "colab-1", "funcao-2", true,
                startedAt, 3L, "Mudança de responsabilidade", false
        );

        when(currentUserService.requireUserId()).thenReturn("alfa-1");
        when(jdbcTemplate.query(
                contains("FROM equipe e"), any(RowMapper.class), eq("equipe-1")
        )).thenReturn(List.of(team));
        when(jdbcTemplate.query(
                contains("WHERE em.equipe_id = ?"), any(RowMapper.class), eq("equipe-1")
        )).thenReturn(List.of());
        when(jdbcTemplate.query(
                contains("WHERE em.id = ?"), any(RowMapper.class), eq("participacao-1")
        )).thenReturn(List.of(before), List.of(after));
        when(jdbcTemplate.queryForObject(
                contains("FROM funcao_operacional"), eq(Integer.class), eq("funcao-2")
        )).thenReturn(1);
        when(jdbcTemplate.update(
                contains("UPDATE equipe_membro"), any(Object[].class)
        )).thenReturn(1);

        EquipeMemberResponse updated = service.atualizarMembro(
                "equipe-1", "participacao-1", request
        );

        assertThat(updated).isEqualTo(after);
        verify(memoryPublisher).membroAtualizado(
                team, before, after, "alfa-1", 3L, "Mudança de responsabilidade"
        );
    }

    @Test
    void alfaCreatesConfigurableOperationalFunctionWithoutSeedData() {
        LocalDateTime createdAt = LocalDateTime.of(2026, 7, 15, 11, 30);
        FuncaoOperacionalRequest request = new FuncaoOperacionalRequest(
                "funcao-1",
                "operador_pavimentadora",
                "Operador de pavimentadora",
                "Opera a vibroacabadora da frente",
                true,
                10,
                null
        );
        FuncaoOperacionalResponse expected = new FuncaoOperacionalResponse(
                "funcao-1",
                "OPERADOR_PAVIMENTADORA",
                "Operador de pavimentadora",
                "Opera a vibroacabadora da frente",
                true,
                10,
                1,
                createdAt,
                createdAt
        );

        when(currentUserService.requireUserId()).thenReturn("alfa-1");
        when(jdbcTemplate.query(
                contains("WHERE id = ? OR codigo = ?"),
                any(RowMapper.class),
                eq("funcao-1"),
                eq("OPERADOR_PAVIMENTADORA")
        )).thenReturn(List.of());
        when(jdbcTemplate.query(
                contains("WHERE id = ?"),
                any(RowMapper.class),
                eq("funcao-1")
        )).thenReturn(List.of(expected));

        FuncaoOperacionalResponse created = service.criarFuncao(request);

        assertThat(created).isEqualTo(expected);
        verify(jdbcTemplate).update(
                contains("versao_linha"),
                eq("funcao-1"),
                eq("OPERADOR_PAVIMENTADORA"),
                eq("Operador de pavimentadora"),
                eq("Opera a vibroacabadora da frente"),
                eq(true),
                eq(10),
                eq("alfa-1"),
                eq("alfa-1")
        );
        verify(memoryPublisher).funcaoCriada(expected, "alfa-1");
    }

    @Test
    void betaCanReadTeamThroughAnyActiveAuthorizedWorksite() {
        LocalDateTime startedAt = LocalDateTime.of(2026, 7, 1, 8, 0);
        EquipeResponse team = new EquipeResponse(
                "equipe-1", "obra-principal", "Obra principal", "Equipe itinerante",
                null, "ATIVA", startedAt, null, 2,
                startedAt, startedAt, List.of()
        );

        when(currentUserService.requireUserId()).thenReturn("beta-1");
        when(currentUserService.allowedObraIds("beta-1"))
                .thenReturn(Optional.of(Set.of("obra-secundaria")));
        when(jdbcTemplate.query(
                contains("FROM equipe e"), any(RowMapper.class), eq("equipe-1")
        )).thenReturn(List.of(team));
        when(jdbcTemplate.queryForObject(
                contains("FROM equipe_obra"),
                eq(Integer.class),
                eq("equipe-1"),
                eq("obra-secundaria")
        )).thenReturn(1);
        when(jdbcTemplate.query(
                contains("WHERE em.equipe_id = ?"), any(RowMapper.class), eq("equipe-1")
        )).thenReturn(List.of());

        EquipeResponse response = service.buscarPorId("equipe-1");

        assertThat(response).isEqualTo(team);
        verify(currentUserService, never()).requireWorksiteAccess("obra-principal");
    }

    @Test
    void betaCannotReadTeamWithoutAnActiveAuthorizedWorksiteIntersection() {
        LocalDateTime startedAt = LocalDateTime.of(2026, 7, 1, 8, 0);
        EquipeResponse team = new EquipeResponse(
                "equipe-1", "obra-principal", "Obra principal", "Equipe restrita",
                null, "ATIVA", startedAt, null, 2,
                startedAt, startedAt, List.of()
        );

        when(currentUserService.requireUserId()).thenReturn("beta-1");
        when(currentUserService.allowedObraIds("beta-1"))
                .thenReturn(Optional.of(Set.of("obra-beta")));
        when(jdbcTemplate.query(
                contains("FROM equipe e"), any(RowMapper.class), eq("equipe-1")
        )).thenReturn(List.of(team));
        when(jdbcTemplate.queryForObject(
                contains("FROM equipe_obra"),
                eq(Integer.class),
                eq("equipe-1"),
                eq("obra-beta")
        )).thenReturn(0);

        assertThatThrownBy(() -> service.buscarPorId("equipe-1"))
                .isInstanceOfSatisfying(ResponseStatusException.class, exception -> {
                    assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
                    assertThat(exception.getReason()).isEqualTo("Acesso negado à equipe.");
                });

        verify(jdbcTemplate, never()).query(
                contains("FROM equipe_membro em"),
                any(RowMapper.class),
                any(Object[].class)
        );
    }

    @Test
    void alfaAssociatesTeamWithAnotherWorksiteAsTemporalVersionedLink() {
        LocalDateTime startedAt = LocalDateTime.of(2026, 7, 15, 12, 0);
        EquipeResponse team = new EquipeResponse(
                "equipe-1", "obra-principal", "Obra principal", "Equipe móvel",
                null, "ATIVA", startedAt, null, 2,
                startedAt, startedAt, List.of()
        );
        EquipeWorksiteResponse link = new EquipeWorksiteResponse(
                "alocacao-2", "equipe-1", "obra-secundaria", "Obra secundária",
                "ATIVO", startedAt, null, null, 1,
                startedAt, startedAt
        );
        EquipeWorksiteRequest request = new EquipeWorksiteRequest(
                "alocacao-2", "obra-secundaria", startedAt
        );

        when(jdbcTemplate.query(
                contains("FROM equipe e"), any(RowMapper.class), eq("equipe-1")
        )).thenReturn(List.of(team));
        when(jdbcTemplate.query(
                contains("WHERE em.equipe_id = ?"), any(RowMapper.class), eq("equipe-1")
        )).thenReturn(List.of());
        when(jdbcTemplate.queryForObject(
                contains("FROM obra"), eq(Integer.class), eq("obra-secundaria")
        )).thenReturn(1);
        when(jdbcTemplate.query(
                contains("eo.obra_id = ?"),
                any(RowMapper.class),
                eq("equipe-1"),
                eq("obra-secundaria")
        )).thenReturn(List.of());
        when(jdbcTemplate.query(
                contains("WHERE eo.id = ?"),
                any(RowMapper.class),
                eq("alocacao-2")
        )).thenReturn(List.of(link));

        EquipeWorksiteResponse created = service.adicionarObra("equipe-1", request);

        assertThat(created).isEqualTo(link);
        verify(jdbcTemplate).update(
                contains("versao_linha"),
                eq("alocacao-2"),
                eq("equipe-1"),
                eq("obra-secundaria"),
                eq(startedAt),
                eq("alfa-1")
        );
        verify(memoryPublisher).obraAssociada(team, link, "alfa-1");
    }
}
