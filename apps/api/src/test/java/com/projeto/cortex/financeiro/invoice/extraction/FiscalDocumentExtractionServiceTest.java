package com.projeto.cortex.financeiro.invoice.extraction;

import static com.projeto.cortex.financeiro.invoice.extraction.FiscalExtractionDtos.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.projeto.cortex.financeiro.core.FinanceAuditContext;
import com.projeto.cortex.financeiro.core.FinanceOntologyProjector;
import com.projeto.cortex.storage.ObjectStorageContent;
import com.projeto.cortex.storage.StoredObjectDownload;
import com.projeto.cortex.storage.StoredObjectRecord;
import com.projeto.cortex.storage.StoredObjectService;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class FiscalDocumentExtractionServiceTest {

    private StoredObjectService storage;
    private FiscalExtractionRepository repository;
    private InvoiceExtractionCoordinator coordinator;
    private FinanceOntologyProjector ontology;
    private FiscalDocumentExtractionService service;

    @BeforeEach
    void setup() {
        storage = mock(StoredObjectService.class);
        repository = mock(FiscalExtractionRepository.class);
        coordinator = mock(InvoiceExtractionCoordinator.class);
        ontology = mock(FinanceOntologyProjector.class);
        service = new FiscalDocumentExtractionService(
                storage, repository, coordinator, ontology
        );
    }

    @Test
    void persistsAuthenticatedActorHashesCandidatesAndOntology() {
        StoredObjectRecord object = object("a".repeat(64));
        FinanceAuditContext audit = new FinanceAuditContext(
                "actor-1", "device-1", "correlation-1", "ONLINE"
        );
        byte[] xml = "<NFe/>".getBytes(StandardCharsets.UTF_8);
        FiscalExtractionResult extracted = new FiscalExtractionResult(
                "XML", ExtractionStatus.EXTRAIDO, "NFE_XML", "1",
                List.of(), List.of(), "NAO_VERIFICADA"
        );
        FiscalExtractionJobResponse response = response(false, object);

        when(repository.findByMutation("actor-1", "mutation-1"))
                .thenReturn(Optional.empty());
        when(storage.downloadAuthorized(eq("object-1"), any()))
                .thenReturn(new StoredObjectDownload(
                        object,
                        new ObjectStorageContent(
                                new ByteArrayInputStream(xml),
                                xml.length,
                                "application/xml"
                        )
                ));
        when(coordinator.extract(any())).thenReturn(extracted);
        when(repository.response(any(), eq(false))).thenReturn(response);

        FiscalExtractionJobResponse saved = service.extract(
                object, "mutation-1", object.sha256(), audit
        );

        ArgumentCaptor<FiscalExtractionRepository.JobWrite> write =
                ArgumentCaptor.forClass(
                        FiscalExtractionRepository.JobWrite.class
                );
        verify(repository).insert(write.capture(), eq(extracted));
        assertThat(write.getValue().createdBy()).isEqualTo("actor-1");
        assertThat(write.getValue().deviceId()).isEqualTo("device-1");
        assertThat(write.getValue().clientSha256()).isEqualTo(object.sha256());
        assertThat(write.getValue().serverSha256()).isEqualTo(object.sha256());
        assertThat(saved).isSameAs(response);
        verify(ontology).successWithRelations(
                eq("DOCUMENTO_FISCAL"),
                any(),
                eq("nota.xml"),
                eq("DOCUMENTO_FISCAL_EXTRAIDO"),
                eq("obra-1"),
                eq(audit),
                eq(java.util.Map.of()),
                any(),
                any()
        );
    }

    @Test
    void replaysCanonicalJobOnlyForTheSameObjectAndHash() {
        StoredObjectRecord object = object("a".repeat(64));
        FiscalExtractionRepository.JobRecord previous =
                new FiscalExtractionRepository.JobRecord(
                        "job-1", "object-1", "obra-1", "mutation-1",
                        "XML", "EXTRAIDO", "NFE_XML", "1",
                        object.sha256(), object.sha256(), "NAO_VERIFICADA",
                        "actor-1", "device-1", "correlation-1",
                        LocalDateTime.now(), LocalDateTime.now()
                );
        FiscalExtractionJobResponse replay = response(true, object);
        when(repository.findByMutation("actor-1", "mutation-1"))
                .thenReturn(Optional.of(previous));
        when(repository.response("job-1", true)).thenReturn(replay);

        FiscalExtractionJobResponse result = service.extract(
                object,
                "mutation-1",
                object.sha256(),
                new FinanceAuditContext(
                        "actor-1", "device-1", null, "ONLINE"
                )
        );

        assertThat(result.replayed()).isTrue();
        verify(storage, never()).downloadAuthorized(any(), any());
        verify(coordinator, never()).extract(any());
        verify(repository, never()).insert(any(), any());
    }

    @Test
    void recordsClientServerHashMismatchWithoutRunningExtractor() {
        StoredObjectRecord object = object("a".repeat(64));
        when(repository.findByMutation("actor-1", "mutation-1"))
                .thenReturn(Optional.empty());
        when(repository.response(any(), eq(false)))
                .thenReturn(response(false, object));

        service.extract(
                object,
                "mutation-1",
                "b".repeat(64),
                new FinanceAuditContext("actor-1", null, null, "ONLINE")
        );

        ArgumentCaptor<FiscalExtractionResult> result =
                ArgumentCaptor.forClass(FiscalExtractionResult.class);
        verify(repository).insert(any(), result.capture());
        assertThat(result.getValue().status()).isEqualTo(ExtractionStatus.FALHA);
        assertThat(result.getValue().warnings())
                .contains("HASH_CLIENTE_DIVERGENTE");
        verify(coordinator, never()).extract(any());
        verify(ontology).failureWithRelations(
                eq("DOCUMENTO_FISCAL"), any(), eq("nota.xml"),
                eq("DOCUMENTO_FISCAL_EXTRACAO_FALHOU"), eq("obra-1"),
                any(), eq(java.util.Map.of()), any(), any(),
                eq("HASH_CLIENTE_DIVERGENTE")
        );
    }

    private StoredObjectRecord object(String hash) {
        return new StoredObjectRecord(
                "object-1", "actor-1", "obra-1", "dedupe", hash,
                "LOCAL", "objects/1", "nota.xml", "application/xml",
                "application/xml", 6, "DISPONIVEL", LocalDateTime.now()
        );
    }

    private FiscalExtractionJobResponse response(
            boolean replayed,
            StoredObjectRecord object
    ) {
        return new FiscalExtractionJobResponse(
                "job-1", object.id(), object.obraId(), object.originalName(),
                object.detectedMediaType(), object.size(), "mutation-1",
                "XML", ExtractionStatus.EXTRAIDO, "NFE_XML", "1",
                object.sha256(), object.sha256(), List.of(), List.of(),
                "NAO_VERIFICADA", "actor-1", "device-1", "correlation-1",
                LocalDateTime.now(), LocalDateTime.now(), replayed
        );
    }
}
