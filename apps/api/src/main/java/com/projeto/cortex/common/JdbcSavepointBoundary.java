package com.projeto.cortex.common;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Savepoint;
import javax.sql.DataSource;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceUtils;

/**
 * Limite JDBC pequeno para recuperar violações esperadas de unicidade.
 *
 * <p>No PostgreSQL, capturar {@code DuplicateKeyException} não basta: a
 * transação permanece abortada até voltar a um savepoint. O limite é inativo
 * em autocommit, onde a própria instrução que falhou já foi revertida.</p>
 */
public final class JdbcSavepointBoundary {

    private final DataSource dataSource;
    private final Savepoint savepoint;

    private JdbcSavepointBoundary(
            DataSource dataSource,
            Savepoint savepoint
    ) {
        this.dataSource = dataSource;
        this.savepoint = savepoint;
    }

    public static JdbcSavepointBoundary begin(
            JdbcTemplate jdbcTemplate,
            String failureMessage
    ) {
        DataSource dataSource = jdbcTemplate.getDataSource();
        if (dataSource == null) {
            return new JdbcSavepointBoundary(null, null);
        }
        Connection connection = DataSourceUtils.getConnection(dataSource);
        try {
            if (connection.getAutoCommit()) {
                return new JdbcSavepointBoundary(null, null);
            }
            return new JdbcSavepointBoundary(
                    dataSource,
                    connection.setSavepoint()
            );
        } catch (SQLException exception) {
            throw new DataAccessResourceFailureException(
                    failureMessage,
                    exception
            );
        } finally {
            DataSourceUtils.releaseConnection(connection, dataSource);
        }
    }

    public void rollbackAndRelease(String failureMessage) {
        if (dataSource == null || savepoint == null) {
            return;
        }
        Connection connection = DataSourceUtils.getConnection(dataSource);
        try {
            connection.rollback(savepoint);
            connection.releaseSavepoint(savepoint);
        } catch (SQLException exception) {
            throw new DataAccessResourceFailureException(
                    failureMessage,
                    exception
            );
        } finally {
            DataSourceUtils.releaseConnection(connection, dataSource);
        }
    }

    public void release(String failureMessage) {
        if (dataSource == null || savepoint == null) {
            return;
        }
        Connection connection = DataSourceUtils.getConnection(dataSource);
        try {
            connection.releaseSavepoint(savepoint);
        } catch (SQLException exception) {
            throw new DataAccessResourceFailureException(
                    failureMessage,
                    exception
            );
        } finally {
            DataSourceUtils.releaseConnection(connection, dataSource);
        }
    }
}
