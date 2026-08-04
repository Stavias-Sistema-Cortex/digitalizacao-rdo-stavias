package com.projeto.cortex.storage;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.util.unit.DataSize;

/**
 * Quem corta o anexo primeiro é o contêiner, não o serviço.
 *
 * O app valida o arquivo contra {@code cortex.storage.max-size-bytes}, mas
 * essa checagem só roda depois que o multipart já foi aceito. Sem configurar o
 * limite do servlet valia o padrão do Spring — 1 MB por arquivo —, e uma foto
 * de obra passa disso com folga. O envio morria antes de chegar ao serviço, e
 * como o erro não é de rede, a fila local marcava o anexo como recusado: o
 * arquivo ficava parado esperando uma revisão que ninguém sabia fazer.
 *
 * Este teste existe para os dois números não voltarem a divergir. Eles saem da
 * mesma variável de ambiente de propósito.
 */
class UploadSizeBoundaryContractTest {

    private static final Path APPLICATION =
            Path.of("src/main/resources/application.yml");

    private String configuracao() throws IOException {
        return Files.readString(APPLICATION);
    }

    @Test
    void oLimiteDoServletAcompanhaOLimiteDoArmazenamento() throws IOException {
        String application = configuracao();

        assertThat(application).contains(
                "max-file-size: ${CORTEX_STORAGE_MAX_SIZE_BYTES:26214400}B"
        );
        assertThat(application).contains(
                "max-size-bytes: ${CORTEX_STORAGE_MAX_SIZE_BYTES:26214400}"
        );
    }

    /**
     * O envelope do multipart carrega, além do arquivo, o obraId e as bordas
     * de cada parte. Um teto de requisição igual ao do arquivo recusaria
     * justamente o anexo do tamanho máximo permitido.
     */
    @Test
    void oEnvelopeCabeAlemDoArquivo() throws IOException {
        String application = configuracao();

        assertThat(application).contains("max-request-size:");
        assertThat(padraoDe(application, "max-request-size"))
                .isGreaterThan(padraoDe(application, "max-file-size"));
    }

    private long padraoDe(String application, String chave) {
        int inicio = application.indexOf(chave + ": ${");
        assertThat(inicio).as("chave %s ausente", chave).isNotNegative();
        int abre = application.indexOf(':', inicio + chave.length() + 3);
        int fecha = application.indexOf('}', abre);
        String bruto = application.substring(abre + 1, fecha).trim();
        return DataSize.parse(bruto + "B").toBytes();
    }
}
