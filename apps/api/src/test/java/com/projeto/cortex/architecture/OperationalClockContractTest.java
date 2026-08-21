package com.projeto.cortex.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * O relógio civil da operação é o de Brasília, e a imagem precisa dizê-lo.
 *
 * <p>Os carimbos civis do Córtex ({@code LocalDateTime.now()},
 * {@code CURRENT_TIMESTAMP} via sessão JDBC) não carregam fuso, e todos os
 * leitores assumem que nasceram no relógio de Brasília: a Memória interpreta as
 * gerações civis com {@code AT TIME ZONE 'America/Sao_Paulo'} e a PWA preserva
 * os dígitos dessas gerações como relógio de parede. Quando o contêiner subiu
 * sem fuso no servidor local, a JVM caiu em UTC e a ontologia da obra passou a
 * exibir o relógio de UTC — três horas adiantado para quem opera no Brasil.
 * Este contrato impede que o pino do fuso se perca numa refatoração da
 * imagem.</p>
 */
class OperationalClockContractTest {

    private static final Path REPOSITORY_ROOT = Path.of("../..");

    @Test
    void apiImagePinsTheOperationCivilClock() throws Exception {
        String dockerfile = Files.readString(
                REPOSITORY_ROOT.resolve("apps/api/Dockerfile")
        );

        assertThat(dockerfile)
                .contains("ENV TZ=America/Sao_Paulo")
                // O ENTRYPOINT repete o fuso na própria JVM: zerar o TZ do
                // ambiente não pode devolver o relógio a UTC em silêncio.
                .contains("-Duser.timezone=${TZ:-America/Sao_Paulo}");
    }

    @Test
    void productionComposeDeclaresTheClockForOperators() throws Exception {
        String compose = Files.readString(
                REPOSITORY_ROOT.resolve("compose.production.example.yml")
        );

        assertThat(compose).contains("TZ: ${CORTEX_TZ:-America/Sao_Paulo}");
    }
}
