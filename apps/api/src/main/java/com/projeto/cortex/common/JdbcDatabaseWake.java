package com.projeto.cortex.common;

import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Abre uma conexão de verdade e devolve.
 *
 * <p>A primeira conexão é a cara — DNS, TCP, TLS e autenticação contra um banco
 * que pode estar suspenso. É exatamente essa que o primeiro login do dia não
 * deveria pagar, e é por isso que existe um toque que a paga antes.
 *
 * <p>Usa um {@link JdbcTemplate} próprio, e não o compartilhado da aplicação,
 * apenas para poder impor um limite de tempo sem alterar o comportamento de
 * todas as outras consultas do sistema.
 */
@Component
public final class JdbcDatabaseWake implements DatabaseWake {

    static final int PROBE_TIMEOUT_SECONDS = 5;

    private final JdbcTemplate probe;

    @Autowired
    public JdbcDatabaseWake(ObjectProvider<DataSource> dataSource) {
        this(boundedTemplate(dataSource.getIfAvailable()));
    }

    JdbcDatabaseWake(JdbcTemplate probe) {
        this.probe = probe;
    }

    @Override
    public String status() {
        if (probe == null) {
            return "ABSENT";
        }
        try {
            probe.queryForObject("SELECT 1", Integer.class);
            return "WARM";
        } catch (RuntimeException exception) {
            // Um toque de aquecimento não decide nada, então não fecha nada:
            // relatar que o banco continua frio é a resposta correta.
            return "COLD";
        }
    }

    private static JdbcTemplate boundedTemplate(DataSource dataSource) {
        if (dataSource == null) {
            return null;
        }
        JdbcTemplate template = new JdbcTemplate(dataSource);
        template.setQueryTimeout(PROBE_TIMEOUT_SECONDS);
        return template;
    }
}
