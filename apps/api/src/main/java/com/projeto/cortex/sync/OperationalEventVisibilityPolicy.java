package com.projeto.cortex.sync;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/** One fail-closed SQL policy shared by offline Sync and Operational Memory. */
public final class OperationalEventVisibilityPolicy {

    private static final List<String> GLOBAL_REFERENCE_TYPES =
            List.of("ATIVO", "EQUIPAMENTO", "SERVICO");
    private static final List<String> FINANCIAL_TYPES = List.of(
            "ITEM_CONTRATUAL", "PREVISAO_FINANCEIRA", "PDOR", "CENTRO_CUSTO",
            "FORNECEDOR", "SOLICITACAO_COMPRA", "COMPRA", "PEDIDO_COMPRA",
            "REGRA_APROVACAO", "DECISAO_APROVACAO", "NOTA_FISCAL", "LANCAMENTO",
            "LIQUIDACAO", "LANCAMENTO_FINANCEIRO", "PAGAMENTO", "COBRANCA_EMAIL",
            "UNIDADE_FINANCEIRA", "RATEIO_FINANCEIRO", "COMPRA_ITEM",
            "DOCUMENTO_FISCAL"
    );
    private static final List<String> ALFA_ONLY_FINANCIAL_TYPES =
            List.of("PERMISSAO_FINANCEIRA");
    private static final List<String> MESSAGE_TYPES =
            List.of("CONVERSA", "MENSAGEM", "MENSAGEM_ANEXO");

    private OperationalEventVisibilityPolicy() {
    }

    /**
     * Tabela que o pull consulta, usada como qualificador das colunas.
     *
     * <p>A Memória sempre passou um alias; o sync passava vazio, e sem
     * qualificador o predicado emitia {@code restricted_visibility.evento_id =
     * id} dentro de uma subconsulta sobre {@code cortex_evento_visibilidade} —
     * que também tem uma coluna {@code id}. O PostgreSQL recusa isso como
     * referência ambígua, e o pull inteiro virava 500.
     *
     * <p>Só o Beta chegava aqui: para Alfa {@code forSync} devolve predicado
     * vazio e a consulta é trivial. Quem administra o sistema nunca via o erro,
     * e quem estava em campo não via outra coisa.
     */
    private static final String SYNC_EVENT_TABLE = "cortex_evento_operacional";

    public static SqlPredicate forSync(
            Optional<Set<String>> allowedWorksites,
            Set<String> financialWorksites,
            Set<String> financialUnits,
            String userId
    ) {
        if (allowedWorksites.isEmpty()) {
            return new SqlPredicate("", List.of());
        }
        return beta(
                SYNC_EVENT_TABLE,
                allowedWorksites.orElseThrow(),
                financialWorksites,
                financialUnits,
                userId,
                GlobalNullVisibility.REFERENCE_CATALOG
        );
    }

    public static SqlPredicate forMemory(
            boolean global,
            Set<String> allowedWorksites,
            Set<String> financialWorksites,
            Set<String> financialUnits,
            String userId,
            String alias
    ) {
        if (global) {
            return new SqlPredicate("", List.of());
        }
        return beta(
                alias,
                allowedWorksites,
                financialWorksites,
                financialUnits,
                userId,
                GlobalNullVisibility.OWN_EVENTS
        );
    }

    private static SqlPredicate beta(
            String alias,
            Set<String> allowedWorksites,
            Set<String> financialWorksites,
            Set<String> financialUnits,
            String userId,
            GlobalNullVisibility globalNullVisibility
    ) {
        String prefix = alias == null || alias.isBlank() ? "" : alias.trim() + ".";
        TreeSet<String> worksites = sorted(allowedWorksites);
        TreeSet<String> financeWorksites = sorted(financialWorksites);
        TreeSet<String> units = sorted(financialUnits);
        List<Object> parameters = new ArrayList<>();
        List<String> restrictedTypes = new ArrayList<>();
        restrictedTypes.addAll(FINANCIAL_TYPES);
        restrictedTypes.addAll(ALFA_ONLY_FINANCIAL_TYPES);
        restrictedTypes.addAll(MESSAGE_TYPES);

        StringBuilder regular = new StringBuilder("(")
                .append(prefix).append("tipo_entidade NOT IN (")
                .append(placeholders(restrictedTypes.size())).append(")")
                .append(" AND NOT EXISTS (")
                .append("SELECT 1 FROM cortex_evento_visibilidade restricted_visibility ")
                .append("WHERE restricted_visibility.evento_id = ")
                .append(prefix).append("id AND restricted_visibility.escopo_tipo IN (")
                .append("'CONVERSATION_PARTICIPANT', 'FINANCIAL_CAPABILITY', 'GLOBAL_ALFA'))")
                .append(" AND (");
        parameters.addAll(restrictedTypes);
        if (!worksites.isEmpty()) {
            regular.append(prefix).append("obra_id IN (")
                    .append(placeholders(worksites.size())).append(") OR ");
            parameters.addAll(worksites);
        }
        if (globalNullVisibility == GlobalNullVisibility.REFERENCE_CATALOG) {
            regular.append("(").append(prefix).append("obra_id IS NULL AND ")
                    .append(prefix).append("tipo_entidade IN (")
                    .append(placeholders(GLOBAL_REFERENCE_TYPES.size())).append("))");
            parameters.addAll(GLOBAL_REFERENCE_TYPES);
        } else {
            regular.append("(").append(prefix).append("obra_id IS NULL AND ")
                    .append(prefix).append("usuario_id = ?)");
            parameters.add(requireUser(userId));
        }
        regular.append("))");

        StringBuilder condition = new StringBuilder(" AND (").append(regular);
        if (!financeWorksites.isEmpty()) {
            condition.append(" OR (").append(prefix).append("tipo_entidade IN (")
                    .append(placeholders(FINANCIAL_TYPES.size())).append(") AND ")
                    .append(prefix).append("obra_id IN (")
                    .append(placeholders(financeWorksites.size())).append("))");
            parameters.addAll(FINANCIAL_TYPES);
            parameters.addAll(financeWorksites);
        }
        if (!units.isEmpty()) {
            condition.append(" OR (").append(prefix).append("tipo_entidade IN (")
                    .append(placeholders(FINANCIAL_TYPES.size())).append(") AND ((")
                    .append(prefix).append("tipo_entidade = 'UNIDADE_FINANCEIRA' AND ")
                    .append(prefix).append("entidade_id IN (")
                    .append(placeholders(units.size())).append(")) OR EXISTS (")
                    .append("SELECT 1 FROM cortex_relacao relacao WHERE ")
                    .append("relacao.origem_tipo = ").append(prefix).append("tipo_entidade ")
                    .append("AND relacao.origem_id = ").append(prefix).append("entidade_id ")
                    .append("AND relacao.destino_tipo = 'UNIDADE_FINANCEIRA' ")
                    .append("AND relacao.destino_id IN (")
                    .append(placeholders(units.size()))
                    .append(") AND relacao.ativa = TRUE)))");
            parameters.addAll(FINANCIAL_TYPES);
            parameters.addAll(units);
            parameters.addAll(units);
        }

        if (userId != null && !userId.isBlank()) {
            SqlPredicate conversationVisibility = activeConversationVisibility(
                    "cv",
                    userId
            );
            condition.append(" OR (").append(prefix).append("tipo_entidade IN (")
                    .append(placeholders(MESSAGE_TYPES.size())).append(") AND EXISTS (")
                    .append("SELECT 1 FROM cortex_evento_visibilidade cev ")
                    .append("JOIN conversa cv ON cv.id = cev.escopo_id ")
                    .append("WHERE cev.evento_id = ").append(prefix).append("id ")
                    .append("AND cev.escopo_tipo = 'CONVERSATION_PARTICIPANT'")
                    .append(conversationVisibility.sql())
                    .append("))");
            parameters.addAll(MESSAGE_TYPES);
            parameters.addAll(conversationVisibility.parameters());
        }
        condition.append(")");
        return new SqlPredicate(condition.toString(), List.copyOf(parameters));
    }

    /** Predicate for a conversation alias, shared with Memory scope fingerprinting. */
    public static SqlPredicate activeConversationVisibility(
            String conversationAlias,
            String userId
    ) {
        String alias = conversationAlias == null || conversationAlias.isBlank()
                ? "cv"
                : conversationAlias.trim();
        String actor = requireUser(userId);
        String sql = " AND " + alias + ".status = 'ATIVA'"
                + " AND " + alias + ".deletado_em IS NULL"
                + " AND EXISTS (SELECT 1 FROM conversa_participante cp"
                + " WHERE cp.conversa_id = " + alias + ".id"
                + " AND cp.colaborador_id = ? AND cp.status = 'ATIVO'"
                + " AND cp.removido_em IS NULL AND cp.deletado_em IS NULL)"
                + " AND (" + alias + ".tipo IN ('DIRETA', 'GRUPO')"
                + " OR (" + alias + ".tipo = 'OBRA' AND EXISTS ("
                + "SELECT 1 FROM vinculo_colaborador_obra v"
                + " WHERE v.obra_id = " + alias + ".obra_id"
                + " AND v.colaborador_id = ? AND v.status = 'ATIVO'))"
                + " OR (" + alias + ".tipo = 'EQUIPE' AND EXISTS ("
                + "SELECT 1 FROM equipe e"
                + " JOIN equipe_membro em ON em.equipe_id = e.id"
                + " JOIN vinculo_colaborador_obra v ON v.obra_id = e.obra_id"
                + " WHERE e.id = " + alias + ".equipe_id"
                + " AND e.obra_id = " + alias + ".obra_id"
                + " AND e.status = 'ATIVA' AND e.deletado_em IS NULL"
                + " AND em.colaborador_id = ? AND em.status = 'ATIVO'"
                + " AND em.removido_em IS NULL AND em.deletado_em IS NULL"
                + " AND v.colaborador_id = ? AND v.status = 'ATIVO')))";
        return new SqlPredicate(sql, List.of(actor, actor, actor, actor));
    }

    private static TreeSet<String> sorted(Set<String> values) {
        TreeSet<String> result = new TreeSet<>();
        if (values != null) {
            values.stream()
                    .filter(value -> value != null && !value.isBlank())
                    .map(String::trim)
                    .forEach(result::add);
        }
        return result;
    }

    private static String requireUser(String userId) {
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("User is required for scoped event visibility.");
        }
        return userId.trim();
    }

    private static String placeholders(int count) {
        return String.join(",", Collections.nCopies(count, "?"));
    }

    private enum GlobalNullVisibility {
        REFERENCE_CATALOG,
        OWN_EVENTS
    }

    public record SqlPredicate(String sql, List<Object> parameters) {
        public SqlPredicate {
            sql = sql == null ? "" : sql;
            parameters = parameters == null ? List.of() : List.copyOf(parameters);
        }
    }
}
