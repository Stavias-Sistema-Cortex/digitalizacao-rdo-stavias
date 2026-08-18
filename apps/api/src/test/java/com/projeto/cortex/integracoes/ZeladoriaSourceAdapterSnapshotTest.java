package com.projeto.cortex.integracoes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.mockito.MockedStatic;

class ZeladoriaSourceAdapterSnapshotTest {

    private static final String REDACTED_FAILURE =
            "Falha ao ler snapshot completo da Zeladoria em modo somente leitura.";

    @Test
    void readsEveryPageInOneReadOnlyRepeatableReadTransaction()
            throws Exception {
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        ResultSet firstPage = rows(
                row("1", "EQ-01", "CAMINHAO", "Modelo A"),
                row("2", "EQ-02", "ROLO", "Modelo B")
        );
        ResultSet secondPage = rows(
                row("3", "EQ-03", "TRATOR", "Modelo C")
        );
        when(statement.executeQuery()).thenReturn(firstPage, secondPage);

        Object snapshot;
        try (MockedStatic<DriverManager> driver = mockStatic(
                DriverManager.class
        )) {
            driver.when(() -> DriverManager.getConnection(
                    "jdbc:mysql://source.invalid/zeladoria",
                    "reader",
                    "secret"
            )).thenReturn(connection);
            snapshot = fetchCompleteSnapshot(adapter(), 2);
        }

        assertThat((boolean) invoke(snapshot, "complete")).isTrue();
        List<?> assets = (List<?>) invoke(snapshot, "assets");
        assertThat(assets)
                .extracting(asset -> invokeUnchecked(asset, "id"))
                .containsExactly("1", "2", "3");

        InOrder order = inOrder(connection, statement);
        order.verify(connection).setReadOnly(true);
        order.verify(connection).setTransactionIsolation(
                Connection.TRANSACTION_REPEATABLE_READ
        );
        order.verify(connection).setAutoCommit(false);
        order.verify(statement).setString(1, "0");
        order.verify(statement).setInt(2, 2);
        order.verify(statement).executeQuery();
        order.verify(statement).setString(1, "2");
        order.verify(statement).setInt(2, 2);
        order.verify(statement).executeQuery();
        order.verify(connection).commit();
        verify(connection, never()).rollback();
    }

    @Test
    void rollsBackAndNeverReturnsAPartialSnapshotWhenLaterPageFails()
            throws Exception {
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        ResultSet firstPage = rows(
                row("1", "EQ-01", "CAMINHAO", "Modelo A"),
                row("2", "EQ-02", "ROLO", "Modelo B")
        );
        when(statement.executeQuery()).thenReturn(firstPage).thenThrow(
                new IllegalStateException(
                "driver leaked source.invalid reader secret"
        ));

        try (MockedStatic<DriverManager> driver = mockStatic(
                DriverManager.class
        )) {
            driver.when(() -> DriverManager.getConnection(
                    "jdbc:mysql://source.invalid/zeladoria",
                    "reader",
                    "secret"
            )).thenReturn(connection);

            assertThatThrownBy(() -> fetchCompleteSnapshot(adapter(), 2))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage(REDACTED_FAILURE)
                    .hasMessageNotContaining("source.invalid")
                    .hasMessageNotContaining("reader")
                    .hasMessageNotContaining("secret")
                    .hasNoCause();
        }

        verify(connection).rollback();
        verify(connection, never()).commit();
    }

    private ZeladoriaSourceAdapter adapter() {
        return new ZeladoriaSourceAdapter(
                "jdbc:mysql://source.invalid/zeladoria",
                "reader",
                "secret"
        );
    }

    private Object fetchCompleteSnapshot(
            ZeladoriaSourceAdapter adapter,
            int pageSize
    ) throws Exception {
        Method method;
        try {
            method = ZeladoriaSourceAdapter.class.getMethod(
                    "fetchCompleteSnapshot",
                    int.class
            );
        } catch (NoSuchMethodException exception) {
            throw new AssertionError(
                    "Zeladoria must expose a complete snapshot API.",
                    exception
            );
        }
        try {
            return method.invoke(adapter, pageSize);
        } catch (InvocationTargetException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof Exception checked) {
                throw checked;
            }
            throw exception;
        }
    }

    private Object invoke(Object target, String method) throws Exception {
        return target.getClass().getMethod(method).invoke(target);
    }

    private Object invokeUnchecked(Object target, String method) {
        try {
            return invoke(target, method);
        } catch (Exception exception) {
            throw new AssertionError(exception);
        }
    }

    @SafeVarargs
    private ResultSet rows(Map<String, String>... sourceRows)
            throws Exception {
        ResultSet resultSet = mock(ResultSet.class);
        int[] index = {-1};
        when(resultSet.next()).thenAnswer(ignored -> {
            index[0]++;
            return index[0] < sourceRows.length;
        });
        when(resultSet.getString(anyString())).thenAnswer(invocation ->
                sourceRows[index[0]].get(invocation.getArgument(0))
        );
        return resultSet;
    }

    private Map<String, String> row(
            String id,
            String prefixo,
            String tipo,
            String modelo
    ) {
        return Map.of(
                "id", id,
                "prefixo", prefixo,
                "tipo", tipo,
                "modelo", modelo
        );
    }
}
