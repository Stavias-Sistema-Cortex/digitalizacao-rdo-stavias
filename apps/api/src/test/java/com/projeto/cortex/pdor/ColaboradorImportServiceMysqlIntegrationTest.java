package com.projeto.cortex.pdor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.projeto.cortex.auth.identity.AuthIdentityRepository;
import com.projeto.cortex.colaboradores.ColaboradorImportService;
import com.projeto.cortex.colaboradores.CpfHasher;
import com.projeto.cortex.integracoes.AcademySourceAdapter;
import com.projeto.cortex.memory.CortexOperationalMemoryService;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;

@EnabledIfEnvironmentVariable(named = "CORTEX_MYSQL_ROOT_PASSWORD", matches = ".+")
class ColaboradorImportServiceMysqlIntegrationTest {

    private static final String FIRST_SYNTHETIC_CPF = "11144477735";
    private static final String SECOND_SYNTHETIC_CPF = "90000007935";
    private static final String SOURCE_PK = "900000004";

    private PdorMysqlTestDatabase database;

    @AfterEach
    void dropDatabase() {
        if (database != null) {
            database.drop();
        }
    }

    @Test
    void cpfOnlyChangePersistsAndIncrementsVersionWithStableSourceHash() {
        database = PdorMysqlTestDatabase.create("cpf_update");
        database.migrate();

        JdbcTemplate jdbc = new JdbcTemplate(database.dataSource());
        AcademySourceAdapter academy = mock(AcademySourceAdapter.class);
        CortexOperationalMemoryService memory =
                mock(CortexOperationalMemoryService.class);
        AuthIdentityRepository authIdentities =
                mock(AuthIdentityRepository.class);

        when(academy.fetchUsers(anyInt())).thenReturn(
                List.of(academyUser(FIRST_SYNTHETIC_CPF)),
                List.of(academyUser(SECOND_SYNTHETIC_CPF)),
                List.of(academyUser(FIRST_SYNTHETIC_CPF))
        );
        ColaboradorImportService service = new ColaboradorImportService(
                jdbc,
                academy,
                memory,
                authIdentities
        );

        service.importarUsuariosDaAcademy();
        Map<String, Object> first = collaboratorState(jdbc);

        service.importarUsuariosDaAcademy();
        Map<String, Object> second = collaboratorState(jdbc);

        assertThat(second.get("cpf_hash"))
                .isEqualTo(CpfHasher.hashDeDigitos(SECOND_SYNTHETIC_CPF));
        assertThat(second.get("hash_origem"))
                .isEqualTo(first.get("hash_origem"));
        assertThat(version(second)).isEqualTo(version(first) + 1);

        jdbc.update("""
                UPDATE colaborador
                SET cpf_hash = NULL
                WHERE banco_origem = 'dbstavias_acad'
                  AND tabela_origem = 'usuarios'
                  AND pk_origem = ?
                """, SOURCE_PK);

        service.importarUsuariosDaAcademy();
        Map<String, Object> afterNull = collaboratorState(jdbc);

        assertThat(afterNull.get("cpf_hash"))
                .isEqualTo(CpfHasher.hashDeDigitos(FIRST_SYNTHETIC_CPF));
        assertThat(afterNull.get("hash_origem"))
                .isEqualTo(first.get("hash_origem"));
        assertThat(version(afterNull)).isEqualTo(version(second) + 1);
    }

    private AcademySourceAdapter.UsuarioAcademyRecord academyUser(String cpf) {
        return new AcademySourceAdapter.UsuarioAcademyRecord(
                Integer.parseInt(SOURCE_PK),
                cpf,
                "Colaborador Sintético",
                "colaborador@example.invalid",
                true,
                "grupo-teste",
                "Operacional",
                "perfil-teste",
                "Administrador legado",
                LocalDateTime.of(2026, 1, 1, 0, 0)
        );
    }

    private Map<String, Object> collaboratorState(JdbcTemplate jdbc) {
        return jdbc.queryForMap("""
                SELECT cpf_hash, hash_origem, versao_linha
                FROM colaborador
                WHERE banco_origem = 'dbstavias_acad'
                  AND tabela_origem = 'usuarios'
                  AND pk_origem = ?
                """, SOURCE_PK);
    }

    private long version(Map<String, Object> state) {
        return ((Number) state.get("versao_linha")).longValue();
    }
}
