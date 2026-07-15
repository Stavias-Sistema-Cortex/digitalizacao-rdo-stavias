package com.projeto.cortex.financeiro.unit;

import com.projeto.cortex.financeiro.access.FinancialAccessService;
import com.projeto.cortex.financeiro.access.FinancialPermission;
import com.projeto.cortex.financeiro.core.FinanceAuditContext;
import com.projeto.cortex.financeiro.core.FinanceOntologyProjector;
import com.projeto.cortex.financeiro.unit.FinancialUnitDtos.CreateFinancialUnitRequest;
import com.projeto.cortex.financeiro.unit.FinancialUnitDtos.FinancialUnitFilter;
import com.projeto.cortex.financeiro.unit.FinancialUnitDtos.FinancialUnitResponse;
import com.projeto.cortex.financeiro.unit.FinancialUnitRepository.AssetRecord;
import com.projeto.cortex.financeiro.unit.FinancialUnitRepository.WorksiteRecord;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class FinancialUnitService {

    private final FinancialUnitRepository repository;
    private final FinancialAccessService access;
    private final FinanceOntologyProjector ontology;

    public FinancialUnitService(
            FinancialUnitRepository repository,
            FinancialAccessService access,
            FinanceOntologyProjector ontology
    ) {
        this.repository = repository;
        this.access = access;
        this.ontology = ontology;
    }

    public List<FinancialUnitResponse> list(
            FinancialUnitFilter filter,
            String userId
    ) {
        String actor = requireId(userId, "usuário");
        Optional<Set<String>> allowedUnitIds = access.allowedUnitIds(
                actor,
                FinancialPermission.FINANCEIRO_VISUALIZAR
        );
        return repository.list(normalizeFilter(filter))
                .stream()
                .filter(unit -> allowedUnitIds
                        .map(ids -> ids.contains(unit.id()))
                        .orElse(true))
                .map(FinancialUnitResponse::from)
                .toList();
    }

    @Transactional
    public FinancialUnitResponse createAdministrative(
            CreateFinancialUnitRequest request,
            String actorId
    ) {
        return createAdministrative(
                request,
                FinanceAuditContext.online(actorId, null)
        );
    }

    @Transactional
    public FinancialUnitResponse createAdministrative(
            CreateFinancialUnitRequest request,
            FinanceAuditContext rawAudit
    ) {
        if (request == null) {
            throw badRequest("Dados da unidade administrativa são obrigatórios.");
        }
        FinanceAuditContext audit = requireAudit(rawAudit);
        String actor = audit.actorId();
        String code = requireCode(request.codigo());
        String name = requireName(request.nome());
        if (repository.findByCode(code).isPresent()) {
            throw conflict("Já existe uma unidade financeira com este código.");
        }
        String id = UUID.randomUUID().toString();
        try {
            repository.insertAdministrative(id, code, name, actor);
        } catch (DuplicateKeyException duplicate) {
            throw conflict("Já existe uma unidade financeira com este código.");
        }
        FinancialUnitRecord created = current(id);
        projectCreated(created, audit, Map.of());
        return FinancialUnitResponse.from(created);
    }

    @Transactional
    public FinancialUnitResponse ensureAssetUnit(
            String assetId,
            String actorId
    ) {
        return ensureAssetUnit(
                assetId,
                FinanceAuditContext.online(actorId, null)
        );
    }

    @Transactional
    public FinancialUnitResponse ensureAssetUnit(
            String assetId,
            FinanceAuditContext rawAudit
    ) {
        String normalizedAssetId = requireId(assetId, "ativoId");
        FinanceAuditContext audit = requireAudit(rawAudit);
        String actor = audit.actorId();
        FinancialUnitRecord existing = repository.findByAsset(normalizedAssetId)
                .orElse(null);
        if (existing != null) {
            return FinancialUnitResponse.from(existing);
        }
        AssetRecord asset = repository.findActiveAsset(normalizedAssetId)
                .orElseThrow(() -> notFound("Ativo ativo não encontrado."));
        String id = UUID.randomUUID().toString();
        try {
            repository.insertAsset(
                    id,
                    asset.id(),
                    "ATIVO:" + asset.id(),
                    requireName(asset.name()),
                    actor
            );
        } catch (DuplicateKeyException duplicate) {
            return FinancialUnitResponse.from(repository.findByAsset(asset.id())
                    .orElseThrow(() -> duplicate));
        }
        FinancialUnitRecord created = current(id);
        projectCreated(created, audit, Map.of("ATIVO", asset.id()));
        return FinancialUnitResponse.from(created);
    }

    @Transactional
    public FinancialUnitResponse ensureWorksiteUnit(
            String worksiteId,
            String actorId
    ) {
        return ensureWorksiteUnit(
                worksiteId,
                FinanceAuditContext.online(actorId, null)
        );
    }

    @Transactional
    public FinancialUnitResponse ensureWorksiteUnit(
            String worksiteId,
            FinanceAuditContext rawAudit
    ) {
        String normalizedWorksiteId = requireId(worksiteId, "obraId");
        FinanceAuditContext audit = requireAudit(rawAudit);
        String actor = audit.actorId();
        FinancialUnitRecord existing = repository.findByWorksite(
                normalizedWorksiteId
        ).orElse(null);
        if (existing != null) {
            return FinancialUnitResponse.from(existing);
        }
        WorksiteRecord worksite = repository.findActiveWorksite(
                normalizedWorksiteId
        ).orElseThrow(() -> notFound("Obra ativa não encontrada."));
        String id = UUID.randomUUID().toString();
        try {
            repository.insertWorksite(
                    id,
                    worksite.id(),
                    "OBRA:" + worksite.id(),
                    requireName(worksite.name()),
                    actor
            );
        } catch (DuplicateKeyException duplicate) {
            return FinancialUnitResponse.from(repository.findByWorksite(
                    worksite.id()
            ).orElseThrow(() -> duplicate));
        }
        FinancialUnitRecord created = current(id);
        projectCreated(created, audit, Map.of("OBRA", worksite.id()));
        return FinancialUnitResponse.from(created);
    }

    private void projectCreated(
            FinancialUnitRecord created,
            FinanceAuditContext audit,
            Map<String, String> related
    ) {
        Map<String, Object> newState = Map.of(
                "id", created.id(),
                "tipo", created.type().name(),
                "codigo", created.code(),
                "nome", created.name(),
                "status", created.status(),
                "atorId", audit.actorId()
        );
        ontology.success(
                "UNIDADE_FINANCEIRA",
                created.id(),
                created.name(),
                "UNIDADE_FINANCEIRA_CRIADA",
                created.worksiteId(),
                audit,
                Map.of(),
                newState,
                related
        );
    }

    private FinanceAuditContext requireAudit(FinanceAuditContext audit) {
        if (audit == null) {
            throw badRequest("Contexto de auditoria é obrigatório.");
        }
        String actor = requireId(audit.actorId(), "ator");
        String origin = audit.origin();
        if (!"ONLINE".equals(origin) && !"OFFLINE".equals(origin)) {
            throw badRequest("Origem da auditoria financeira é inválida.");
        }
        return new FinanceAuditContext(
                actor,
                audit.deviceId(),
                audit.correlationId(),
                origin
        );
    }

    private FinancialUnitRecord current(String id) {
        return repository.findById(id).orElseThrow(() ->
                new IllegalStateException(
                        "A unidade financeira criada não pôde ser relida."
                )
        );
    }

    private FinancialUnitFilter normalizeFilter(FinancialUnitFilter filter) {
        if (filter == null) {
            return new FinancialUnitFilter(null, "ATIVA", null);
        }
        String status = hasText(filter.status())
                ? filter.status().trim().toUpperCase(Locale.ROOT)
                : "ATIVA";
        if (!List.of("ATIVA", "ARQUIVADA").contains(status)) {
            throw badRequest("status deve ser ATIVA ou ARQUIVADA.");
        }
        String search = hasText(filter.busca()) ? filter.busca().trim() : null;
        if (search != null && search.length() > 255) {
            throw badRequest("busca deve ter no máximo 255 caracteres.");
        }
        return new FinancialUnitFilter(filter.tipo(), status, search);
    }

    private String requireCode(String value) {
        if (!hasText(value)) {
            throw badRequest("codigo é obrigatório.");
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        if (normalized.length() > 120
                || !normalized.matches("[A-Z0-9][A-Z0-9._:-]+")) {
            throw badRequest("codigo da unidade financeira é inválido.");
        }
        return normalized;
    }

    private String requireName(String value) {
        if (!hasText(value)) {
            throw badRequest("nome é obrigatório.");
        }
        String normalized = value.trim();
        if (normalized.length() > 255) {
            throw badRequest("nome deve ter no máximo 255 caracteres.");
        }
        return normalized;
    }

    private String requireId(String value, String field) {
        if (!hasText(value) || value.trim().length() > 64) {
            throw badRequest(field + " é obrigatório e deve ser válido.");
        }
        return value.trim();
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    private ResponseStatusException notFound(String message) {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, message);
    }

    private ResponseStatusException conflict(String message) {
        return new ResponseStatusException(HttpStatus.CONFLICT, message);
    }
}
