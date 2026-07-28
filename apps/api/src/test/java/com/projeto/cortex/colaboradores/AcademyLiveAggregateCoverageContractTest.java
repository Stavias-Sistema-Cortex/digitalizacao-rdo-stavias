package com.projeto.cortex.colaboradores;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class AcademyLiveAggregateCoverageContractTest {

    private static final String SOURCE_DATABASE =
            AcademyLiveAggregateCoverageIT.sourceDatabase();

    @Test
    void acceptsOnlyUsageAndTheThreeExactTableSelects() {
        AcademyLiveAggregateCoverageIT.GrantAggregates grants =
                AcademyLiveAggregateCoverageIT.classifyGrantStatements(
                        List.of(
                                "GRANT USAGE ON *.* TO 'qa'@'%'",
                                "GRANT SELECT ON `" + SOURCE_DATABASE
                                        + "`.`usuarios` "
                                        + "TO 'qa'@'%'",
                                "GRANT SELECT ON `" + SOURCE_DATABASE
                                        + "`.`grupos` "
                                        + "TO 'qa'@'%'",
                                "GRANT SELECT ON `" + SOURCE_DATABASE
                                        + "`.`perfil` "
                                        + "TO 'qa'@'%'"
                        )
                );

        assertThat(grants.unexpectedStatements()).isZero();
        assertThat(grants.selectTables()).containsExactlyInAnyOrderElementsOf(
                Set.of("usuarios", "grupos", "perfil")
        );
    }

    @Test
    void rejectsGrantOptionEvenOnAnOtherwiseExactTableSelect() {
        AcademyLiveAggregateCoverageIT.GrantAggregates grants =
                AcademyLiveAggregateCoverageIT.classifyGrantStatements(
                        List.of(
                                "GRANT USAGE ON *.* TO 'qa'@'%'",
                                "GRANT SELECT ON `" + SOURCE_DATABASE
                                        + "`.`usuarios` "
                                        + "TO 'qa'@'%' WITH GRANT OPTION",
                                "GRANT SELECT ON `" + SOURCE_DATABASE
                                        + "`.`grupos` "
                                        + "TO 'qa'@'%'",
                                "GRANT SELECT ON `" + SOURCE_DATABASE
                                        + "`.`perfil` "
                                        + "TO 'qa'@'%'"
                        )
                );

        assertThat(grants.unexpectedStatements()).isOne();
        assertThat(grants.delegatingStatements()).isOne();
        assertThat(grants.selectTables())
                .containsExactlyInAnyOrder("grupos", "perfil");
    }

    @Test
    void classifiesSchemaAndGlobalPrivilegesAsBroadScope() {
        AcademyLiveAggregateCoverageIT.GrantAggregates grants =
                AcademyLiveAggregateCoverageIT.classifyGrantStatements(
                        List.of(
                                "GRANT SELECT ON `" + SOURCE_DATABASE
                                        + "`.* "
                                        + "TO 'qa'@'%'",
                                "GRANT ALL PRIVILEGES ON *.* TO 'qa'@'%'"
                        )
                );

        assertThat(grants.unexpectedStatements()).isEqualTo(2);
        assertThat(grants.schemaWideSelectStatements()).isOne();
        assertThat(grants.broadScopeStatements()).isEqualTo(2);
        assertThat(grants.selectTables()).isEmpty();
    }

    @Test
    void rejectsRoleGrantsWithoutPrintingTheirContents() {
        AcademyLiveAggregateCoverageIT.GrantAggregates grants =
                AcademyLiveAggregateCoverageIT.classifyGrantStatements(
                        List.of("GRANT 'academy_reader' TO 'qa'@'%'")
                );

        assertThat(grants.unexpectedStatements()).isOne();
        assertThat(grants.roleGrantStatements()).isOne();
        assertThat(grants.selectTables()).isEmpty();
    }
}
