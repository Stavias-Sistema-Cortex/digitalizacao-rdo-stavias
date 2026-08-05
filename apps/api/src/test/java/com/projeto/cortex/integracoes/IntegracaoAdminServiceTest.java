package com.projeto.cortex.integracoes;

import com.projeto.cortex.assets.AssetImportService;
import com.projeto.cortex.colaboradores.ColaboradorImportService;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class IntegracaoAdminServiceTest {

    @Test
    void shouldReturnFailedActionWhenAcademySyncThrows() {
        ColaboradorImportService colaboradorImportService =
                mock(ColaboradorImportService.class);

        when(colaboradorImportService.importarUsuariosDaAcademy())
                .thenThrow(
                        new RuntimeException(
                                "Falha ao importar.",
                                new IllegalStateException(
                                        "Configuracao Academy incompleta."
                                )
                        )
                );

        IntegracaoActionResponse response =
                service(colaboradorImportService, mock(AssetImportService.class))
                        .startSync("academy");

        assertThat(response.status()).isEqualTo("FAILED");
        assertThat(response.mensagem())
                .isEqualTo(
                        "Sincronizacao Academy falhou. "
                                + "Consulte o relatório da integração "
                                + "para detalhes."
                )
                .doesNotContain("Configuracao Academy incompleta");
    }

    @Test
    void shouldReturnFailedActionWhenZeladoriaSyncThrows() {
        AssetImportService assetImportService =
                mock(AssetImportService.class);

        when(assetImportService.importFromZldAtivos())
                .thenThrow(
                        new IllegalStateException(
                                "Configuracao Zeladoria incompleta."
                        )
                );

        IntegracaoActionResponse response =
                service(mock(ColaboradorImportService.class), assetImportService)
                        .startSync("zeladoria");

        assertThat(response.status()).isEqualTo("FAILED");
        assertThat(response.mensagem())
                .contains("Sincronizacao Zeladoria falhou")
                .contains("Configuracao Zeladoria incompleta");
    }

    @Test
    void academyStatusRedactsHistoricalPersistedDriverDetails() {
        String historical = "SQL exception for CPF 11144477735 "
                + "and owner@example.invalid";

        assertThat(IntegracaoAdminService.safeStatusError(
                "academy",
                historical
        ))
                .isEqualTo(
                        "Sincronizacao Academy falhou. "
                                + "Consulte o relatório da integração "
                                + "para detalhes."
                )
                .doesNotContain("11144477735")
                .doesNotContain("owner@example.invalid")
                .doesNotContain("SQL exception");
    }

    /*
     * A janela de quinze minutos da Academy morava na readiness do runtime:
     * passar dela deixava a API inteira indisponível, RDO, mapa e mensagens
     * incluídos, que não dependem da Academy para nada. Aqui ela é o que
     * sempre deveria ter sido — um estado da integração.
     */
    @Test
    void academyQueRespondeuHaTempoDemaisEhRelatadaComoAtrasada() {
        LocalDateTime agora = LocalDateTime.of(2026, 8, 5, 12, 0);

        assertThat(IntegracaoAdminService.estadoComAtraso(
                "academy",
                "SUCCESS",
                agora.minusMinutes(16),
                true,
                900_000L,
                agora
        )).isEqualTo("ATRASADA");

        assertThat(IntegracaoAdminService.estadoComAtraso(
                "academy",
                "SUCCESS",
                agora.minusMinutes(5),
                true,
                900_000L,
                agora
        )).isEqualTo("SUCCESS");
    }

    @Test
    void semAgendadorLigadoNinguemPrometeuAtualizacaoPeriodica() {
        LocalDateTime agora = LocalDateTime.of(2026, 8, 5, 12, 0);

        // Com o agendador desligado a sincronização só acontece por clique de
        // um Alfa. Chamar de atrasado o que não tem hora marcada inventaria um
        // defeito, e é justamente a configuração de produção hoje.
        assertThat(IntegracaoAdminService.estadoComAtraso(
                "academy",
                "SUCCESS",
                agora.minusDays(30),
                false,
                900_000L,
                agora
        )).isEqualTo("SUCCESS");
    }

    @Test
    void oAtrasoNaoMascaraOEstadoQueJaDizMais() {
        LocalDateTime agora = LocalDateTime.of(2026, 8, 5, 12, 0);

        // FAILED e SEM_SINCRONIZACAO são mais informativos que "atrasada";
        // trocá-los apagaria a razão real.
        assertThat(IntegracaoAdminService.estadoComAtraso(
                "academy",
                "FAILED",
                null,
                true,
                900_000L,
                agora
        )).isEqualTo("FAILED");

        assertThat(IntegracaoAdminService.estadoComAtraso(
                "academy",
                "SEM_SINCRONIZACAO",
                null,
                true,
                900_000L,
                agora
        )).isEqualTo("SEM_SINCRONIZACAO");
    }

    @Test
    void aJanelaDaAcademyNaoSeAplicaAZeladoria() {
        LocalDateTime agora = LocalDateTime.of(2026, 8, 5, 12, 0);

        assertThat(IntegracaoAdminService.estadoComAtraso(
                "zeladoria",
                "SUCCESS",
                agora.minusDays(2),
                true,
                900_000L,
                agora
        )).isEqualTo("SUCCESS");
    }

    @Test
    void sucessoSemDataDeSucessoEhAtrasoENaoSucesso() {
        LocalDateTime agora = LocalDateTime.of(2026, 8, 5, 12, 0);

        assertThat(IntegracaoAdminService.estadoComAtraso(
                "academy",
                "SUCCESS",
                null,
                true,
                900_000L,
                agora
        )).isEqualTo("ATRASADA");
    }

    private IntegracaoAdminService service(
            ColaboradorImportService colaboradorImportService,
            AssetImportService assetImportService
    ) {
        return new IntegracaoAdminService(
                mock(JdbcTemplate.class),
                mock(AcademySourceAdapter.class),
                mock(ZeladoriaSourceAdapter.class),
                colaboradorImportService,
                assetImportService
        );
    }
}
