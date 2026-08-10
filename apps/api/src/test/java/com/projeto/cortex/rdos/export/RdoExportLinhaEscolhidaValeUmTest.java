package com.projeto.cortex.rdos.export;

import static com.projeto.cortex.rdos.export.RdoExportTestFixtures.populatedRdo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.projeto.cortex.rdos.RdoQueryService;
import com.projeto.cortex.rdos.RdoResponse;
import java.math.BigDecimal;
import java.time.LocalTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

/**
 * O RDO montado pela escolha em lista dupla, exportado pelo servidor.
 *
 * <p>A tela põe uma pessoa por linha e uma máquina por linha, sem pedir
 * quantidade e sem pedir vínculo. O servidor tomava o nulo por zero e imprimia
 * "0" no lugar da frente inteira, e recusava a máquina por vínculo em branco.
 * Estes testes prendem a regra unificada com a do aparelho: a linha que se
 * identifica sozinha vale um; a que não identifica ninguém continua exigindo o
 * número.
 */
class RdoExportLinhaEscolhidaValeUmTest {

    private static final List<RdoResponse.MaoObraItem> ESCOLHIDOS = List.of(
            new RdoResponse.MaoObraItem(
                    "mo-1", "col-ana", "Ana Apontadora", "Apontador",
                    "", null, null, null, null, null
            ),
            new RdoResponse.MaoObraItem(
                    "mo-2", "col-bruno", "Bruno Motorista", "Motorista",
                    "", null, null, null, null, null
            ),
            new RdoResponse.MaoObraItem(
                    "mo-3", "col-carla", "Carla Motorista", "Motorista",
                    "", null, null, null, null, null
            )
    );

    private static final List<RdoResponse.EquipamentoItem> DO_PARQUE = List.of(
            new RdoResponse.EquipamentoItem(
                    "eq-1", "asset-7", "FRE004", "Fresadora", "FRESADORA",
                    "", null, LocalTime.of(8, 0), LocalTime.of(16, 0), null
            )
    );

    private RdoResponse comLinhas(
            List<RdoResponse.MaoObraItem> maoObra,
            List<RdoResponse.EquipamentoItem> equipamentos
    ) {
        RdoResponse base = populatedRdo("rdo-70", "RDO-0070");
        return new RdoResponse(
                base.id(), base.obraId(), base.programacaoId(),
                base.numeroRdo(), base.dataRdo(), base.previousRdoId(),
                base.creationContextVersion(), base.clientMutationId(),
                base.versaoEntidade(), base.apontadorColaboradorId(),
                base.diaSemana(), base.cliente(), base.contrato(),
                base.rodovia(), base.cidade(), base.uf(),
                base.kmInicialProgramado(), base.kmFinalProgramado(),
                base.kmInicialInterditado(), base.kmFinalInterditado(),
                base.turno(), base.horaInicio(), base.horaFim(),
                base.condicaoManha(), base.condicaoTarde(),
                base.condicaoNoite(), base.condicaoTrabalho(),
                base.pluviometriaMm(), base.status(), base.observacoes(),
                base.preenchidoPor(), base.apontadorRdo(),
                base.encarregadoObra(), base.fiscalizacaoCampo(),
                maoObra, equipamentos, base.materiais(),
                base.controlesGeometricos(), base.servicosExecutados(),
                base.alocacoesColaboradores(), base.attachments()
        );
    }

    private RdoExportAggregate load(RdoResponse rdo) {
        RdoQueryService queryService = mock(RdoQueryService.class);
        RdoExportWorksiteReader worksiteReader =
                mock(RdoExportWorksiteReader.class);
        when(queryService.buscarPorId(rdo.id())).thenReturn(rdo);
        when(worksiteReader.read("obra-7")).thenReturn(
                new RdoExportWorksiteReader.Worksite("Obra Norte", "CW-007")
        );
        return new RdoExportAggregateFactory(queryService, worksiteReader)
                .load(rdo.id());
    }

    @Test
    void contaUmaPessoaPorLinhaEscolhidaAgrupadaPorCargo() {
        RdoExportAggregate aggregate = load(comLinhas(ESCOLHIDOS, DO_PARQUE));

        assertThat(aggregate.workforce())
                .extracting("role", "quantity")
                .containsExactly(
                        tuple("Apontador", BigDecimal.ONE),
                        tuple("Motorista", new BigDecimal("2"))
                );
    }

    @Test
    void trataAMaquinaComCadastroComoPropriaEContaUma() {
        RdoExportAggregate aggregate = load(comLinhas(ESCOLHIDOS, DO_PARQUE));

        assertThat(aggregate.equipment()).singleElement()
                .satisfies(item -> {
                    assertThat(item.tipoVinculo()).isEqualTo("PROPRIO");
                    assertThat(item.quantidade())
                            .isEqualByComparingTo(BigDecimal.ONE);
                });
    }

    @Test
    void aindaExigeQuantidadeNaLinhaDeCargoSemPessoa() {
        RdoResponse rdo = comLinhas(
                List.of(new RdoResponse.MaoObraItem(
                        "mo-avulsa", null, null, "Rasteleiro", "",
                        null, null, null, null, null
                )),
                DO_PARQUE
        );

        assertThatThrownBy(() -> load(rdo))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("quantidade");
    }

    @Test
    void aindaExigeVinculoNaMaquinaSemCadastro() {
        RdoResponse rdo = comLinhas(
                ESCOLHIDOS,
                List.of(new RdoResponse.EquipamentoItem(
                        "eq-avulso", null, null, "Rompedor alugado",
                        "ROMPEDOR", "", new BigDecimal("1"), null, null, null
                ))
        );

        assertThatThrownBy(() -> load(rdo))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("vínculo de equipamento");
    }
}
