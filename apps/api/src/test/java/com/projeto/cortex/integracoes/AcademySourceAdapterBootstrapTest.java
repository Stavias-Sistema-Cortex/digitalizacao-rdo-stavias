package com.projeto.cortex.integracoes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

class AcademySourceAdapterBootstrapTest {

    private static final String JDBC_URL = "jdbc:academy-bootstrap-test";
    private static final String USERNAME = "academy-test-user";
    private static final String CREDENTIAL = "academy-test-credential";

    @Test
    void completeSnapshotUsesOneConsistentReadOnlyKeysetTransaction()
            throws Exception {
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        ResultSet firstPage = mock(ResultSet.class);
        ResultSet finalPage = mock(ResultSet.class);
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        when(statement.executeQuery()).thenReturn(firstPage, finalPage);
        when(firstPage.next()).thenReturn(true, true, false);
        when(firstPage.getInt("id_usuario")).thenReturn(907_101, 907_102);
        when(finalPage.next()).thenReturn(true, false);
        when(finalPage.getInt("id_usuario")).thenReturn(907_103);

        AcademyUserSnapshot snapshot = executeCompleteSnapshot(connection, 2);

        assertThat(snapshot.complete()).isTrue();
        assertThat(snapshot.users())
                .extracting(AcademySourceAdapter.UsuarioAcademyRecord::idUsuario)
                .containsExactly(907_101, 907_102, 907_103);
        assertThat(snapshotSql())
                .contains("WHERE u.id_usuario > ?")
                .contains("ORDER BY u.id_usuario")
                .contains("LIMIT ?");
        verify(connection).setReadOnly(true);
        verify(connection).setTransactionIsolation(
                Connection.TRANSACTION_REPEATABLE_READ
        );
        verify(connection).setAutoCommit(false);
        verify(statement).setInt(1, Integer.MIN_VALUE);
        verify(statement).setInt(1, 907_102);
        verify(statement, times(2)).setInt(2, 2);
        verify(statement, never()).setMaxRows(anyInt());
        verify(connection).commit();
        verify(connection, never()).rollback();
        verify(connection).close();
    }

    @Test
    void completeSnapshotRollsBackAndRedactsAnIntermediatePageFailure()
            throws Exception {
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        ResultSet firstPage = mock(ResultSet.class);
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        when(statement.executeQuery())
                .thenReturn(firstPage)
                .thenThrow(new SQLException(
                        "driver detail: " + canonicalSyntheticCpf()
                ));
        when(firstPage.next()).thenReturn(true, true, false);
        when(firstPage.getInt("id_usuario")).thenReturn(907_201, 907_202);

        assertThatThrownBy(() -> executeCompleteSnapshot(connection, 2))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage(
                        "Falha ao ler snapshot completo da Academy em modo somente leitura."
                )
                .hasNoCause();

        verify(connection).rollback();
        verify(connection, never()).commit();
        verify(connection).close();
    }

    @Test
    void lookupSqlIsReadOnlyParameterizedAndDoesNotSelectProtectedColumns()
            throws Exception {
        String sql = bootstrapLookupSql();

        assertThat(sql).contains(
                "WHERE REPLACE(REPLACE(REPLACE(TRIM(u.cpf), '.', ''), '-', ''), ' ', '') = ?"
        );
        assertThat(sql).contains("AND u.ativo = 1");
        assertThat(sql).contains("ORDER BY u.id_usuario");
        assertThat(sql).contains("LIMIT 2");
        assertThat(sql).doesNotContainIgnoringCase("insert");
        assertThat(sql).doesNotContainIgnoringCase("update");
        assertThat(sql).doesNotContainIgnoringCase("delete");
        assertThat(sql).doesNotContainIgnoringCase("call");
        assertThat(sql).doesNotContainIgnoringCase("senha");
        assertThat(sql).doesNotContainIgnoringCase("password");
        assertThat(sql).doesNotContain("SELECT u.cpf");

        assertThat(Arrays.stream(AcademyBootstrapUser.class.getRecordComponents())
                .map(component -> component.getName().toLowerCase()))
                .noneMatch(name -> name.contains("cpf") || name.contains("password"));
    }

    @Test
    void lookupReturnsEmptyWhenNoActiveUserMatches() throws Exception {
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        ResultSet resultSet = mock(ResultSet.class);
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        when(statement.executeQuery()).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(false);

        Optional<AcademyBootstrapUser> result = executeLookup(
                connection,
                canonicalSyntheticCpf()
        );

        assertThat(result).isEmpty();
        verify(connection).setReadOnly(true);
        verify(statement).setString(1, canonicalSyntheticCpf());
        verify(statement).setMaxRows(2);
    }

    @Test
    void lookupReturnsOnlyTheSingleActiveUserWithoutCpf() throws Exception {
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        ResultSet resultSet = mock(ResultSet.class);
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        when(statement.executeQuery()).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(true, false);
        stubFirstUser(resultSet);

        Optional<AcademyBootstrapUser> result = executeLookup(
                connection,
                canonicalSyntheticCpf()
        );

        assertThat(result).hasValueSatisfying(user -> {
            assertThat(user.sourceUserId()).isEqualTo(907_001);
            assertThat(user.nome()).isEqualTo("Usuário Academy Sintético");
            assertThat(user.email()).isEqualTo("academy-bootstrap@example.invalid");
            assertThat(user.ativo()).isTrue();
            assertThat(user.idGrupo()).isEqualTo("group-synthetic");
            assertThat(user.nomeGrupo()).isEqualTo("Grupo Sintético");
            assertThat(user.idPerfil()).isEqualTo("profile-synthetic");
            assertThat(user.nomePerfil()).isEqualTo("Perfil Sintético");
            assertThat(user.criadoEm()).isEqualTo(
                    LocalDateTime.of(2026, 7, 20, 0, 0)
            );
        });
    }

    @Test
    void lookupFailsClosedWhenTheSourceReturnsTwoActiveUsers() throws Exception {
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        ResultSet resultSet = mock(ResultSet.class);
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        when(statement.executeQuery()).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(true, true);
        stubFirstUser(resultSet);

        assertThatThrownBy(() -> executeLookup(
                connection,
                canonicalSyntheticCpf()
        ))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Resultado Academy ambíguo para bootstrap.");
    }

    @Test
    void lookupDoesNotExposeTheProtectedIdentifierThroughDriverFailures()
            throws Exception {
        String canonicalCpf = canonicalSyntheticCpf();
        AcademySourceAdapter adapter = new AcademySourceAdapter(
                JDBC_URL,
                USERNAME,
                CREDENTIAL
        );
        SQLException driverFailure = new SQLException(
                "driver detail: " + canonicalCpf
        );

        try (MockedStatic<DriverManager> driverManager =
                     Mockito.mockStatic(DriverManager.class)) {
            driverManager.when(() -> DriverManager.getConnection(
                    JDBC_URL,
                    USERNAME,
                    CREDENTIAL
            )).thenThrow(driverFailure);

            assertThatThrownBy(() ->
                    adapter.findSingleActiveUserForBootstrap(canonicalCpf)
            )
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("Falha ao consultar a fonte Academy para bootstrap.")
                    .hasNoCause();
        }
    }

    @Test
    void lookupRedactsUnexpectedRuntimeDriverFailures() throws Exception {
        String canonicalCpf = canonicalSyntheticCpf();
        Connection connection = mock(Connection.class);
        IllegalStateException driverFailure = new IllegalStateException(
                "driver runtime detail: " + canonicalCpf
        );
        when(connection.prepareStatement(anyString())).thenThrow(driverFailure);
        AcademySourceAdapter adapter = new AcademySourceAdapter(
                JDBC_URL,
                USERNAME,
                CREDENTIAL
        );

        try (MockedStatic<DriverManager> driverManager =
                     Mockito.mockStatic(DriverManager.class)) {
            driverManager.when(() -> DriverManager.getConnection(
                    JDBC_URL,
                    USERNAME,
                    CREDENTIAL
            )).thenReturn(connection);

            assertThatThrownBy(() ->
                    adapter.findSingleActiveUserForBootstrap(canonicalCpf)
            )
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("Falha ao consultar a fonte Academy para bootstrap.")
                    .hasNoCause();
        }
    }

    @Test
    void lookupClosesTheConnectionWhenReadOnlySetupFails() throws Exception {
        String canonicalCpf = canonicalSyntheticCpf();
        Connection connection = mock(Connection.class);
        SQLException readOnlyFailure = new SQLException(
                "driver read-only detail: " + canonicalCpf
        );
        doThrow(readOnlyFailure).when(connection).setReadOnly(true);
        AcademySourceAdapter adapter = new AcademySourceAdapter(
                JDBC_URL,
                USERNAME,
                CREDENTIAL
        );

        try (MockedStatic<DriverManager> driverManager =
                     Mockito.mockStatic(DriverManager.class)) {
            driverManager.when(() -> DriverManager.getConnection(
                    JDBC_URL,
                    USERNAME,
                    CREDENTIAL
            )).thenReturn(connection);

            assertThatThrownBy(() ->
                    adapter.findSingleActiveUserForBootstrap(canonicalCpf)
            )
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("Falha ao consultar a fonte Academy para bootstrap.")
                    .hasNoCause();
        }

        verify(connection).close();
    }

    private Optional<AcademyBootstrapUser> executeLookup(
            Connection connection,
            String canonicalCpf
    ) throws Exception {
        AcademySourceAdapter adapter = new AcademySourceAdapter(
                JDBC_URL,
                USERNAME,
                CREDENTIAL
        );

        try (MockedStatic<DriverManager> driverManager =
                     Mockito.mockStatic(DriverManager.class)) {
            driverManager.when(() -> DriverManager.getConnection(
                    JDBC_URL,
                    USERNAME,
                    CREDENTIAL
            )).thenReturn(connection);

            return adapter.findSingleActiveUserForBootstrap(canonicalCpf);
        }
    }

    private AcademyUserSnapshot executeCompleteSnapshot(
            Connection connection,
            int pageSize
    ) throws Exception {
        AcademySourceAdapter adapter = new AcademySourceAdapter(
                JDBC_URL,
                USERNAME,
                CREDENTIAL
        );

        try (MockedStatic<DriverManager> driverManager =
                     Mockito.mockStatic(DriverManager.class)) {
            driverManager.when(() -> DriverManager.getConnection(
                    JDBC_URL,
                    USERNAME,
                    CREDENTIAL
            )).thenReturn(connection);

            try {
                return adapter.fetchCompleteSnapshot(pageSize);
            } finally {
                driverManager.verify(() -> DriverManager.getConnection(
                        JDBC_URL,
                        USERNAME,
                        CREDENTIAL
                ), times(1));
            }
        }
    }

    private String snapshotSql() throws Exception {
        Field field = AcademySourceAdapter.class.getDeclaredField(
                "SQL_SELECT_USUARIOS"
        );
        field.setAccessible(true);
        return (String) field.get(null);
    }

    private String bootstrapLookupSql() throws Exception {
        Field field = AcademySourceAdapter.class.getDeclaredField(
                "SQL_SELECT_BOOTSTRAP_USER"
        );
        field.setAccessible(true);
        return (String) field.get(null);
    }

    private void stubFirstUser(ResultSet resultSet) throws Exception {
        when(resultSet.getInt("id_usuario")).thenReturn(907_001);
        when(resultSet.getString("nome")).thenReturn("Usuário Academy Sintético");
        when(resultSet.getString("email")).thenReturn(
                "academy-bootstrap@example.invalid"
        );
        when(resultSet.getBoolean("ativo")).thenReturn(true);
        when(resultSet.getObject("id_grupo")).thenReturn("group-synthetic");
        when(resultSet.getString("nome_grupo")).thenReturn("Grupo Sintético");
        when(resultSet.getObject("id_perfil")).thenReturn("profile-synthetic");
        when(resultSet.getString("nome_perfil")).thenReturn("Perfil Sintético");
        when(resultSet.getTimestamp("criado_em")).thenReturn(
                Timestamp.valueOf(LocalDateTime.of(2026, 7, 20, 0, 0))
        );
    }

    private String canonicalSyntheticCpf() {
        String base = "581729364";
        String first = String.valueOf(checkDigit(base, 10));
        return base + first + checkDigit(base + first, 11);
    }

    private int checkDigit(String digits, int initialWeight) {
        int sum = 0;
        for (int index = 0; index < digits.length(); index++) {
            sum += (digits.charAt(index) - '0') * (initialWeight - index);
        }
        int value = 11 - (sum % 11);
        return value >= 10 ? 0 : value;
    }
}
