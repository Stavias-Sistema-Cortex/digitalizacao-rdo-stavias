package com.projeto.cortex.integracoes;

import com.projeto.cortex.assets.AssetImportService;
import com.projeto.cortex.colaboradores.ColaboradorImportService;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

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
                .contains("Sincronizacao Academy falhou")
                .contains("Configuracao Academy incompleta");
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
