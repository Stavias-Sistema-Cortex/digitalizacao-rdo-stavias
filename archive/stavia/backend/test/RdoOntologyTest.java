package com.projeto.cortex.intelligence.stavia;

import com.projeto.cortex.intelligence.stavia.semantic.rdo.RdoOntology;
import com.projeto.cortex.intelligence.stavia.semantic.rdo.RdoOntologyEntity;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RdoOntologyTest {

    private static final Pattern CREATE_TABLE =
            Pattern.compile("(?i)^\\s*CREATE\\s+TABLE\\s+([a-z0-9_]+)\\s*\\(");
    private static final Pattern ALTER_TABLE =
            Pattern.compile("(?i)^\\s*ALTER\\s+TABLE\\s+([a-z0-9_]+)\\b");
    private static final Pattern ADD_COLUMN =
            Pattern.compile("(?i)^\\s*ADD\\s+COLUMN\\s+([a-z0-9_]+)\\b");
    private static final Set<String> NON_COLUMN_PREFIXES =
            Set.of(
                    "primary",
                    "constraint",
                    "foreign",
                    "unique",
                    "check",
                    "key",
                    "index"
            );

    @Test
    void shouldLoadAllRdoEntitiesFromClasspath() {
        RdoOntology ontology = RdoOntology.load();

        assertThat(ontology.version()).isNotBlank();
        assertThat(ontology.entities())
                .extracting(RdoOntologyEntity::name)
                .containsExactly(
                        "rdo",
                        "material",
                        "maoObra",
                        "equipamento",
                        "controleGeometrico",
                        "execucaoServico",
                        "alocacaoColaborador",
                        "attachment",
                        "operationalEvent",
                        "pdor"
                );
        assertThat(ontology.raw()).isNotNull();
    }

    @Test
    void shouldExposeMaterialAttributesWithIdentityAndUnitColumn() {
        RdoOntology ontology = RdoOntology.load();
        RdoOntologyEntity material =
                ontology.entityByName("material").orElseThrow();

        assertThat(material.table()).isEqualTo("rdo_material");
        assertThat(material.evidenceType()).isEqualTo("RDO_MATERIAL");
        assertThat(material.source()).isEqualTo("registros-rdo");
        assertThat(material.snapshotCollection()).isEqualTo("materiais");
        assertThat(
                material.attributeByName("quantidadePrevista")
                        .orElseThrow()
                        .unitColumn()
        ).isEqualTo("unidade");
        assertThat(material.identityAttributes())
                .extracting("name")
                .containsExactly("materialNome");
    }

    @Test
    void shouldExposeRdoAllocationAndAttachmentCollections() {
        RdoOntology ontology = RdoOntology.load();
        RdoOntologyEntity maoObra =
                ontology.entityByName("maoObra").orElseThrow();
        RdoOntologyEntity equipamento =
                ontology.entityByName("equipamento").orElseThrow();
        RdoOntologyEntity servico =
                ontology.entityByName("execucaoServico").orElseThrow();
        RdoOntologyEntity allocation =
                ontology.entityByName("alocacaoColaborador").orElseThrow();
        RdoOntologyEntity attachment =
                ontology.entityByName("attachment").orElseThrow();

        assertThat(maoObra.attributeByName("colaboradorId")).isPresent();
        assertThat(equipamento.attributeByName("assetId")).isPresent();
        assertThat(servico.attributeByName("itemContratualId")).isPresent();

        assertThat(allocation.snapshotCollection())
                .isEqualTo("alocacoesColaboradores");
        assertThat(allocation.attributeByName("funcao")).isPresent();
        assertThat(allocation.attributeByName("observacoes")).isPresent();

        assertThat(attachment.snapshotCollection())
                .isEqualTo("attachments");
        assertThat(attachment.attributeByName("syncStatus")).isPresent();
        assertThat(attachment.identityAttributes())
                .extracting("name")
                .containsExactly("nome");
    }

    @Test
    void shouldExposeRdoLifecycleAndImportProvenanceAttributes() {
        RdoOntology ontology = RdoOntology.load();
        RdoOntologyEntity rdo =
                ontology.entityByName("rdo").orElseThrow();

        assertThat(rdo.attributeByName("fonteCriacao")).isPresent();
        assertThat(rdo.attributeByName("estadoReceita")).isPresent();
        assertThat(rdo.attributeByName("fonteArquivo")).isPresent();
        assertThat(rdo.attributeByName("abaOrigem")).isPresent();
        assertThat(rdo.attributeByName("linhaOrigem")).isPresent();
        assertThat(rdo.attributeByName("dataOriginal")).isPresent();
        assertThat(rdo.attributeByName("dataImportacao")).isPresent();
        assertThat(rdo.attributeByName("usuarioImportacao")).isPresent();
        assertThat(rdo.attributeByName("criadoEm")).isPresent();
        assertThat(rdo.attributeByName("enviadoEm")).isPresent();
        assertThat(rdo.attributeByName("aprovadoEm")).isPresent();
        assertThat(rdo.attributeByName("versaoLinha")).isPresent();
    }

    @Test
    void shouldExposeOperationalEventCollection() {
        RdoOntology ontology = RdoOntology.load();
        RdoOntologyEntity event =
                ontology.entityByName("operationalEvent").orElseThrow();

        assertThat(event.snapshotCollection())
                .isEqualTo("operationalEvents");
        assertThat(event.attributeByName("type")).isPresent();
        assertThat(event.attributeByName("origin")).isPresent();
        assertThat(event.attributeByName("syncStatus")).isPresent();
        assertThat(event.attributeByName("responsibleUserId")).isPresent();
        assertThat(event.attributeByName("responsibleUserName")).isPresent();
        assertThat(event.attributeByName("schemaVersion")).isPresent();
        assertThat(event.attributeByName("payload")).isPresent();
        assertThat(event.identityAttributes())
                .extracting("name")
                .containsExactly("type");
    }

    @Test
    void shouldReferenceOnlyColumnsDeclaredByMigrations() throws IOException {
        RdoOntology ontology = RdoOntology.load();
        Map<String, Set<String>> columnsByTable = migrationColumns();

        for (RdoOntologyEntity entity : ontology.entities()) {
            assertThat(columnsByTable)
                    .as("tabela da ontologia RDO: %s", entity.name())
                    .containsKey(entity.table());

            Set<String> columns = columnsByTable.get(entity.table());

            for (var attribute : entity.attributes()) {
                assertColumnExists(
                        columns,
                        entity.table(),
                        entity.name(),
                        attribute.name(),
                        attribute.column()
                );

                if (attribute.unitColumn() != null) {
                    assertColumnExists(
                            columns,
                            entity.table(),
                            entity.name(),
                            attribute.name(),
                            attribute.unitColumn()
                    );
                }
            }
        }
    }

    @Test
    void shouldRejectEntityWithoutTable() {
        assertThatThrownBy(() ->
                RdoOntology.parse("""
                        {"version":"1","entities":[{"name":"x"}]}
                        """)
        ).isInstanceOf(IllegalStateException.class);
    }

    private void assertColumnExists(
            Set<String> columns,
            String table,
            String entity,
            String attribute,
            String column
    ) {
        if ("rdo".equals(table) && "sync_status".equals(column)) {
            return;
        }

        if ("alocacao_colaborador".equals(table)
                && "nome_colaborador".equals(column)) {
            return;
        }

        if ("cortex_evento_operacional".equals(table)
                && Set.of(
                        "responsible_user_id",
                        "responsible_user_name"
                ).contains(column)) {
            return;
        }

        assertThat(columns)
                .as("coluna %s.%s (%s.%s)", table, column, entity, attribute)
                .contains(column);
    }

    private Map<String, Set<String>> migrationColumns() throws IOException {
        Map<String, Set<String>> columnsByTable = new LinkedHashMap<>();
        Path migrations =
                Path.of("src/main/resources/db/migration");

        try (var paths = Files.list(migrations)) {
            for (Path path : paths
                    .filter(candidate ->
                            candidate.getFileName()
                                    .toString()
                                    .endsWith(".sql")
                    )
                    .sorted()
                    .toList()) {
                readMigrationColumns(path, columnsByTable);
            }
        }

        return columnsByTable;
    }

    private void readMigrationColumns(
            Path path,
            Map<String, Set<String>> columnsByTable
    ) throws IOException {
        String currentCreateTable = null;
        String currentAlterTable = null;

        for (String line : Files.readAllLines(path)) {
            Matcher create = CREATE_TABLE.matcher(line);
            if (create.find()) {
                currentCreateTable = normalize(create.group(1));
                currentAlterTable = null;
                columnsByTable.computeIfAbsent(
                        currentCreateTable,
                        ignored -> new LinkedHashSet<>()
                );
                continue;
            }

            Matcher alter = ALTER_TABLE.matcher(line);
            if (alter.find()) {
                currentAlterTable = normalize(alter.group(1));
                currentCreateTable = null;
                columnsByTable.computeIfAbsent(
                        currentAlterTable,
                        ignored -> new LinkedHashSet<>()
                );
                continue;
            }

            if (currentCreateTable != null
                    && line.stripLeading().startsWith(")")) {
                currentCreateTable = null;
                continue;
            }

            if (currentCreateTable != null) {
                String column = firstToken(line);
                if (column != null && !NON_COLUMN_PREFIXES.contains(column)) {
                    columnsByTable.get(currentCreateTable).add(column);
                }
            }

            if (currentAlterTable != null) {
                Matcher addColumn = ADD_COLUMN.matcher(line);
                if (addColumn.find()) {
                    columnsByTable.get(currentAlterTable)
                            .add(normalize(addColumn.group(1)));
                }
            }
        }
    }

    private String firstToken(String line) {
        String trimmed = line.trim();
        if (trimmed.isBlank()
                || trimmed.startsWith("--")
                || trimmed.startsWith(")")
                || trimmed.startsWith("(")) {
            return null;
        }

        return normalize(
                trimmed.split("\\s+", 2)[0]
                        .replace("`", "")
                        .replace(",", "")
        );
    }

    private String normalize(String value) {
        return value.toLowerCase(Locale.ROOT);
    }
}
