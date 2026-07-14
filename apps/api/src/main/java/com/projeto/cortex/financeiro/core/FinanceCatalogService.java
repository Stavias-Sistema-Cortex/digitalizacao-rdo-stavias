package com.projeto.cortex.financeiro.core;

import static com.projeto.cortex.financeiro.core.FinanceDtos.*;

import com.projeto.cortex.auth.CurrentUserService;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class FinanceCatalogService {

    private final JdbcTemplate jdbc;
    private final CurrentUserService currentUser;
    private final FinanceOntologyProjector ontology;

    public FinanceCatalogService(
            JdbcTemplate jdbc,
            CurrentUserService currentUser,
            FinanceOntologyProjector ontology
    ) {
        this.jdbc = jdbc;
        this.currentUser = currentUser;
        this.ontology = ontology;
    }

    @Transactional
    public SupplierResponse saveSupplier(
            String obraId,
            SupplierRequest request,
            FinanceAuditContext audit
    ) {
        requireActor(audit);
        String scope = FinanceValidation.uuid(obraId, "obraId");
        if (request == null) {
            throw FinanceValidation.badRequest("Fornecedor é obrigatório.");
        }
        String cnpj = FinanceValidation.cnpj(request.cnpj());
        String id = jdbc.query(
                "SELECT id FROM finance_fornecedor WHERE cnpj_normalizado = ? LIMIT 1 FOR UPDATE",
                rs -> rs.next() ? rs.getString(1) : null,
                cnpj
        );
        boolean created = id == null;
        if (created) {
            id = request.id() == null || request.id().isBlank()
                    ? UUID.randomUUID().toString()
                    : FinanceValidation.uuid(request.id(), "id");
            jdbc.update(
                    """
                    INSERT INTO finance_fornecedor (
                        id, razao_social, nome_fantasia, cnpj_normalizado,
                        email_financeiro, telefone, criado_por
                    ) VALUES (?, ?, ?, ?, ?, ?, ?)
                    """,
                    id,
                    FinanceValidation.requiredText(
                            request.razaoSocial(), "razaoSocial", 255
                    ),
                    FinanceValidation.optionalText(
                            request.nomeFantasia(), "nomeFantasia", 255
                    ),
                    cnpj,
                    FinanceValidation.email(request.emailFinanceiro()),
                    FinanceValidation.optionalText(
                            request.telefone(), "telefone", 40
                    ),
                    audit.actorId()
            );
        } else {
            preventScopedMutationOfSharedSupplier(id, scope, audit.actorId());
            jdbc.update(
                    """
                    UPDATE finance_fornecedor
                    SET razao_social = ?, nome_fantasia = ?,
                        email_financeiro = ?, telefone = ?, status = 'ATIVO',
                        arquivado_em = NULL, arquivado_por = NULL,
                        atualizado_por = ?, versao_linha = versao_linha + 1
                    WHERE id = ?
                    """,
                    FinanceValidation.requiredText(
                            request.razaoSocial(), "razaoSocial", 255
                    ),
                    FinanceValidation.optionalText(
                            request.nomeFantasia(), "nomeFantasia", 255
                    ),
                    FinanceValidation.email(request.emailFinanceiro()),
                    FinanceValidation.optionalText(
                            request.telefone(), "telefone", 40
                    ),
                    audit.actorId(),
                    id
            );
        }
        jdbc.update(
                """
                INSERT INTO finance_fornecedor_obra (
                    id, fornecedor_id, obra_id, criado_por
                ) VALUES (?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE status = 'ATIVO', arquivado_em = NULL,
                    arquivado_por = NULL
                """,
                UUID.randomUUID().toString(),
                id,
                scope,
                audit.actorId()
        );
        SupplierResponse response = supplier(id);
        ontology.success(
                "FORNECEDOR",
                id,
                response.razaoSocial(),
                created ? "FORNECEDOR_CRIADO" : "FORNECEDOR_ATUALIZADO",
                scope,
                audit,
                Map.of(),
                Map.of(
                        "razaoSocial", response.razaoSocial(),
                        "status", response.status()
                ),
                Map.of()
        );
        return response;
    }

    public List<SupplierResponse> listSuppliers(
            String obraId,
            String query
    ) {
        String scope = FinanceValidation.uuid(obraId, "obraId");
        String normalized = query == null ? "" : query.strip();
        return jdbc.query(
                """
                SELECT f.*
                FROM finance_fornecedor f
                JOIN finance_fornecedor_obra fo ON fo.fornecedor_id = f.id
                WHERE fo.obra_id = ? AND fo.status = 'ATIVO'
                  AND f.status = 'ATIVO'
                  AND (? = '' OR f.razao_social LIKE CONCAT('%', ?, '%')
                       OR COALESCE(f.nome_fantasia, '') LIKE CONCAT('%', ?, '%')
                       OR f.cnpj_normalizado LIKE CONCAT('%', ?, '%'))
                ORDER BY f.razao_social, f.id
                LIMIT 200
                """,
                this::mapSupplier,
                scope,
                normalized,
                normalized,
                normalized,
                normalized.replaceAll("\\D", "")
        );
    }

    @Transactional
    public CostCenterResponse saveCostCenter(
            String obraId,
            CostCenterRequest request,
            FinanceAuditContext audit
    ) {
        requireActor(audit);
        String scope = FinanceValidation.uuid(obraId, "obraId");
        if (request == null) {
            throw FinanceValidation.badRequest("Centro de custo é obrigatório.");
        }
        String code = FinanceValidation.requiredText(
                request.codigo(), "codigo", 80
        );
        String id = resolveScopedNaturalKeyId(
                "finance_centro_custo", scope, code, request.id(),
                "Já existe outro centro de custo com este código na obra."
        );
        int updated = jdbc.update(
                """
                INSERT INTO finance_centro_custo (
                    id, obra_id, codigo, nome, descricao, responsavel_id,
                    criado_por
                ) VALUES (?, ?, ?, ?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE codigo = VALUES(codigo),
                    nome = VALUES(nome), descricao = VALUES(descricao),
                    responsavel_id = VALUES(responsavel_id), status = 'ATIVO',
                    arquivado_em = NULL, arquivado_por = NULL,
                    atualizado_por = VALUES(criado_por),
                    versao_linha = versao_linha + 1
                """,
                id,
                scope,
                code,
                FinanceValidation.requiredText(request.nome(), "nome", 255),
                FinanceValidation.optionalText(request.descricao(), "descricao", 1000),
                FinanceValidation.optionalUuid(request.responsavelId(), "responsavelId"),
                audit.actorId()
        );
        if (updated < 1) {
            throw new IllegalStateException("Centro de custo não foi persistido.");
        }
        CostCenterResponse response = costCenter(id);
        ontology.success(
                "CENTRO_CUSTO", id, response.nome(),
                "CENTRO_CUSTO_SALVO", scope, audit, Map.of(),
                Map.of("codigo", response.codigo(), "nome", response.nome()),
                Map.of()
        );
        return response;
    }

    public List<CostCenterResponse> listCostCenters(String obraId) {
        String scope = FinanceValidation.uuid(obraId, "obraId");
        return jdbc.query(
                """
                SELECT * FROM finance_centro_custo
                WHERE obra_id = ? AND status = 'ATIVO'
                ORDER BY codigo, nome
                """,
                this::mapCostCenter,
                scope
        );
    }

    @Transactional
    public CategoryResponse saveCategory(
            String obraId,
            CategoryRequest request,
            FinanceAuditContext audit
    ) {
        requireActor(audit);
        String scope = FinanceValidation.uuid(obraId, "obraId");
        if (request == null) {
            throw FinanceValidation.badRequest("Categoria é obrigatória.");
        }
        String code = FinanceValidation.requiredText(
                request.codigo(), "codigo", 80
        );
        String id = resolveScopedNaturalKeyId(
                "finance_categoria", scope, code, request.id(),
                "Já existe outra categoria com este código na obra."
        );
        jdbc.update(
                """
                INSERT INTO finance_categoria (
                    id, obra_id, codigo, nome, descricao, criado_por
                ) VALUES (?, ?, ?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE codigo = VALUES(codigo),
                    nome = VALUES(nome), descricao = VALUES(descricao),
                    status = 'ATIVA', arquivado_em = NULL, arquivado_por = NULL,
                    atualizado_por = VALUES(criado_por),
                    versao_linha = versao_linha + 1
                """,
                id,
                scope,
                code,
                FinanceValidation.requiredText(request.nome(), "nome", 255),
                FinanceValidation.optionalText(request.descricao(), "descricao", 1000),
                audit.actorId()
        );
        CategoryResponse response = category(id);
        ontology.success(
                "CATEGORIA_FINANCEIRA", id, response.nome(),
                "CATEGORIA_FINANCEIRA_SALVA", scope, audit, Map.of(),
                Map.of("codigo", response.codigo(), "nome", response.nome()),
                Map.of()
        );
        return response;
    }

    public List<CategoryResponse> listCategories(String obraId) {
        String scope = FinanceValidation.uuid(obraId, "obraId");
        return jdbc.query(
                """
                SELECT * FROM finance_categoria
                WHERE obra_id = ? AND status = 'ATIVA'
                ORDER BY codigo, nome
                """,
                this::mapCategory,
                scope
        );
    }

    @Transactional
    public StatusDefinitionResponse saveStatus(
            String obraId,
            StatusDefinitionRequest request,
            FinanceAuditContext audit
    ) {
        requireActor(audit);
        String scope = FinanceValidation.uuid(obraId, "obraId");
        if (request == null || request.ordem() == null || request.ordem() < 0) {
            throw FinanceValidation.badRequest("Definição de status inválida.");
        }
        String aggregate = aggregate(request.agregadoTipo());
        String code = FinanceValidation.requiredText(request.codigo(), "codigo", 60)
                .toUpperCase(Locale.ROOT);
        if (!code.matches("[A-Z][A-Z0-9_]{1,59}")) {
            throw FinanceValidation.badRequest("Código de status inválido.");
        }
        String requestedId = request.id() == null || request.id().isBlank()
                ? null : FinanceValidation.uuid(request.id(), "id");
        String existingId = jdbc.query(
                "SELECT id FROM finance_status_definicao WHERE obra_id = ? AND agregado_tipo = ? AND codigo = ? LIMIT 1",
                rs -> rs.next() ? rs.getString(1) : null,
                scope,
                aggregate,
                code
        );
        if (existingId != null && requestedId != null
                && !existingId.equals(requestedId)) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Já existe outro status com este código na obra."
            );
        }
        String id = existingId != null ? existingId
                : requestedId != null ? requestedId : UUID.randomUUID().toString();
        jdbc.update(
                """
                INSERT INTO finance_status_definicao (
                    id, obra_id, agregado_tipo, codigo, nome, descricao,
                    ordem, terminal, criado_por
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE nome = VALUES(nome),
                    descricao = VALUES(descricao), ordem = VALUES(ordem),
                    terminal = VALUES(terminal), ativo = TRUE,
                    arquivado_em = NULL, atualizado_por = VALUES(criado_por),
                    versao_linha = versao_linha + 1
                """,
                id, scope, aggregate, code,
                FinanceValidation.requiredText(request.nome(), "nome", 120),
                FinanceValidation.optionalText(request.descricao(), "descricao", 500),
                request.ordem(), request.terminal(), audit.actorId()
        );
        StatusDefinitionResponse response = status(id);
        ontology.success(
                "STATUS_FINANCEIRO", id, response.nome(),
                "STATUS_FINANCEIRO_CONFIGURADO", scope, audit, Map.of(),
                Map.of("codigo", response.codigo(), "agregado", aggregate),
                Map.of()
        );
        return response;
    }

    public List<StatusDefinitionResponse> listStatuses(
            String obraId,
            String aggregateType
    ) {
        String scope = FinanceValidation.uuid(obraId, "obraId");
        String aggregate = aggregate(aggregateType);
        return jdbc.query(
                """
                SELECT * FROM finance_status_definicao
                WHERE obra_id = ? AND agregado_tipo = ? AND ativo = TRUE
                ORDER BY ordem, codigo
                """,
                this::mapStatus,
                scope,
                aggregate
        );
    }

    @Transactional
    public void saveTransition(
            String obraId,
            StatusTransitionRequest request,
            FinanceAuditContext audit
    ) {
        requireActor(audit);
        String scope = FinanceValidation.uuid(obraId, "obraId");
        if (request == null) {
            throw FinanceValidation.badRequest("Transição é obrigatória.");
        }
        try {
            jdbc.update(
                    """
                    INSERT INTO finance_status_transicao (
                        id, obra_id, agregado_tipo, status_origem_id,
                        status_destino_id, exige_aprovacao, criado_por
                    ) VALUES (?, ?, ?, ?, ?, ?, ?)
                    ON DUPLICATE KEY UPDATE exige_aprovacao = VALUES(exige_aprovacao),
                        ativo = TRUE, atualizado_por = VALUES(criado_por),
                        versao_linha = versao_linha + 1
                    """,
                    request.id() == null || request.id().isBlank()
                            ? UUID.randomUUID().toString()
                            : FinanceValidation.uuid(request.id(), "id"),
                    scope,
                    aggregate(request.agregadoTipo()),
                    FinanceValidation.uuid(request.statusOrigemId(), "statusOrigemId"),
                    FinanceValidation.uuid(request.statusDestinoId(), "statusDestinoId"),
                    request.exigeAprovacao(),
                    audit.actorId()
            );
        } catch (DataIntegrityViolationException exception) {
            throw FinanceValidation.badRequest(
                    "A transição deve usar status ativos do mesmo tipo e da mesma obra."
            );
        }
    }

    @Transactional
    public ApprovalRuleResponse saveApprovalRule(
            String obraId,
            ApprovalRuleRequest request,
            FinanceAuditContext audit
    ) {
        requireActor(audit);
        String scope = FinanceValidation.uuid(obraId, "obraId");
        if (request == null || request.vigenteDe() == null
                || request.etapas() == null || request.etapas().isEmpty()) {
            throw FinanceValidation.badRequest(
                    "Regra de aprovação exige vigência e ao menos uma etapa."
            );
        }
        if (request.vigenteAte() != null
                && !request.vigenteAte().isAfter(request.vigenteDe())) {
            throw FinanceValidation.badRequest("Vigência final inválida.");
        }
        String id = request.id() == null || request.id().isBlank()
                ? UUID.randomUUID().toString()
                : FinanceValidation.uuid(request.id(), "id");
        try {
            jdbc.update(
                    """
                    INSERT INTO finance_regra_aprovacao (
                        id, obra_id, centro_custo_id, categoria_id, nome,
                        prioridade, moeda, valor_minimo, valor_maximo,
                        vigente_de, vigente_ate, criado_por
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    ON DUPLICATE KEY UPDATE centro_custo_id = VALUES(centro_custo_id),
                        categoria_id = VALUES(categoria_id), nome = VALUES(nome),
                        prioridade = VALUES(prioridade), moeda = VALUES(moeda),
                        valor_minimo = VALUES(valor_minimo),
                        valor_maximo = VALUES(valor_maximo),
                        vigente_de = VALUES(vigente_de),
                        vigente_ate = VALUES(vigente_ate), ativo = TRUE,
                        arquivado_em = NULL, atualizado_por = VALUES(criado_por),
                        versao_linha = versao_linha + 1
                    """,
                    id,
                    scope,
                    FinanceValidation.optionalUuid(request.centroCustoId(), "centroCustoId"),
                    FinanceValidation.optionalUuid(request.categoriaId(), "categoriaId"),
                    FinanceValidation.requiredText(request.nome(), "nome", 255),
                    FinanceValidation.priority(request.prioridade(), true),
                    FinanceValidation.currency(request.moeda()),
                    FinanceValidation.money(request.valorMinimo(), "valorMinimo"),
                    FinanceValidation.optionalMoney(request.valorMaximo(), "valorMaximo"),
                    request.vigenteDe(),
                    request.vigenteAte(),
                    audit.actorId()
            );
            jdbc.update(
                    "DELETE FROM finance_regra_aprovacao_etapa WHERE regra_id = ?",
                    id
            );
            int expectedOrder = 1;
            for (ApprovalStepRequest step : request.etapas()) {
                int order = step.ordem() == null ? expectedOrder : step.ordem();
                if (order != expectedOrder) {
                    throw FinanceValidation.badRequest(
                            "As etapas de aprovação devem ter ordem contínua iniciando em 1."
                    );
                }
                String type = FinanceValidation.requiredText(
                        step.aprovadorTipo(), "aprovadorTipo", 30
                ).toUpperCase(Locale.ROOT);
                String collaboratorId = "COLABORADOR".equals(type)
                        ? FinanceValidation.uuid(
                                step.aprovadorColaboradorId(),
                                "aprovadorColaboradorId"
                        ) : null;
                String requiredPermission = "PERMISSAO".equals(type)
                        ? "FINANCEIRO_APROVAR" : null;
                if (!"COLABORADOR".equals(type) && !"PERMISSAO".equals(type)) {
                    throw FinanceValidation.badRequest("Tipo de aprovador inválido.");
                }
                int required = step.aprovacoesNecessarias() == null
                        ? 1 : step.aprovacoesNecessarias();
                if (required < 1) {
                    throw FinanceValidation.badRequest(
                            "Quantidade de aprovações inválida."
                    );
                }
                jdbc.update(
                        """
                        INSERT INTO finance_regra_aprovacao_etapa (
                            id, regra_id, ordem, aprovador_tipo,
                            aprovador_colaborador_id, permissao_requerida,
                            aprovacoes_necessarias, criado_por
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                        """,
                        step.id() == null || step.id().isBlank()
                                ? UUID.randomUUID().toString()
                                : FinanceValidation.uuid(step.id(), "etapa.id"),
                        id, order, type, collaboratorId,
                        requiredPermission, required, audit.actorId()
                );
                expectedOrder++;
            }
        } catch (DataIntegrityViolationException exception) {
            throw FinanceValidation.badRequest(
                    "A regra referencia centro de custo, categoria ou aprovador inválido para a obra."
            );
        }
        ApprovalRuleResponse response = approvalRule(id);
        ontology.success(
                "REGRA_APROVACAO", id, response.nome(),
                "REGRA_APROVACAO_CONFIGURADA", scope, audit, Map.of(),
                Map.of(
                        "valorMinimo", response.valorMinimo(),
                        "valorMaximo", response.valorMaximo() == null
                                ? "" : response.valorMaximo(),
                        "etapas", response.etapas().size()
                ),
                Map.of()
        );
        return response;
    }

    public List<ApprovalRuleResponse> listApprovalRules(String obraId) {
        String scope = FinanceValidation.uuid(obraId, "obraId");
        List<String> ids = jdbc.queryForList(
                """
                SELECT id FROM finance_regra_aprovacao
                WHERE obra_id = ? AND ativo = TRUE
                ORDER BY vigente_de DESC, nome, id
                """,
                String.class,
                scope
        );
        return ids.stream().map(this::approvalRule).toList();
    }

    private SupplierResponse supplier(String id) {
        return requireOne(
                jdbc.query("SELECT * FROM finance_fornecedor WHERE id = ?", this::mapSupplier, id),
                "Fornecedor não encontrado."
        );
    }

    private SupplierResponse mapSupplier(ResultSet rs, int row) throws SQLException {
        return new SupplierResponse(
                rs.getString("id"), rs.getString("razao_social"),
                rs.getString("nome_fantasia"), rs.getString("cnpj_normalizado"),
                rs.getString("email_financeiro"), rs.getString("telefone"),
                rs.getString("status"),
                rs.getTimestamp("criado_em").toLocalDateTime(),
                rs.getTimestamp("atualizado_em").toLocalDateTime(),
                rs.getLong("versao_linha")
        );
    }

    private CostCenterResponse costCenter(String id) {
        return requireOne(
                jdbc.query("SELECT * FROM finance_centro_custo WHERE id = ?", this::mapCostCenter, id),
                "Centro de custo não encontrado."
        );
    }

    private CostCenterResponse mapCostCenter(ResultSet rs, int row) throws SQLException {
        return new CostCenterResponse(
                rs.getString("id"), rs.getString("obra_id"),
                rs.getString("codigo"), rs.getString("nome"),
                rs.getString("descricao"), rs.getString("responsavel_id"),
                rs.getString("status"), rs.getLong("versao_linha")
        );
    }

    private CategoryResponse category(String id) {
        return requireOne(
                jdbc.query("SELECT * FROM finance_categoria WHERE id = ?", this::mapCategory, id),
                "Categoria não encontrada."
        );
    }

    private CategoryResponse mapCategory(ResultSet rs, int row) throws SQLException {
        return new CategoryResponse(
                rs.getString("id"), rs.getString("obra_id"),
                rs.getString("codigo"), rs.getString("nome"),
                rs.getString("descricao"), rs.getString("status"),
                rs.getLong("versao_linha")
        );
    }

    private StatusDefinitionResponse status(String id) {
        return requireOne(
                jdbc.query("SELECT * FROM finance_status_definicao WHERE id = ?", this::mapStatus, id),
                "Status financeiro não encontrado."
        );
    }

    private StatusDefinitionResponse mapStatus(ResultSet rs, int row)
            throws SQLException {
        return new StatusDefinitionResponse(
                rs.getString("id"), rs.getString("obra_id"),
                rs.getString("agregado_tipo"), rs.getString("codigo"),
                rs.getString("nome"), rs.getString("descricao"),
                rs.getInt("ordem"), rs.getBoolean("terminal"),
                rs.getBoolean("ativo"), rs.getLong("versao_linha")
        );
    }

    private ApprovalRuleResponse approvalRule(String id) {
        List<ApprovalRuleResponse> rules = jdbc.query(
                "SELECT * FROM finance_regra_aprovacao WHERE id = ?",
                (rs, row) -> new ApprovalRuleResponse(
                        rs.getString("id"), rs.getString("obra_id"),
                        rs.getString("centro_custo_id"),
                        rs.getString("categoria_id"), rs.getString("nome"),
                        rs.getString("prioridade"), rs.getString("moeda"),
                        rs.getBigDecimal("valor_minimo"),
                        rs.getBigDecimal("valor_maximo"),
                        rs.getTimestamp("vigente_de").toLocalDateTime(),
                        rs.getTimestamp("vigente_ate") == null ? null
                                : rs.getTimestamp("vigente_ate").toLocalDateTime(),
                        rs.getBoolean("ativo"),
                        approvalSteps(rs.getString("id")),
                        rs.getLong("versao_linha")
                ),
                id
        );
        return requireOne(rules, "Regra de aprovação não encontrada.");
    }

    private List<ApprovalStepRequest> approvalSteps(String ruleId) {
        return jdbc.query(
                """
                SELECT * FROM finance_regra_aprovacao_etapa
                WHERE regra_id = ? ORDER BY ordem
                """,
                (rs, row) -> new ApprovalStepRequest(
                        rs.getString("id"), rs.getInt("ordem"),
                        rs.getString("aprovador_tipo"),
                        rs.getString("aprovador_colaborador_id"),
                        rs.getString("permissao_requerida"),
                        rs.getInt("aprovacoes_necessarias")
                ),
                ruleId
        );
    }

    private <T> T requireOne(List<T> values, String message) {
        if (values.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, message);
        }
        return values.getFirst();
    }

    private String resolveScopedNaturalKeyId(
            String table,
            String obraId,
            String code,
            String rawRequestedId,
            String conflictMessage
    ) {
        String requestedId = rawRequestedId == null || rawRequestedId.isBlank()
                ? null : FinanceValidation.uuid(rawRequestedId, "id");
        String existingId = jdbc.query(
                "SELECT id FROM " + table
                        + " WHERE obra_id = ? AND codigo = ? LIMIT 1",
                rs -> rs.next() ? rs.getString(1) : null,
                obraId,
                code
        );
        if (existingId != null && requestedId != null
                && !existingId.equals(requestedId)) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    conflictMessage
            );
        }
        if (existingId != null) {
            return existingId;
        }
        return requestedId == null ? UUID.randomUUID().toString() : requestedId;
    }

    private void requireActor(FinanceAuditContext audit) {
        String actor = currentUser.requireUserId();
        if (audit == null || !actor.equals(audit.actorId())) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN,
                    "Ator financeiro divergente da sessão."
            );
        }
    }

    private void preventScopedMutationOfSharedSupplier(
            String supplierId,
            String currentWorksiteId,
            String actorId
    ) {
        if (currentUser.isAlfa(actorId)) {
            return;
        }
        Integer linkedElsewhere = jdbc.queryForObject(
                """
                SELECT COUNT(*)
                FROM finance_fornecedor_obra
                WHERE fornecedor_id = ?
                  AND obra_id <> ?
                  AND status = 'ATIVO'
                  AND arquivado_em IS NULL
                """,
                Integer.class,
                supplierId,
                currentWorksiteId
        );
        if (linkedElsewhere != null && linkedElsewhere > 0) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Fornecedor compartilhado entre obras só pode ser alterado por um administrador Alfa."
            );
        }
    }

    private String aggregate(String value) {
        String normalized = FinanceValidation.requiredText(
                value, "agregadoTipo", 30
        ).toUpperCase(Locale.ROOT);
        if (!normalized.equals("SOLICITACAO") && !normalized.equals("COMPRA")) {
            throw FinanceValidation.badRequest("Tipo de agregado inválido.");
        }
        return normalized;
    }

}
