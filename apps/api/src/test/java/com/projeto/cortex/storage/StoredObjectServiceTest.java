package com.projeto.cortex.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.projeto.cortex.auth.CurrentUserService;
import com.projeto.cortex.obras.ObraOperabilityGuard;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.web.server.ResponseStatusException;

class StoredObjectServiceTest {

    private final StoredObjectRepository repository =
            mock(StoredObjectRepository.class);
    private final ObjectStorage storage = mock(ObjectStorage.class);
    private final CurrentUserService currentUser =
            mock(CurrentUserService.class);
    private final StoredObjectDomainAccessGuard domainGuard =
            mock(StoredObjectDomainAccessGuard.class);
    private final ObraOperabilityGuard operabilityGuard =
            mock(ObraOperabilityGuard.class);
    private final PlatformTransactionManager transactionManager =
            mock(PlatformTransactionManager.class);
    private StoredObjectService service;

    @BeforeEach
    void setUp() {
        when(currentUser.requireUserId()).thenReturn("user-1");
        when(storage.backend()).thenReturn("LOCAL");
        service = new StoredObjectService(
                repository,
                storage,
                new StoredObjectContentInspector(
                        1_024,
                        Set.of("text/plain")
                ),
                currentUser,
                List.of(domainGuard),
                operabilityGuard,
                transactionManager
        );
    }

    @Test
    void repeatedUploadReturnsExistingMetadataWithoutWritingAgain() {
        byte[] body = "mesmo arquivo".getBytes(StandardCharsets.UTF_8);
        StoredObjectRecord existing = availableRecord(
                "object-existing",
                "user-1",
                "obra-1",
                body.length
        );
        when(repository.findAvailableByDedupeKey(any()))
                .thenReturn(Optional.of(existing));

        StoredObjectUploadResult result = service.upload(
                "obra-1",
                "evidencia.txt",
                "text/plain",
                body.length,
                () -> new ByteArrayInputStream(body)
        );

        assertThat(result.created()).isFalse();
        assertThat(result.object().id()).isEqualTo("object-existing");
        verify(currentUser).requireWorksiteAccess("obra-1");
        verify(operabilityGuard, never()).requireWritable(any());
        verify(transactionManager, never()).getTransaction(any());
        verify(repository, never()).reserve(any());
        verify(storage, never()).put(any(), any(), any(Long.class), any());
    }

    @Test
    void verifiesTheSecondPassBeforePublishingMetadata() {
        byte[] first = "conteudo-a".getBytes(StandardCharsets.UTF_8);
        byte[] second = "conteudo-b".getBytes(StandardCharsets.UTF_8);
        AtomicInteger openings = new AtomicInteger();
        when(repository.findAvailableByDedupeKey(any()))
                .thenReturn(Optional.empty());
        when(repository.reserve(any())).thenReturn(true);
        org.mockito.Mockito.doAnswer(invocation -> {
            invocation.<java.io.InputStream>getArgument(1).readAllBytes();
            return null;
        }).when(storage).put(any(), any(), eq((long) first.length), any());

        assertThatThrownBy(() -> service.upload(
                null,
                "evidencia.txt",
                "text/plain",
                first.length,
                () -> new ByteArrayInputStream(
                        openings.getAndIncrement() == 0 ? first : second
                )
        )).isInstanceOfSatisfying(
                ResponseStatusException.class,
                exception -> assertThat(exception.getStatusCode())
                        .isEqualTo(HttpStatus.CONFLICT)
        );

        verify(repository).markQuarantined(any());
        verify(storage).delete(any());
        verify(repository, never()).markAvailable(any());
    }

    @Test
    void downloadRequiresCurrentWorksiteAccessForAnotherOwner() {
        StoredObjectRecord record = availableRecord(
                "object-1",
                "owner-2",
                "obra-1",
                3
        );
        when(repository.findAvailableById("object-1"))
                .thenReturn(Optional.of(record));
        when(storage.get(record.storageKey())).thenReturn(
                new ObjectStorageContent(
                        new ByteArrayInputStream(new byte[] {1, 2, 3}),
                        3,
                        "text/plain"
                )
        );

        try (StoredObjectDownload download = service.download("object-1")) {
            assertThat(download.object().id()).isEqualTo("object-1");
        }

        verify(currentUser).requireWorksiteAccess("obra-1");
    }

    @Test
    void privateObjectOwnedBySomeoneElseRequiresSelfOrAdmin() {
        StoredObjectRecord record = availableRecord(
                "object-1",
                "owner-2",
                null,
                3
        );
        when(repository.findAvailableById("object-1"))
                .thenReturn(Optional.of(record));
        when(storage.get(record.storageKey())).thenReturn(
                new ObjectStorageContent(
                        new ByteArrayInputStream(new byte[] {1, 2, 3}),
                        3,
                        "text/plain"
                )
        );

        try (StoredObjectDownload ignored = service.download("object-1")) {
            // Authorization is asserted through the central policy call.
        }

        verify(currentUser).requireSelfOrAdmin("owner-2");
    }

    @Test
    void domainProtectedObjectCannotFallBackToOwnerOrWorksiteAccess() {
        StoredObjectRecord record = availableRecord(
                "object-finance",
                "user-1",
                "obra-1",
                3
        );
        when(repository.findAvailableById("object-finance"))
                .thenReturn(Optional.of(record));
        when(domainGuard.protects(record)).thenReturn(true);
        org.mockito.Mockito.doThrow(new ResponseStatusException(
                HttpStatus.FORBIDDEN
        )).when(domainGuard).authorize(record);

        assertThatThrownBy(() -> service.download("object-finance"))
                .isInstanceOf(ResponseStatusException.class);

        verify(domainGuard).authorize(record);
        verify(storage, never()).get(any());
        verify(currentUser, never()).requireWorksiteAccess(any());
    }

    @Test
    void archivedWorksiteRejectsUploadBeforeReservationOrObjectWrite() {
        byte[] body = "evidencia".getBytes(StandardCharsets.UTF_8);
        when(repository.findAvailableByDedupeKey(any()))
                .thenReturn(Optional.empty());
        doThrow(new ResponseStatusException(
                HttpStatus.NOT_FOUND,
                "Obra não encontrada ou arquivada."
        )).when(operabilityGuard).requireWritable("obra-1");

        assertThatThrownBy(() -> service.upload(
                "obra-1",
                "evidencia.txt",
                "text/plain",
                body.length,
                () -> new ByteArrayInputStream(body)
        )).isInstanceOfSatisfying(
                ResponseStatusException.class,
                exception -> {
                    assertThat(exception.getStatusCode())
                            .isEqualTo(HttpStatus.NOT_FOUND);
                    assertThat(exception.getReason())
                            .isEqualTo("Obra não encontrada ou arquivada.");
                }
        );

        verify(repository, never()).reserve(any());
        verify(storage, never()).put(
                any(), any(), any(Long.class), any()
        );
    }

    @Test
    void reservationTransactionCommitsBeforeObjectStorageWrite() {
        byte[] body = "evidencia".getBytes(StandardCharsets.UTF_8);
        TransactionStatus transaction = mock(TransactionStatus.class);
        when(transactionManager.getTransaction(any()))
                .thenReturn(transaction);
        when(repository.findAvailableByDedupeKey(any()))
                .thenReturn(Optional.empty());
        when(repository.reserve(any())).thenReturn(true);
        org.mockito.Mockito.doAnswer(invocation -> {
            invocation.<java.io.InputStream>getArgument(1).readAllBytes();
            return null;
        }).when(storage).put(
                any(),
                any(),
                eq((long) body.length),
                eq("text/plain")
        );

        StoredObjectUploadResult result = service.upload(
                "obra-1",
                "evidencia.txt",
                "text/plain",
                body.length,
                () -> new ByteArrayInputStream(body)
        );

        assertThat(result.created()).isTrue();
        InOrder order = inOrder(
                transactionManager,
                operabilityGuard,
                repository,
                storage
        );
        order.verify(transactionManager).getTransaction(any());
        order.verify(operabilityGuard).requireWritable("obra-1");
        order.verify(repository).reserve(any());
        order.verify(transactionManager).commit(transaction);
        order.verify(storage).put(
                any(),
                any(),
                eq((long) body.length),
                eq("text/plain")
        );
    }

    private StoredObjectRecord availableRecord(
            String id,
            String owner,
            String obraId,
            long size
    ) {
        return new StoredObjectRecord(
                id,
                owner,
                obraId,
                "dedupe",
                "a".repeat(64),
                "LOCAL",
                "objects/aa/" + id + "/hash",
                "arquivo.txt",
                "text/plain",
                "text/plain",
                size,
                "DISPONIVEL",
                LocalDateTime.of(2026, 7, 14, 12, 0)
        );
    }
}
