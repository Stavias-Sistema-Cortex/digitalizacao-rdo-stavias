package com.projeto.cortex.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Nenhuma escrita em coluna json/jsonb sem dizer que é JSON.
 *
 * <p>Este contrato existe porque o mesmo defeito entrou duas vezes no mesmo
 * dia, por duas portas diferentes. O PostgreSQL não converte VARCHAR em jsonb
 * implicitamente: um {@code ?} pelado no SQL, ou um campo String de entidade
 * sem {@code @JdbcTypeCode(SqlTypes.JSON)}, compila, passa no CI e explode na
 * primeira gravação em produção — foi assim com a passkey
 * ({@code transports_json}) e com a geometria de obra
 * ({@code geometria_json}), esta última retentando 196 vezes na fila de um
 * apontador antes de alguém ver. O MySQL aceita string em coluna JSON, e a
 * suíte histórica rodava nele; o CI verde não estava testemunhando o banco da
 * produção.
 *
 * <p>Caçar caso a caso não fecha a porta — só o padrão fecha. Este teste lê as
 * migrações para saber quais colunas são json/jsonb e depois varre o fonte:
 * todo INSERT/UPDATE que toca uma dessas colunas precisa do cast
 * ({@code ?::jsonb} ou {@code CAST(? AS JSONB)}), e todo campo de entidade
 * mapeado para elas precisa do {@code @JdbcTypeCode}. Quem escrever a próxima
 * gravação sem isso descobre aqui, no primeiro {@code mvn test} — não no campo.
 */
class PostgresqlJsonWriteContractTest {

    private static final Pattern JSON_COLUMN = Pattern.compile(
            "(\\w+)\\s+jsonb?\\b",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern TEXT_BLOCK = Pattern.compile(
            "\"\"\"(.*?)\"\"\"",
            Pattern.DOTALL
    );
    private static final Pattern INSERT_SHAPE = Pattern.compile(
            "INSERT\\s+INTO\\s+(\\w+)\\s*\\(([^)]*)\\)\\s*VALUES\\s*\\((.*?)\\)",
            Pattern.DOTALL | Pattern.CASE_INSENSITIVE
    );
    private static final Pattern UPDATE_BARE_BIND = Pattern.compile(
            "(\\w+)\\s*=\\s*\\?(?!::)"
    );
    private static final Pattern JPA_JSON_FIELD = Pattern.compile(
            "@Column\\([^)]*columnDefinition\\s*=\\s*\"jsonb?\"[^)]*\\)",
            Pattern.CASE_INSENSITIVE
    );

    @Test
    void everyJsonColumnWriteDeclaresItselfAsJson() throws IOException {
        Set<String> jsonColumns = jsonColumnsFromMigrations();
        assertThat(jsonColumns).isNotEmpty();

        List<String> violations = new ArrayList<>();
        try (Stream<Path> sources = Files.walk(
                repositoryRoot().resolve("apps/api/src/main/java")
        )) {
            for (Path source : sources
                    .filter(path -> path.toString().endsWith(".java"))
                    .toList()) {
                String java = Files.readString(source);
                String relative = repositoryRoot().relativize(source).toString();
                Matcher blocks = TEXT_BLOCK.matcher(java);
                while (blocks.find()) {
                    violations.addAll(sqlViolations(
                            relative,
                            blocks.group(1),
                            jsonColumns
                    ));
                }
                violations.addAll(entityViolations(relative, java));
            }
        }

        assertThat(violations)
                .as("escritas json/jsonb sem cast ou sem @JdbcTypeCode")
                .isEmpty();
    }

    private static List<String> sqlViolations(
            String file,
            String sql,
            Set<String> jsonColumns
    ) {
        List<String> violations = new ArrayList<>();
        Matcher insert = INSERT_SHAPE.matcher(sql);
        while (insert.find()) {
            String[] columns = insert.group(2).split(",");
            List<String> values = splitTopLevel(insert.group(3));
            if (columns.length != values.size()) {
                continue;
            }
            for (int i = 0; i < columns.length; i++) {
                String column = columns[i].trim();
                if (jsonColumns.contains(column)
                        && values.get(i).trim().equals("?")) {
                    violations.add(file + " — INSERT " + insert.group(1)
                            + "." + column + " com ? sem cast");
                }
            }
        }
        if (sql.toUpperCase().contains("UPDATE")) {
            Matcher bind = UPDATE_BARE_BIND.matcher(sql);
            while (bind.find()) {
                if (jsonColumns.contains(bind.group(1))) {
                    violations.add(file + " — UPDATE " + bind.group(1)
                            + " com ? sem cast");
                }
            }
        }
        return violations;
    }

    /**
     * Um campo de entidade mapeado para coluna json precisa do JdbcTypeCode na
     * declaração vizinha; a checagem é textual e local de propósito, para o
     * aviso apontar a linha em vez de exigir reflexão sobre o classpath.
     */
    private static List<String> entityViolations(String file, String java) {
        List<String> violations = new ArrayList<>();
        Matcher column = JPA_JSON_FIELD.matcher(java);
        while (column.find()) {
            int start = Math.max(0, column.start() - 200);
            String vicinity = java.substring(start, column.end());
            if (!vicinity.contains("@JdbcTypeCode")) {
                violations.add(file
                        + " — campo JPA em coluna json sem @JdbcTypeCode: "
                        + column.group().replaceAll("\\s+", " "));
            }
        }
        return violations;
    }

    private static Set<String> jsonColumnsFromMigrations() throws IOException {
        Set<String> columns = new HashSet<>();
        try (Stream<Path> migrations = Files.list(repositoryRoot().resolve(
                "apps/api/src/main/resources/db/migration-postgresql"
        ))) {
            for (Path migration : migrations
                    .filter(path -> path.toString().endsWith(".sql"))
                    .toList()) {
                Matcher matcher = JSON_COLUMN.matcher(
                        Files.readString(migration)
                );
                while (matcher.find()) {
                    columns.add(matcher.group(1));
                }
            }
        }
        return columns;
    }

    /** Divide os VALUES no nível de fora, sem quebrar CAST(? AS JSONB). */
    private static List<String> splitTopLevel(String values) {
        List<String> parts = new ArrayList<>();
        int depth = 0;
        StringBuilder current = new StringBuilder();
        for (char c : values.toCharArray()) {
            if (c == '(') {
                depth++;
            }
            if (c == ')') {
                depth--;
            }
            if (c == ',' && depth == 0) {
                parts.add(current.toString());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        parts.add(current.toString());
        return parts;
    }

    private static Path repositoryRoot() {
        Path configured = Path.of(System.getProperty("basedir", ""))
                .toAbsolutePath().normalize();
        for (Path candidate = configured;
                candidate != null;
                candidate = candidate.getParent()) {
            if (Files.isRegularFile(candidate.resolve("apps/api/pom.xml"))) {
                return candidate;
            }
        }
        throw new IllegalStateException(
                "Raiz do repositório não encontrada a partir de " + configured
        );
    }
}
