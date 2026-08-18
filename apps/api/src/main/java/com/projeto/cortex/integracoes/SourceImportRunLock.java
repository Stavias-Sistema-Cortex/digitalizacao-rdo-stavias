package com.projeto.cortex.integracoes;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Objects;
import javax.sql.DataSource;

/** Serializes one source connector across all Córtex API instances. */
@FunctionalInterface
public interface SourceImportRunLock {

    LockHandle acquire();

    static SourceImportRunLock noOp() {
        return () -> () -> {
        };
    }

    static SourceImportRunLock postgresql(
            DataSource dataSource,
            String connectorName,
            String failureMessage
    ) {
        return new PostgresqlSourceImportRunLock(
                dataSource,
                connectorName,
                failureMessage
        );
    }

    @FunctionalInterface
    interface LockHandle extends AutoCloseable {

        @Override
        void close();
    }

    final class PostgresqlSourceImportRunLock implements SourceImportRunLock {

        private static final String ACQUIRE_SQL =
                "SELECT pg_try_advisory_xact_lock(hashtextextended(?, 0))";

        private final DataSource dataSource;
        private final String connectorName;
        private final String failureMessage;

        private PostgresqlSourceImportRunLock(
                DataSource dataSource,
                String connectorName,
                String failureMessage
        ) {
            this.dataSource = Objects.requireNonNull(
                    dataSource,
                    "DataSource PostgreSQL obrigatorio"
            );
            this.connectorName = Objects.requireNonNull(
                    connectorName,
                    "Conector de origem obrigatorio"
            );
            this.failureMessage = Objects.requireNonNull(
                    failureMessage,
                    "Mensagem segura de lock obrigatoria"
            );
        }

        @Override
        public LockHandle acquire() {
            Connection connection = null;
            try {
                connection = dataSource.getConnection();
                connection.setAutoCommit(false);
                if (!tryAcquire(connection)) {
                    rollbackQuietly(connection);
                    closeQuietly(connection);
                    throw new IllegalStateException(failureMessage);
                }
                Connection lockedConnection = connection;
                return () -> releaseAndClose(lockedConnection);
            } catch (IllegalStateException exception) {
                throw exception;
            } catch (Exception ignored) {
                closeQuietly(connection);
                throw new IllegalStateException(failureMessage);
            }
        }

        private boolean tryAcquire(Connection connection) throws Exception {
            try (PreparedStatement statement =
                         connection.prepareStatement(ACQUIRE_SQL)) {
                statement.setString(1, connectorName);
                try (ResultSet resultSet = statement.executeQuery()) {
                    return resultSet.next() && resultSet.getBoolean(1);
                }
            }
        }

        private void releaseAndClose(Connection connection) {
            rollbackQuietly(connection);
            closeQuietly(connection);
        }

        private void rollbackQuietly(Connection connection) {
            try {
                connection.rollback();
            } catch (Exception ignored) {
                // Closing the transaction releases the advisory lock.
            }
        }

        private void closeQuietly(Connection connection) {
            if (connection == null) {
                return;
            }
            try {
                connection.close();
            } catch (Exception ignored) {
                // The connector exposes only its safe public failure.
            }
        }
    }
}
