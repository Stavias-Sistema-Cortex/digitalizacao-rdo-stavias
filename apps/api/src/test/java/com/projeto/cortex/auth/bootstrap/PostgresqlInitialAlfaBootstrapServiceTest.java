package com.projeto.cortex.auth.bootstrap;

import com.projeto.cortex.auth.identity.CpfLookupDigest;
import com.projeto.cortex.auth.identity.CpfLookupDigestService;
import com.projeto.cortex.integracoes.AcademyBootstrapUser;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionOperations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PostgresqlInitialAlfaBootstrapServiceTest {

    private static final String SYNTHETIC_CPF = "11144477735";
    private static final CpfLookupDigest PROTECTED_DIGEST =
            new CpfLookupDigest("test-kid", "a".repeat(64));

    @Test
    void validatesTheSourceBeforeOpeningThePostgresqlTransaction() {
        BootstrapAdminProperties properties = properties();
        BootstrapCpfSecretReader reader = mock(BootstrapCpfSecretReader.class);
        CpfLookupDigestService digests = mock(CpfLookupDigestService.class);
        AcademyBootstrapLookup academy = mock(AcademyBootstrapLookup.class);
        PostgresqlInitialAlfaBootstrapRepository repository =
                mock(PostgresqlInitialAlfaBootstrapRepository.class);
        AtomicBoolean transactionUsed = new AtomicBoolean();
        TransactionOperations transactions = recordingTransactions(transactionUsed);
        when(reader.read(any(Path.class))).thenReturn(SYNTHETIC_CPF);
        when(digests.current(SYNTHETIC_CPF)).thenReturn(PROTECTED_DIGEST);
        when(academy.lookupSingleActive(SYNTHETIC_CPF)).thenReturn(new AcademyBootstrapUser(
                901,
                "Synthetic Owner",
                "not-an-email",
                true,
                null,
                null,
                null,
                null,
                null
        ));

        PostgresqlInitialAlfaBootstrapService service = service(
                properties, reader, digests, academy, repository, transactions
        );

        assertThatThrownBy(service::bootstrap)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Bootstrap inicial do ALFA não pôde ser concluído.")
                .hasMessageNotContaining("not-an-email")
                .hasMessageNotContaining(SYNTHETIC_CPF);
        assertThat(transactionUsed).isFalse();
        verify(repository, never()).bootstrap(any(), any(), anyString());
    }

    @Test
    void usesOnlyProtectedDigestMaterialForTheReceiptAndDelegatesOneTransaction() {
        BootstrapAdminProperties properties = properties();
        BootstrapCpfSecretReader reader = mock(BootstrapCpfSecretReader.class);
        CpfLookupDigestService digests = mock(CpfLookupDigestService.class);
        AcademyBootstrapLookup academy = mock(AcademyBootstrapLookup.class);
        PostgresqlInitialAlfaBootstrapRepository repository =
                mock(PostgresqlInitialAlfaBootstrapRepository.class);
        TransactionOperations transactions = directTransactions();
        when(reader.read(any(Path.class))).thenReturn(SYNTHETIC_CPF);
        when(digests.current(SYNTHETIC_CPF)).thenReturn(PROTECTED_DIGEST);
        when(academy.lookupSingleActive(SYNTHETIC_CPF)).thenReturn(validSource());
        when(repository.bootstrap(any(), any(), anyString())).thenReturn(
                PostgresqlInitialAlfaBootstrapService.BootstrapResult.CREATED
        );

        PostgresqlInitialAlfaBootstrapService service = service(
                properties, reader, digests, academy, repository, transactions
        );

        assertThat(service.bootstrap()).isEqualTo(
                PostgresqlInitialAlfaBootstrapService.BootstrapResult.CREATED
        );
        ArgumentCaptor<String> receipt = ArgumentCaptor.forClass(String.class);
        verify(repository).bootstrap(any(), any(), receipt.capture());
        assertThat(receipt.getValue())
                .matches("[0-9a-f]{64}")
                .doesNotContain(SYNTHETIC_CPF)
                .isNotEqualTo(PROTECTED_DIGEST.value());
    }

    @Test
    void invalidSecretStopsBeforeSourceLookupAndTransaction() {
        BootstrapAdminProperties properties = properties();
        BootstrapCpfSecretReader reader = mock(BootstrapCpfSecretReader.class);
        CpfLookupDigestService digests = mock(CpfLookupDigestService.class);
        AcademyBootstrapLookup academy = mock(AcademyBootstrapLookup.class);
        PostgresqlInitialAlfaBootstrapRepository repository =
                mock(PostgresqlInitialAlfaBootstrapRepository.class);
        AtomicBoolean transactionUsed = new AtomicBoolean();
        TransactionOperations transactions = recordingTransactions(transactionUsed);
        when(reader.read(any(Path.class))).thenThrow(new IllegalStateException(
                "Arquivo secreto de bootstrap inválido."
        ));

        PostgresqlInitialAlfaBootstrapService service = service(
                properties, reader, digests, academy, repository, transactions
        );

        assertThatThrownBy(service::bootstrap)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Bootstrap inicial do ALFA não pôde ser concluído.");
        assertThat(transactionUsed).isFalse();
        verify(academy, never()).lookupSingleActive(anyString());
        verify(repository, never()).bootstrap(any(), any(), anyString());
    }

    private static TransactionOperations directTransactions() {
        return recordingTransactions(null);
    }

    private static TransactionOperations recordingTransactions(AtomicBoolean used) {
        return new TransactionOperations() {
            @Override
            public <T> T execute(TransactionCallback<T> callback) {
                if (used != null) {
                    used.set(true);
                }
                return callback.doInTransaction(null);
            }
        };
    }

    private static BootstrapAdminProperties properties() {
        BootstrapAdminProperties properties = new BootstrapAdminProperties();
        properties.setAdminCpfFile("/synthetic/bootstrap-secret");
        return properties;
    }

    private static AcademyBootstrapUser validSource() {
        return new AcademyBootstrapUser(
                901,
                "Synthetic Owner",
                "synthetic.owner@example.invalid",
                true,
                "group-1",
                "Group",
                "profile-1",
                "Profile",
                null
        );
    }

    private static PostgresqlInitialAlfaBootstrapService service(
            BootstrapAdminProperties properties,
            BootstrapCpfSecretReader reader,
            CpfLookupDigestService digests,
            AcademyBootstrapLookup academy,
            PostgresqlInitialAlfaBootstrapRepository repository,
            TransactionOperations transactions
    ) {
        return new PostgresqlInitialAlfaBootstrapService(
                properties,
                reader,
                digests,
                academy,
                repository,
                transactions
        );
    }
}
