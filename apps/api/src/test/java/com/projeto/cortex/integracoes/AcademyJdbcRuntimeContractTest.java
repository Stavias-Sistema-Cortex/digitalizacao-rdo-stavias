package com.projeto.cortex.integracoes;

import static org.assertj.core.api.Assertions.assertThatCode;

import java.sql.DriverManager;
import org.junit.jupiter.api.Test;

class AcademyJdbcRuntimeContractTest {

    @Test
    void academyJdbcUrlHasAReadOnlySourceDriverAtRuntime() {
        assertThatCode(() -> DriverManager.getDriver(
                "jdbc:mysql://127.0.0.1:3306/dbstavias_acad"
        )).doesNotThrowAnyException();
    }
}
