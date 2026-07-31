package com.projeto.cortex.obras.trecho;

import com.projeto.cortex.auth.CurrentUserService;
import com.projeto.cortex.obras.Obra;
import com.projeto.cortex.obras.ObraRepository;
import com.projeto.cortex.obras.trecho.ObraTrechoResponse.Origem;
import com.projeto.cortex.obras.trecho.ObraTrechoResponse.SegmentoTrecho;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ObraTrechoServiceTest {

    private static final String OBRA_ID = "obra-1";

    private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    private final ObraRepository obraRepository = mock(ObraRepository.class);
    private final CurrentUserService currentUserService = mock(CurrentUserService.class);
    private final ObraTrechoService service =
            new ObraTrechoService(jdbcTemplate, obraRepository, currentUserService);

    private Obra obraAtiva() {
        return Obra.criar(
                "CW38386", "CW38386", "4ª Intervenção", "Recapeamento SP-310",
                "Intervias", null, "Rio Claro", "SP", "SP-310",
                "ATIVA", "TESTE", null, null
        );
    }

    @SuppressWarnings("unchecked")
    private void stubQueries(
            List<SegmentoTrecho> programacao,
            List<SegmentoTrecho> controles,
            List<SegmentoTrecho> execucoes,
            LocalDateTime atualizadoEm
    ) {
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), eq(OBRA_ID)))
                .thenAnswer(invocation -> {
                    String sql = invocation.getArgument(0);
                    if (sql.contains("programacao_operacional")) {
                        return programacao;
                    }
                    if (sql.contains("rdo_controle_geometrico")) {
                        return controles;
                    }
                    return execucoes;
                });
        when(jdbcTemplate.queryForObject(
                anyString(), any(RowMapper.class), eq(OBRA_ID), eq(OBRA_ID), eq(OBRA_ID)
        )).thenReturn(atualizadoEm);
    }

    private SegmentoTrecho segmento(
            String id,
            Origem origem,
            String rdoId,
            LocalDate data,
            String sentido,
            String faixa,
            String kmInicial,
            String kmFinal,
            String extensaoM,
            String massaTonelada
    ) {
        return new SegmentoTrecho(
                id, origem, rdoId, "RDO-1", data, "Recapeamento", null,
                sentido, sentido, faixa,
                kmInicial == null ? null : new BigDecimal(kmInicial),
                kmFinal == null ? null : new BigDecimal(kmFinal),
                null, null,
                extensaoM == null ? null : new BigDecimal(extensaoM),
                null, null,
                massaTonelada == null ? null : new BigDecimal(massaTonelada),
                "VALIDADA"
        );
    }

    @Test
    void requiresWorksiteAccessBeforeReadingAnything() {
        doThrow(new ResponseStatusException(HttpStatus.FORBIDDEN, "Sem acesso à obra."))
                .when(currentUserService).requireWorksiteAccess(OBRA_ID);

        assertThatThrownBy(() -> service.buscarTrecho(OBRA_ID))
                .isInstanceOf(ResponseStatusException.class);

        verify(obraRepository, never()).findById(anyString());
    }

    @Test
    void projectsExecutedSegmentsWithRealTotalsAndKilometreRange() {
        Obra obra = obraAtiva();
        when(obraRepository.findById(OBRA_ID)).thenReturn(Optional.of(obra));
        stubQueries(
                List.of(segmento(
                        "prog-1", Origem.PROGRAMACAO, null, LocalDate.of(2026, 3, 1),
                        "SUL", "1", "171", "174", "3000", null
                )),
                List.of(
                        segmento(
                                "ctrl-1", Origem.RDO_CONTROLE, "rdo-1",
                                LocalDate.of(2026, 3, 2), "SUL", "1",
                                "172", "171", "1000", "250"
                        ),
                        segmento(
                                "ctrl-2", Origem.RDO_CONTROLE, "rdo-1",
                                LocalDate.of(2026, 3, 3), "SUL", "2",
                                "171", "170.5", "500", "120"
                        )
                ),
                List.of(),
                LocalDateTime.of(2026, 3, 3, 18, 30)
        );

        ObraTrechoResponse response = service.buscarTrecho(OBRA_ID);

        assertThat(response.obraId()).isEqualTo(obra.getId());
        assertThat(response.obraNome()).isEqualTo("Recapeamento SP-310");
        assertThat(response.rodovia()).isEqualTo("SP-310");
        assertThat(response.operavel()).isTrue();
        assertThat(response.segmentos()).hasSize(3);
        assertThat(response.kmMin()).isEqualByComparingTo(new BigDecimal("170.5"));
        assertThat(response.kmMax()).isEqualByComparingTo(new BigDecimal("174"));
        assertThat(response.sentidos()).containsExactly("SUL");
        assertThat(response.faixas()).containsExactly("1", "2");
        assertThat(response.atualizadoEm())
                .isEqualTo(LocalDateTime.of(2026, 3, 3, 18, 30));
    }

    @Test
    void summaryCountsOnlyExecutedProductionAndNotPlanning() {
        when(obraRepository.findById(OBRA_ID)).thenReturn(Optional.of(obraAtiva()));
        stubQueries(
                List.of(segmento(
                        "prog-1", Origem.PROGRAMACAO, null, LocalDate.of(2026, 3, 1),
                        "SUL", "1", "171", "174", "3000", "800"
                )),
                List.of(segmento(
                        "ctrl-1", Origem.RDO_CONTROLE, "rdo-1",
                        LocalDate.of(2026, 3, 2), "SUL", "1", "172", "171", "1000", "250"
                )),
                List.of(),
                null
        );

        ObraTrechoResponse.ResumoTrecho resumo = service.buscarTrecho(OBRA_ID).resumo();

        assertThat(resumo.totalSegmentos()).isEqualTo(2);
        assertThat(resumo.totalRdos()).isEqualTo(1);
        assertThat(resumo.extensaoTotalM()).isEqualByComparingTo(new BigDecimal("1000"));
        assertThat(resumo.massaTotalTonelada()).isEqualByComparingTo(new BigDecimal("250"));
        assertThat(resumo.primeiraExecucao()).isEqualTo(LocalDate.of(2026, 3, 2));
        assertThat(resumo.ultimaExecucao()).isEqualTo(LocalDate.of(2026, 3, 2));
    }

    @Test
    void deactivatedWorksiteStopsBeingOperableButKeepsItsHistory() {
        Obra obra = obraAtiva();
        obra.desativar();
        when(obraRepository.findById(OBRA_ID)).thenReturn(Optional.of(obra));
        stubQueries(
                List.of(),
                List.of(segmento(
                        "ctrl-1", Origem.RDO_CONTROLE, "rdo-1",
                        LocalDate.of(2026, 3, 2), "SUL", "1", "172", "171", "1500", "380"
                )),
                List.of(),
                null
        );

        ObraTrechoResponse response = service.buscarTrecho(OBRA_ID);

        assertThat(response.operavel()).isFalse();
        assertThat(response.segmentos()).hasSize(1);
        assertThat(response.resumo().extensaoTotalM())
                .isEqualByComparingTo(new BigDecimal("1500"));
    }

    @Test
    void archivedWorksiteIsNotOperable() {
        Obra obra = obraAtiva();
        obra.arquivar();
        when(obraRepository.findById(OBRA_ID)).thenReturn(Optional.of(obra));
        stubQueries(List.of(), List.of(), List.of(), null);

        assertThat(service.buscarTrecho(OBRA_ID).operavel()).isFalse();
    }

    @Test
    void reportsTheAvailablePeriodAndTheExecutedDays() {
        when(obraRepository.findById(OBRA_ID)).thenReturn(Optional.of(obraAtiva()));
        stubQueries(
                List.of(),
                List.of(
                        segmento(
                                "ctrl-1", Origem.RDO_CONTROLE, "rdo-1",
                                LocalDate.of(2026, 3, 2), "SUL", "1",
                                "172", "171", "1000", "250"
                        ),
                        segmento(
                                "ctrl-2", Origem.RDO_CONTROLE, "rdo-1",
                                LocalDate.of(2026, 3, 2), "SUL", "2",
                                "171", "170.5", "500", "120"
                        ),
                        segmento(
                                "ctrl-3", Origem.RDO_CONTROLE, "rdo-2",
                                LocalDate.of(2026, 3, 5), "SUL", "1",
                                "170.5", "170", "500", "130"
                        )
                ),
                List.of(),
                null
        );

        ObraTrechoResponse response = service.buscarTrecho(OBRA_ID);

        assertThat(response.periodoDisponivel().de()).isEqualTo(LocalDate.of(2026, 3, 2));
        assertThat(response.periodoDisponivel().ate()).isEqualTo(LocalDate.of(2026, 3, 5));
        assertThat(response.periodoAplicado().de()).isNull();
        assertThat(response.diasExecutados()).hasSize(2);
        assertThat(response.diasExecutados().get(0).data())
                .isEqualTo(LocalDate.of(2026, 3, 2));
        assertThat(response.diasExecutados().get(0).totalSegmentos()).isEqualTo(2);
        assertThat(response.diasExecutados().get(0).totalRdos()).isEqualTo(1);
        assertThat(response.diasExecutados().get(0).extensaoM())
                .isEqualByComparingTo(new BigDecimal("1500"));
        assertThat(response.diasExecutados().get(1).data())
                .isEqualTo(LocalDate.of(2026, 3, 5));
    }

    @Test
    void keepsOnlyTheSegmentsExecutedInsideTheRequestedPeriod() {
        when(obraRepository.findById(OBRA_ID)).thenReturn(Optional.of(obraAtiva()));
        stubQueries(
                List.of(),
                List.of(
                        segmento(
                                "ctrl-1", Origem.RDO_CONTROLE, "rdo-1",
                                LocalDate.of(2026, 3, 2), "SUL", "1",
                                "172", "171", "1000", "250"
                        ),
                        segmento(
                                "ctrl-2", Origem.RDO_CONTROLE, "rdo-2",
                                LocalDate.of(2026, 3, 10), "SUL", "1",
                                "171", "170", "1000", "260"
                        )
                ),
                List.of(),
                null
        );

        ObraTrechoResponse response = service.buscarTrecho(
                OBRA_ID, LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 5)
        );

        assertThat(response.segmentos()).extracting(SegmentoTrecho::id)
                .containsExactly("ctrl-1");
        assertThat(response.resumo().extensaoTotalM())
                .isEqualByComparingTo(new BigDecimal("1000"));
        // O período disponível continua descrevendo tudo que a obra registrou.
        assertThat(response.periodoDisponivel().ate())
                .isEqualTo(LocalDate.of(2026, 3, 10));
        assertThat(response.periodoAplicado().ate())
                .isEqualTo(LocalDate.of(2026, 3, 5));
    }

    @Test
    void aDatelessSegmentLeavesTheProjectionOnceAPeriodIsChosen() {
        when(obraRepository.findById(OBRA_ID)).thenReturn(Optional.of(obraAtiva()));
        stubQueries(
                List.of(),
                List.of(segmento(
                        "sem-data", Origem.RDO_CONTROLE, "rdo-1", null,
                        "SUL", "1", "172", "171", "1000", "250"
                )),
                List.of(),
                null
        );

        assertThat(service.buscarTrecho(OBRA_ID).segmentos()).hasSize(1);
        assertThat(
                service.buscarTrecho(
                        OBRA_ID, LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 5)
                ).segmentos()
        ).isEmpty();
    }

    @Test
    void rejectsAnInvertedPeriodBeforeReadingAnything() {
        assertThatThrownBy(() -> service.buscarTrecho(
                OBRA_ID, LocalDate.of(2026, 3, 10), LocalDate.of(2026, 3, 1)
        ))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("não pode ser posterior");

        verify(obraRepository, never()).findById(anyString());
    }

    @Test
    void unknownWorksiteIsNotFound() {
        when(obraRepository.findById(OBRA_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.buscarTrecho(OBRA_ID))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Obra não encontrada");
    }

    @Test
    void worksiteWithoutProductionReturnsTruthfulEmptyProjection() {
        when(obraRepository.findById(OBRA_ID)).thenReturn(Optional.of(obraAtiva()));
        stubQueries(List.of(), List.of(), List.of(), null);

        ObraTrechoResponse response = service.buscarTrecho(OBRA_ID);

        assertThat(response.segmentos()).isEmpty();
        assertThat(response.kmMin()).isNull();
        assertThat(response.kmMax()).isNull();
        assertThat(response.atualizadoEm()).isNull();
        assertThat(response.resumo().totalSegmentos()).isZero();
        assertThat(response.resumo().primeiraExecucao()).isNull();
    }

    /**
     * O mapeamento de linha é exercitado diretamente porque é onde o km textual
     * do banco vira número e onde uma unidade não linear precisa continuar nula.
     */
    @Test
    void serviceExecutionRowKeepsNonLinearQuantitiesOutOfExtension() throws Exception {
        when(obraRepository.findById(OBRA_ID)).thenReturn(Optional.of(obraAtiva()));
        List<SegmentoTrecho> capturados = new java.util.ArrayList<>();
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), eq(OBRA_ID)))
                .thenAnswer(invocation -> {
                    String sql = invocation.getArgument(0);
                    if (!sql.contains("execucao_servico_rdo")
                            || sql.contains("UNION ALL")) {
                        return List.of();
                    }
                    RowMapper<SegmentoTrecho> mapper = invocation.getArgument(1);
                    capturados.add(mapper.mapRow(execucaoRow("TON", "80"), 0));
                    capturados.add(mapper.mapRow(execucaoRow("M", "1500"), 1));
                    return List.copyOf(capturados);
                });
        when(jdbcTemplate.queryForObject(
                anyString(), any(RowMapper.class), eq(OBRA_ID), eq(OBRA_ID), eq(OBRA_ID)
        )).thenReturn(null);

        service.buscarTrecho(OBRA_ID);

        assertThat(capturados.get(0).extensaoM()).isNull();
        assertThat(capturados.get(0).kmInicial())
                .isEqualByComparingTo(new BigDecimal("309.04"));
        assertThat(capturados.get(1).extensaoM())
                .isEqualByComparingTo(new BigDecimal("1500"));
    }

    private ResultSet execucaoRow(String unidade, String quantidade) throws SQLException {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getString("id")).thenReturn("exec-1");
        when(rs.getString("rdo_id")).thenReturn("rdo-1");
        when(rs.getString("numero_rdo")).thenReturn("RDO-1");
        when(rs.getDate("data_execucao"))
                .thenReturn(java.sql.Date.valueOf(LocalDate.of(2026, 3, 2)));
        when(rs.getString("servico_nome")).thenReturn("Recapeamento");
        when(rs.getString("trecho_inicial")).thenReturn("309.04");
        when(rs.getString("trecho_final")).thenReturn("309,2");
        when(rs.getString("localizacao")).thenReturn("Pista sul");
        when(rs.getString("status_validacao")).thenReturn("VALIDADA");
        when(rs.getString("unidade_medida")).thenReturn(unidade);
        when(rs.getBigDecimal("quantidade_executada"))
                .thenReturn(new BigDecimal(quantidade));
        return rs;
    }
}
