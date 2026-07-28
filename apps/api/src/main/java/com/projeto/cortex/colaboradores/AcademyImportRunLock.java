package com.projeto.cortex.colaboradores;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Objects;
import javax.sql.DataSource;

@FunctionalInterface
interface AcademyImportRunLock {

    LockHandle acquire();

    static AcademyImportRunLock noOp() {
        return () -> () -> {
        };
    }

    @FunctionalInterface
    interface LockHandle extends AutoCloseable {

        @Override
        void close();
    }
}

final class PostgresqlAcademyImportRunLock
        implements AcademyImportRunLock {

    private static final String ACQUIRE_SQL =
            "SELECT pg_try_advisory_xact_lock(hashtextextended(?, 0))";
    private static final String LOCK_FAILURE_MESSAGE =
            "Sincronizacao Academy indisponivel.";

    private final DataSource dataSource;
    private final String connectorName;

    PostgresqlAcademyImportRunLock(
            DataSource dataSource,
            String connectorName
    ) {
        this.dataSource = Objects.requireNonNull(
                dataSource,
                "DataSource PostgreSQL obrigatorio"
        );
        this.connectorName = Objects.requireNonNull(
                connectorName,
                "Conector Academy obrigatorio"
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
                throw new IllegalStateException(LOCK_FAILURE_MESSAGE);
            }
            Connection lockedConnection = connection;
            return () -> releaseAndClose(lockedConnection);
        } catch (IllegalStateException exception) {
            throw exception;
        } catch (Exception ignored) {
            closeQuietly(connection);
            throw new IllegalStateException(LOCK_FAILURE_MESSAGE);
        }
    }

    private boolean tryAcquire(Connection connection) throws Exception {
        try (
                PreparedStatement statement =
                        connection.prepareStatement(ACQUIRE_SQL)
        ) {
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
            // Closing the dedicated transaction releases any remaining lock.
        }
    }

    private void closeQuietly(Connection connection) {
        if (connection == null) {
            return;
        }
        try {
            connection.close();
        } catch (Exception ignored) {
            // The public import error remains generic.
        }
    }
}
