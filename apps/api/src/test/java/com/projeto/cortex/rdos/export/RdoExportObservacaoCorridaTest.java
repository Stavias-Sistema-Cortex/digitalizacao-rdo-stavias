package com.projeto.cortex.rdos.export;

import static com.projeto.cortex.rdos.export.RdoExportTestFixtures.emptyRdo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.projeto.cortex.rdos.RdoQueryService;
import com.projeto.cortex.rdos.RdoResponse;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * O parágrafo corrido de observação sai em XLSX e em PDF.
 *
 * <p>Quem preenche o RDO escreve a observação de uma vez, sem apertar Enter. A
 * conferência de legibilidade media a linha digitada em vez da desenhada, e
 * recusava com 422 qualquer parágrafo passando de cem caracteres — mesmo o que
 * ocupa duas das seis linhas da caixa. Como a conferência é do agregado, comum
 * aos dois renderizadores, o RDO ficava sem os dois arquivos ao mesmo tempo:
 * era esse o "não consigo exportar nem PDF nem XLSX".
 *
 * <p>O RDO aqui é o do relato — sem trechos, sem equipamentos, só o cabeçalho e
 * a observação.
 */
class RdoExportObservacaoCorridaTest {

    private static final String PARAGRAFO =
            "Frente liberada pela fiscalizacao apos a chuva da madrugada; "
                    + "equipe deslocada para o km 10+400 e o servico foi "
                    + "retomado sem intercorrencias ate o fim do turno.";

    private RdoQueryService queryService;
    private RdoXlsxExportService xlsxService;
    private RdoPdfExportService pdfService;

    @BeforeEach
    void setUp() {
        queryService = mock(RdoQueryService.class);
        RdoExportWorksiteReader worksiteReader =
                mock(RdoExportWorksiteReader.class);
        when(worksiteReader.read(anyString())).thenReturn(
                new RdoExportWorksiteReader.Worksite("Obra Norte", "CW386")
        );
        RdoExportAggregateFactory factory =
                new RdoExportAggregateFactory(queryService, worksiteReader);
        xlsxService = new RdoXlsxExportService(factory);
        pdfService = new RdoPdfExportService(factory);
    }

    @Test
    void exportsBothFormatsForAParagraphLongerThanOneDrawnLine() {
        assertThat(PARAGRAFO.length()).isGreaterThan(100);
        givenObservations("rdo-0010", PARAGRAFO);

        RdoExportFile xlsx = xlsxService.export("rdo-0010");
        RdoExportFile pdf = pdfService.export("rdo-0010");

        assertThat(xlsx.content()).startsWith(new byte[] {'P', 'K'});
        assertThat(xlsx.filename()).endsWith(".xlsx");
        assertThat(pdf.content()).startsWith(
                "%PDF-".getBytes(StandardCharsets.US_ASCII)
        );
        assertThat(pdf.filename()).endsWith(".pdf");
    }

    /**
     * O teto subiu, e subir teto não pode derrubar quem já passava.
     *
     * <p>O contorno antigo — seis linhas de cem caracteres — é o pior caso que
     * a regra anterior aceitava. Alargar a linha e somar linhas só relaxa a
     * conta, nunca aperta, então o que exportava antes exporta agora; e a folga
     * nova, aqui exercitada logo acima do teto velho, também sai.
     */
    @Test
    void keepsExportingWhatTheOldCeilingAllowedAndThenSome() {
        // Seis linhas que o rótulo "RDO: " completa em exatos cem caracteres.
        givenObservations("rdo-teto-antigo", ("y".repeat(95) + "\n").repeat(6));
        assertThat(xlsxService.export("rdo-teto-antigo").content())
                .startsWith(new byte[] {'P', 'K'});
        assertThat(pdfService.export("rdo-teto-antigo").content()).startsWith(
                "%PDF-".getBytes(StandardCharsets.US_ASCII)
        );

        givenObservations("rdo-folga-nova", (PARAGRAFO + "\n").repeat(3));
        assertThat(xlsxService.export("rdo-folga-nova").content())
                .startsWith(new byte[] {'P', 'K'});
        assertThat(pdfService.export("rdo-folga-nova").content()).startsWith(
                "%PDF-".getBytes(StandardCharsets.US_ASCII)
        );
    }

    @Test
    void stillRefusesWholeWhenTheWrappedTextOutgrowsTheObservationBox() {
        givenObservations("rdo-transbordo", (PARAGRAFO + "\n").repeat(6));

        Map<String, ThrowingCallable> exports = new LinkedHashMap<>();
        exports.put("XLSX", () -> xlsxService.export("rdo-transbordo"));
        exports.put("PDF", () -> pdfService.export("rdo-transbordo"));

        for (Map.Entry<String, ThrowingCallable> export : exports.entrySet()) {
            assertThatThrownBy(export.getValue())
                    .as(export.getKey())
                    .isInstanceOfSatisfying(
                            ResponseStatusException.class,
                            exception -> {
                                assertThat(exception.getStatusCode())
                                        .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                                assertThat(exception.getReason())
                                        .contains("observações gerais")
                                        .contains("legível")
                                        .contains("nenhum conteúdo foi truncado");
                            }
                    );
        }
    }

    private void givenObservations(String id, String observations) {
        RdoResponse base = emptyRdo(id, "RDO-0010");
        when(queryService.buscarPorId(id)).thenReturn(new RdoResponse(
                base.id(), base.obraId(), base.programacaoId(),
                base.numeroRdo(), base.dataRdo(), base.previousRdoId(),
                base.creationContextVersion(), base.clientMutationId(),
                base.versaoEntidade(), base.apontadorColaboradorId(),
                base.diaSemana(), base.cliente(), base.contrato(),
                base.rodovia(), base.cidade(), base.uf(),
                base.kmInicialProgramado(), base.kmFinalProgramado(),
                base.kmInicialInterditado(), base.kmFinalInterditado(),
                base.turno(), base.horaInicio(), base.horaFim(),
                base.condicaoManha(), base.condicaoTarde(), base.condicaoNoite(),
                base.pluviometriaMm(), base.status(), observations,
                base.preenchidoPor(), base.apontadorRdo(),
                base.encarregadoObra(), base.fiscalizacaoCampo(),
                base.maoObra(), base.equipamentos(), base.materiais(),
                base.controlesGeometricos(), base.servicosExecutados(),
                base.alocacoesColaboradores(), base.attachments()
        ));
    }
}
