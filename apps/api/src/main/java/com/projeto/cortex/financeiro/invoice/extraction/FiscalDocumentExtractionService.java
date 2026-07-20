package com.projeto.cortex.financeiro.invoice.extraction;

import static com.projeto.cortex.financeiro.invoice.extraction.FiscalExtractionDtos.*;

import com.projeto.cortex.financeiro.core.FinanceAuditContext;
import com.projeto.cortex.financeiro.core.FinanceOntologyProjector;
import com.projeto.cortex.financeiro.core.FinanceOntologyRelation;
import com.projeto.cortex.financeiro.core.FinanceValidation;
import com.projeto.cortex.storage.StoredObjectDownload;
import com.projeto.cortex.storage.StoredObjectRecord;
import com.projeto.cortex.storage.StoredObjectService;
import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class FiscalDocumentExtractionService {

    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");

    private final StoredObjectService storage;
    private final FiscalExtractionRepository repository;
    private final InvoiceExtractionCoordinator coordinator;
    private final FinanceOntologyProjector ontology;

    public FiscalDocumentExtractionService(
            StoredObjectService storage,
            FiscalExtractionRepository repository,
            InvoiceExtractionCoordinator coordinator,
            FinanceOntologyProjector ontology
    ) {
        this.storage = storage;
        this.repository = repository;
        this.coordinator = coordinator;
        this.ontology = ontology;
    }

    @Transactional
    public FiscalExtractionJobResponse extract(
            StoredObjectRecord object,
            String clientMutationId,
            String clientSha256,
            FinanceAuditContext audit
    ) {
        requireObject(object);
        FinanceAuditContext context = requireAudit(audit);
        String mutation = FinanceValidation.mutationId(clientMutationId);
        String normalizedClientHash = normalizeHash(clientSha256);

        var previous = repository.findByMutation(
                context.actorId(), mutation
        );
        if (previous.isPresent()) {
            requireSameReplay(previous.get(), object, normalizedClientHash);
            return repository.response(previous.get().id(), true);
        }

        String format = format(object.detectedMediaType());
        FiscalExtractionResult result;
        if (normalizedClientHash != null
                && !normalizedClientHash.equals(object.sha256())) {
            result = new FiscalExtractionResult(
                    format,
                    ExtractionStatus.FALHA,
                    "INTEGRIDADE",
                    "1",
                    List.of(),
                    List.of("HASH_CLIENTE_DIVERGENTE"),
                    "NAO_VERIFICADA"
            );
        } else {
            result = coordinator.extract(source(object));
        }

        String id = UUID.randomUUID().toString();
        FiscalExtractionRepository.JobWrite write =
                new FiscalExtractionRepository.JobWrite(
                        id,
                        object.id(),
                        object.obraId(),
                        mutation,
                        format,
                        normalizedClientHash,
                        object.sha256(),
                        context.actorId(),
                        context.deviceId(),
                        context.correlationId()
                );
        try {
            repository.insert(write, result);
        } catch (DuplicateKeyException exception) {
            FiscalExtractionRepository.JobRecord concurrent = repository
                    .findByMutation(context.actorId(), mutation)
                    .orElseThrow(() -> exception);
            requireSameReplay(concurrent, object, normalizedClientHash);
            return repository.response(concurrent.id(), true);
        }

        Map<String, Object> state = Map.of(
                "id", id,
                "storedObjectId", object.id(),
                "obraId", object.obraId(),
                "status", result.status().name(),
                "sha256Servidor", object.sha256(),
                "autorizacaoFiscal", result.autorizacaoFiscal()
        );
        List<FinanceOntologyRelation> relations = List.of(
                new FinanceOntologyRelation(
                    "STORED_OBJECT",
                    object.id(),
                    "EXTRAIDO_DE",
                    "Hash e conteúdo inspecionado pelo armazenamento."
                )
        );
        if (result.status() == ExtractionStatus.FALHA) {
            ontology.failureWithRelations(
                    "DOCUMENTO_FISCAL", id, object.originalName(),
                    event(result.status()), object.obraId(), context,
                    Map.of(), state, relations,
                    result.warnings().isEmpty()
                            ? "EXTRACAO_FISCAL_FALHOU"
                            : result.warnings().getFirst()
            );
        } else {
            ontology.successWithRelations(
                    "DOCUMENTO_FISCAL", id, object.originalName(),
                    event(result.status()), object.obraId(), context,
                    Map.of(), state, relations
            );
        }
        return repository.response(id, false);
    }

    private FiscalDocumentSource source(StoredObjectRecord expected) {
        try (StoredObjectDownload download = storage.downloadAuthorized(
                expected.id(),
                actual -> {
                    if (!expected.id().equals(actual.id())
                            || !Objects.equals(expected.obraId(), actual.obraId())
                            || !expected.sha256().equals(actual.sha256())) {
                        throw new ResponseStatusException(
                                HttpStatus.CONFLICT,
                                "O objeto fiscal mudou após a inspeção."
                        );
                    }
                }
        )) {
            byte[] content = download.content().inputStream().readAllBytes();
            if (content.length != expected.size()) {
                throw new ResponseStatusException(
                        HttpStatus.CONFLICT,
                        "O tamanho do documento fiscal não confere."
                );
            }
            return new FiscalDocumentSource(
                    expected.detectedMediaType(),
                    expected.originalName(),
                    content
            );
        } catch (IOException exception) {
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "Não foi possível ler o documento fiscal armazenado."
            );
        }
    }

    private void requireSameReplay(
            FiscalExtractionRepository.JobRecord previous,
            StoredObjectRecord object,
            String clientHash
    ) {
        if (!previous.storedObjectId().equals(object.id())
                || !previous.obraId().equals(object.obraId())
                || !previous.serverSha256().equals(object.sha256())
                || (clientHash != null
                && !clientHash.equals(previous.clientSha256()))) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "clientMutationId já foi usado para outro documento fiscal."
            );
        }
    }

    private FinanceAuditContext requireAudit(FinanceAuditContext audit) {
        if (audit == null || audit.actorId() == null
                || audit.actorId().isBlank()) {
            throw new ResponseStatusException(
                    HttpStatus.UNAUTHORIZED,
                    "Ator autenticado é obrigatório."
            );
        }
        return new FinanceAuditContext(
                audit.actorId().strip(),
                optional(audit.deviceId(), "dispositivoId"),
                optional(audit.correlationId(), "correlacaoId"),
                audit.origin() == null || audit.origin().isBlank()
                        ? "ONLINE" : audit.origin().strip()
        );
    }

    private void requireObject(StoredObjectRecord object) {
        if (object == null || object.obraId() == null
                || !"DISPONIVEL".equals(object.status())) {
            throw FinanceValidation.badRequest(
                    "Documento fiscal armazenado e disponível é obrigatório."
            );
        }
        if (!SHA256.matcher(object.sha256()).matches()) {
            throw FinanceValidation.badRequest(
                    "Hash do documento armazenado é inválido."
            );
        }
    }

    private String normalizeHash(String value) {
        if (value == null || value.isBlank()) return null;
        String normalized = value.strip().toLowerCase(Locale.ROOT);
        if (!SHA256.matcher(normalized).matches()) {
            throw FinanceValidation.badRequest(
                    "sha256Cliente deve conter 64 caracteres hexadecimais."
            );
        }
        return normalized;
    }

    private String optional(String value, String field) {
        if (value == null || value.isBlank()) return null;
        String normalized = value.strip();
        if (normalized.length() > 120) {
            throw FinanceValidation.badRequest(
                    field + " deve ter até 120 caracteres."
            );
        }
        return normalized;
    }

    private String format(String mediaType) {
        String normalized = mediaType == null
                ? "" : mediaType.toLowerCase(Locale.ROOT);
        if ("application/xml".equals(normalized)
                || "text/xml".equals(normalized)) return "XML";
        if ("application/pdf".equals(normalized)) return "PDF";
        if (List.of(
                "image/jpeg", "image/png", "image/webp", "image/tiff"
        ).contains(normalized)) return "IMAGEM";
        throw FinanceValidation.badRequest(
                "Formato não suportado para extração fiscal."
        );
    }

    private String event(ExtractionStatus status) {
        return switch (status) {
            case EXTRAIDO -> "DOCUMENTO_FISCAL_EXTRAIDO";
            case REVISAO_NECESSARIA ->
                    "DOCUMENTO_FISCAL_CANDIDATOS_GERADOS";
            case FALHA -> "DOCUMENTO_FISCAL_EXTRACAO_FALHOU";
        };
    }
}
