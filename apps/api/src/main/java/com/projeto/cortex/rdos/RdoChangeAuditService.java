package com.projeto.cortex.rdos;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.Time;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class RdoChangeAuditService {

    private static final DateTimeFormatter DATE_FORMAT =
            DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private final JdbcTemplate jdbcTemplate;

    public RdoChangeAuditService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public RdoAuditSnapshot carregar(String rdoId) {
        try {
            RdoAuditSnapshot cabecalho =
                    jdbcTemplate.queryForObject(
                            """
                            SELECT
                                id,
                                obra_id,
                                programacao_id,
                                numero_rdo,
                                data_rdo,
                                turno,
                                condicao_manha,
                                condicao_tarde,
                                condicao_noite,
                                pluviometria_mm,
                                observacoes,
                                status,
                                versao_linha
                            FROM rdo
                            WHERE id = ?
                            """,
                            (resultSet, rowNumber) ->
                                    mapCabecalho(resultSet),
                            rdoId
                    );

            return cabecalho.withCollections(
                    Map.of(
                            "maoObra",
                            maoObra(rdoId),
                            "equipamentos",
                            equipamentos(rdoId),
                            "materiais",
                            materiais(rdoId),
                            "controlesGeometricos",
                            controlesGeometricos(rdoId)
                    )
            );
        } catch (DataAccessException exception) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "RDO não encontrado: " + rdoId
            );
        }
    }

    public List<Map<String, Object>> calcularAlteracoes(
            RdoAuditSnapshot anterior,
            RdoAuditSnapshot novo
    ) {
        List<Map<String, Object>> changes = new ArrayList<>();

        compareField(
                changes,
                "dataRdo",
                "Data do RDO",
                anterior.field("dataRdo"),
                novo.field("dataRdo")
        );
        compareField(
                changes,
                "turno",
                "Turno",
                anterior.field("turno"),
                novo.field("turno")
        );
        compareField(
                changes,
                "condicaoManha",
                "Condição da manhã",
                anterior.field("condicaoManha"),
                novo.field("condicaoManha")
        );
        compareField(
                changes,
                "condicaoTarde",
                "Condição da tarde",
                anterior.field("condicaoTarde"),
                novo.field("condicaoTarde")
        );
        compareField(
                changes,
                "condicaoNoite",
                "Condição da noite",
                anterior.field("condicaoNoite"),
                novo.field("condicaoNoite")
        );
        compareField(
                changes,
                "pluviometria",
                "Pluviometria",
                anterior.field("pluviometria"),
                novo.field("pluviometria")
        );
        compareField(
                changes,
                "observacoes",
                "Observações",
                anterior.field("observacoes"),
                novo.field("observacoes")
        );
        compareField(
                changes,
                "programacaoId",
                "Programação associada",
                anterior.field("programacaoId"),
                novo.field("programacaoId")
        );
        compareField(
                changes,
                "status",
                "Status",
                anterior.field("status"),
                novo.field("status")
        );

        compareCollection(
                changes,
                "maoObra",
                "Mão de obra",
                anterior.collections().get("maoObra"),
                novo.collections().get("maoObra")
        );
        compareCollection(
                changes,
                "equipamentos",
                "Equipamentos",
                anterior.collections().get("equipamentos"),
                novo.collections().get("equipamentos")
        );
        compareCollection(
                changes,
                "materiais",
                "Materiais",
                anterior.collections().get("materiais"),
                novo.collections().get("materiais")
        );
        compareCollection(
                changes,
                "controlesGeometricos",
                "Controles geométricos",
                anterior.collections().get("controlesGeometricos"),
                novo.collections().get("controlesGeometricos")
        );

        return List.copyOf(changes);
    }

    private RdoAuditSnapshot mapCabecalho(ResultSet resultSet)
            throws java.sql.SQLException {
        Map<String, AuditValue> fields =
                new LinkedHashMap<>();

        fields.put(
                "programacaoId",
                textValue(resultSet.getString("programacao_id"))
        );
        fields.put(
                "dataRdo",
                dateValue(
                        resultSet.getDate("data_rdo")
                                .toLocalDate()
                )
        );
        fields.put(
                "turno",
                textValue(resultSet.getString("turno"))
        );
        fields.put(
                "condicaoManha",
                enumValue(resultSet.getString("condicao_manha"))
        );
        fields.put(
                "condicaoTarde",
                enumValue(resultSet.getString("condicao_tarde"))
        );
        fields.put(
                "condicaoNoite",
                enumValue(resultSet.getString("condicao_noite"))
        );
        fields.put(
                "pluviometria",
                decimalValue(
                        resultSet.getBigDecimal("pluviometria_mm"),
                        " mm"
                )
        );
        fields.put(
                "observacoes",
                textValue(resultSet.getString("observacoes"))
        );
        fields.put(
                "status",
                enumValue(resultSet.getString("status"))
        );

        return new RdoAuditSnapshot(
                resultSet.getString("id"),
                resultSet.getString("obra_id"),
                resultSet.getString("programacao_id"),
                resultSet.getString("numero_rdo"),
                resultSet.getString("status"),
                resultSet.getLong("versao_linha"),
                fields,
                Map.of()
        );
    }

    private List<AuditItem> maoObra(String rdoId) {
        return jdbcTemplate.query(
                """
                SELECT
                    colaborador_id,
                    nome_colaborador,
                    cargo,
                    tipo_vinculo,
                    quantidade,
                    hora_inicio,
                    hora_fim,
                    observacoes
                FROM rdo_mao_obra
                WHERE rdo_id = ?
                ORDER BY cargo, nome_colaborador, id
                """,
                (rs, rowNumber) -> {
                    Map<String, String> values =
                            new LinkedHashMap<>();
                    put(values, "colaboradorId", rs.getString("colaborador_id"));
                    put(values, "nome", rs.getString("nome_colaborador"));
                    put(values, "cargo", rs.getString("cargo"));
                    put(values, "tipo", rs.getString("tipo_vinculo"));
                    put(values, "quantidade", decimal(rs.getBigDecimal("quantidade")));
                    put(values, "horaInicio", time(rs.getTime("hora_inicio")));
                    put(values, "horaFim", time(rs.getTime("hora_fim")));
                    put(values, "observacoes", rs.getString("observacoes"));

                    String identity = firstText(
                            rs.getString("colaborador_id"),
                            rs.getString("nome_colaborador"),
                            rs.getString("cargo")
                    );

                    return new AuditItem(
                            identityKey(identity, values),
                            display(
                                    rs.getString("nome_colaborador"),
                                    rs.getString("cargo"),
                                    rs.getBigDecimal("quantidade")
                            ),
                            values
                    );
                },
                rdoId
        );
    }

    private List<AuditItem> equipamentos(String rdoId) {
        return jdbcTemplate.query(
                """
                SELECT
                    asset_id,
                    prefixo,
                    descricao,
                    tipo_equipamento,
                    tipo_vinculo,
                    quantidade,
                    hora_inicio,
                    hora_fim,
                    observacoes
                FROM rdo_equipamento
                WHERE rdo_id = ?
                ORDER BY prefixo, descricao, id
                """,
                (rs, rowNumber) -> {
                    Map<String, String> values =
                            new LinkedHashMap<>();
                    put(values, "assetId", rs.getString("asset_id"));
                    put(values, "prefixo", rs.getString("prefixo"));
                    put(values, "descricao", rs.getString("descricao"));
                    put(values, "tipo", rs.getString("tipo_equipamento"));
                    put(values, "tipoVinculo", rs.getString("tipo_vinculo"));
                    put(values, "quantidade", decimal(rs.getBigDecimal("quantidade")));
                    put(values, "horaInicio", time(rs.getTime("hora_inicio")));
                    put(values, "horaFim", time(rs.getTime("hora_fim")));
                    put(values, "observacoes", rs.getString("observacoes"));

                    String identity = firstText(
                            rs.getString("asset_id"),
                            rs.getString("prefixo"),
                            rs.getString("descricao")
                    );

                    return new AuditItem(
                            identityKey(identity, values),
                            display(
                                    rs.getString("prefixo"),
                                    rs.getString("descricao"),
                                    rs.getBigDecimal("quantidade")
                            ),
                            values
                    );
                },
                rdoId
        );
    }

    private List<AuditItem> materiais(String rdoId) {
        return jdbcTemplate.query(
                """
                SELECT
                    material_nome,
                    unidade,
                    quantidade_prevista,
                    quantidade_usinada,
                    quantidade_aplicada,
                    quantidade_sobra,
                    nota_fiscal,
                    fornecedor,
                    observacoes
                FROM rdo_material
                WHERE rdo_id = ?
                ORDER BY material_nome, id
                """,
                (rs, rowNumber) -> {
                    Map<String, String> values =
                            new LinkedHashMap<>();
                    put(values, "material", rs.getString("material_nome"));
                    put(values, "unidade", rs.getString("unidade"));
                    put(values, "prevista", decimal(rs.getBigDecimal("quantidade_prevista")));
                    put(values, "usinada", decimal(rs.getBigDecimal("quantidade_usinada")));
                    put(values, "aplicada", decimal(rs.getBigDecimal("quantidade_aplicada")));
                    put(values, "sobra", decimal(rs.getBigDecimal("quantidade_sobra")));
                    put(values, "notaFiscal", rs.getString("nota_fiscal"));
                    put(values, "fornecedor", rs.getString("fornecedor"));
                    put(values, "observacoes", rs.getString("observacoes"));

                    return new AuditItem(
                            identityKey(rs.getString("material_nome"), values),
                            display(
                                    rs.getString("material_nome"),
                                    rs.getString("unidade"),
                                    rs.getBigDecimal("quantidade_aplicada")
                            ),
                            values
                    );
                },
                rdoId
        );
    }

    private List<AuditItem> controlesGeometricos(String rdoId) {
        return jdbcTemplate.query(
                """
                SELECT
                    subtrecho,
                    estaca_inicial,
                    estaca_final,
                    km_inicial,
                    km_final,
                    comprimento_m,
                    largura_m,
                    espessura_1_cm,
                    espessura_2_cm,
                    espessura_3_cm,
                    espessura_media_cm,
                    area_m2,
                    volume_m3,
                    densidade,
                    massa_tonelada,
                    observacoes
                FROM rdo_controle_geometrico
                WHERE rdo_id = ?
                ORDER BY subtrecho, id
                """,
                (rs, rowNumber) -> {
                    Map<String, String> values =
                            new LinkedHashMap<>();
                    put(values, "subtrecho", rs.getString("subtrecho"));
                    put(values, "estacaInicial", rs.getString("estaca_inicial"));
                    put(values, "estacaFinal", rs.getString("estaca_final"));
                    put(values, "kmInicial", rs.getString("km_inicial"));
                    put(values, "kmFinal", rs.getString("km_final"));
                    put(values, "comprimentoM", decimal(rs.getBigDecimal("comprimento_m")));
                    put(values, "larguraM", decimal(rs.getBigDecimal("largura_m")));
                    put(values, "espessura1Cm", decimal(rs.getBigDecimal("espessura_1_cm")));
                    put(values, "espessura2Cm", decimal(rs.getBigDecimal("espessura_2_cm")));
                    put(values, "espessura3Cm", decimal(rs.getBigDecimal("espessura_3_cm")));
                    put(values, "espessuraMediaCm", decimal(rs.getBigDecimal("espessura_media_cm")));
                    put(values, "areaM2", decimal(rs.getBigDecimal("area_m2")));
                    put(values, "volumeM3", decimal(rs.getBigDecimal("volume_m3")));
                    put(values, "densidade", decimal(rs.getBigDecimal("densidade")));
                    put(values, "massaTonelada", decimal(rs.getBigDecimal("massa_tonelada")));
                    put(values, "observacoes", rs.getString("observacoes"));

                    String identity = firstText(
                            rs.getString("subtrecho"),
                            rs.getString("km_inicial"),
                            rs.getString("km_final"),
                            rs.getString("estaca_inicial")
                    );

                    return new AuditItem(
                            identityKey(identity, values),
                            display(
                                    rs.getString("subtrecho"),
                                    rs.getString("km_inicial"),
                                    rs.getBigDecimal("area_m2")
                            ),
                            values
                    );
                },
                rdoId
        );
    }

    private void compareField(
            List<Map<String, Object>> changes,
            String field,
            String label,
            AuditValue before,
            AuditValue after
    ) {
        if (Objects.equals(normalized(before), normalized(after))) {
            return;
        }

        changes.add(
                change(
                        field,
                        label,
                        "ALTERADO",
                        display(before),
                        display(after)
                )
        );
    }

    private void compareCollection(
            List<Map<String, Object>> changes,
            String field,
            String label,
            List<AuditItem> before,
            List<AuditItem> after
    ) {
        Map<String, AuditItem> beforeMap =
                uniqueByKey(before);
        Map<String, AuditItem> afterMap =
                uniqueByKey(after);

        beforeMap.forEach((key, item) -> {
            if (!afterMap.containsKey(key)) {
                changes.add(
                        change(
                                field,
                                label,
                                "REMOVIDO",
                                item.display(),
                                null
                        )
                );
            }
        });

        afterMap.forEach((key, item) -> {
            AuditItem previous = beforeMap.get(key);

            if (previous == null) {
                changes.add(
                        change(
                                field,
                                label,
                                "ADICIONADO",
                                null,
                                item.display()
                        )
                );
                return;
            }

            if (!previous.values().equals(item.values())) {
                changes.add(
                        change(
                                field,
                                label,
                                "ALTERADO",
                                previous.display(),
                                item.display()
                        )
                );
            }
        });
    }

    private Map<String, AuditItem> uniqueByKey(
            List<AuditItem> items
    ) {
        Map<String, Integer> occurrences =
                new LinkedHashMap<>();
        Map<String, AuditItem> result =
                new LinkedHashMap<>();

        List<AuditItem> safeItems =
                items == null ? List.of() : items;

        safeItems.stream()
                .sorted(Comparator.comparing(AuditItem::key))
                .forEach(item -> {
                    int occurrence = occurrences.merge(
                            item.key(),
                            1,
                            Integer::sum
                    );
                    String key = occurrence == 1
                            ? item.key()
                            : item.key() + "#" + occurrence;
                    result.put(key, item);
                });

        return result;
    }

    private Map<String, Object> change(
            String field,
            String label,
            String operation,
            String before,
            String after
    ) {
        Map<String, Object> change =
                new LinkedHashMap<>();

        change.put("campo", field);
        change.put("rotulo", label);
        change.put("operacao", operation);
        change.put("valorAnterior", before);
        change.put("valorNovo", after);

        return change;
    }

    private AuditValue textValue(String value) {
        String normalized = normalizeText(value);
        return new AuditValue(normalized, normalized);
    }

    private AuditValue enumValue(String value) {
        String normalized = normalizeText(value);
        return new AuditValue(
                normalized,
                readableEnum(normalized)
        );
    }

    private AuditValue dateValue(LocalDate value) {
        return value == null
                ? new AuditValue(null, null)
                : new AuditValue(
                        value.toString(),
                        DATE_FORMAT.format(value)
                );
    }

    private AuditValue decimalValue(
            BigDecimal value,
            String suffix
    ) {
        if (value == null) {
            return new AuditValue(null, null);
        }

        String decimal = decimal(value);

        return new AuditValue(decimal, decimal + suffix);
    }

    private String normalized(AuditValue value) {
        return value == null ? null : value.normalized();
    }

    private String display(AuditValue value) {
        return value == null ? null : value.display();
    }

    private String normalizeText(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }

        return value.trim();
    }

    private String readableEnum(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }

        String normalized = value.replace('_', ' ')
                .toLowerCase(Locale.ROOT);

        return normalized.substring(0, 1).toUpperCase(Locale.ROOT)
                + normalized.substring(1);
    }

    private String decimal(BigDecimal value) {
        return value == null
                ? null
                : value.stripTrailingZeros().toPlainString();
    }

    private String time(Time value) {
        if (value == null) {
            return null;
        }

        LocalTime localTime = value.toLocalTime();

        return localTime.toString();
    }

    private void put(
            Map<String, String> values,
            String key,
            String value
    ) {
        if (value != null && !value.isBlank()) {
            values.put(key, value.trim());
        }
    }

    private String display(
            String primary,
            String secondary,
            BigDecimal quantity
    ) {
        StringBuilder display =
                new StringBuilder(
                        firstText(primary, secondary, "Item")
                );

        if (secondary != null
                && !secondary.isBlank()
                && (primary == null || !primary.equals(secondary))) {
            display.append(" - ")
                    .append(secondary.trim());
        }

        if (quantity != null) {
            display.append(" (")
                    .append(decimal(quantity))
                    .append(")");
        }

        return display.toString();
    }

    private String firstText(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }

        return null;
    }

    private String identityKey(
            String identity,
            Map<String, String> values
    ) {
        String preferred =
                identity == null || identity.isBlank()
                        ? values.toString()
                        : identity;

        return preferred.toLowerCase(Locale.ROOT);
    }

    public record RdoAuditSnapshot(
            String id,
            String obraId,
            String programacaoId,
            String numeroRdo,
            String status,
            long versaoLinha,
            Map<String, AuditValue> fields,
            Map<String, List<AuditItem>> collections
    ) {

        AuditValue field(String key) {
            return fields.get(key);
        }

        RdoAuditSnapshot withCollections(
                Map<String, List<AuditItem>> newCollections
        ) {
            return new RdoAuditSnapshot(
                    id,
                    obraId,
                    programacaoId,
                    numeroRdo,
                    status,
                    versaoLinha,
                    fields,
                    newCollections
            );
        }
    }

    public record AuditValue(
            String normalized,
            String display
    ) {
    }

    public record AuditItem(
            String key,
            String display,
            Map<String, String> values
    ) {
    }
}
