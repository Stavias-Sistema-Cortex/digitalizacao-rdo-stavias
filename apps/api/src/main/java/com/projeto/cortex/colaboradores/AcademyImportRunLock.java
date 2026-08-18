package com.projeto.cortex.colaboradores;

import com.projeto.cortex.integracoes.SourceImportRunLock;
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

    private static final String LOCK_FAILURE_MESSAGE =
            "Sincronizacao Academy indisponivel.";

    private final SourceImportRunLock delegate;

    PostgresqlAcademyImportRunLock(
            DataSource dataSource,
            String connectorName
    ) {
        this.delegate = SourceImportRunLock.postgresql(
                dataSource,
                connectorName,
                LOCK_FAILURE_MESSAGE
        );
    }

    @Override
    public LockHandle acquire() {
        SourceImportRunLock.LockHandle handle = delegate.acquire();
        return handle::close;
    }
}
