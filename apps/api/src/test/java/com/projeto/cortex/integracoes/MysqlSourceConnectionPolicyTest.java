package com.projeto.cortex.integracoes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Properties;
import org.junit.jupiter.api.Test;

class MysqlSourceConnectionPolicyTest {

    @Test
    void appliesBoundedConnectAndSocketTimeoutsOutsideTheJdbcUrl() {
        Properties properties = MysqlSourceConnectionPolicy.properties(
                "readonly-source-user",
                "test-only-source-credential"
        );

        assertThat(properties)
                .containsEntry("user", "readonly-source-user")
                .containsEntry("password", "test-only-source-credential")
                .containsEntry("connectTimeout", "10000")
                .containsEntry("socketTimeout", "30000")
                .containsEntry("tcpKeepAlive", "true");
    }

    @Test
    void rejectsUrlParametersThatCouldOverrideTheBoundedTimeouts() {
        assertThatThrownBy(() -> MysqlSourceConnectionPolicy.validateUrl(
                "jdbc:mysql://source.invalid/db?sslMode=VERIFY_IDENTITY"
                        + "&socketTimeout=0"
        ))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Configuração de prazo da fonte MySQL inválida.")
                .hasMessageNotContaining("source.invalid");

        assertThatThrownBy(() -> MysqlSourceConnectionPolicy.validateUrl(
                "jdbc:mysql://source.invalid/db?CONNECTTIMEOUT=999999"
        ))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Configuração de prazo da fonte MySQL inválida.");
    }
}
