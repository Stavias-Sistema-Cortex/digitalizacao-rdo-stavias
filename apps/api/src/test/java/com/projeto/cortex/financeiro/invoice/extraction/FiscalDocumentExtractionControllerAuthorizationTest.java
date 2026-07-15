package com.projeto.cortex.financeiro.invoice.extraction;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.projeto.cortex.auth.CurrentUserService;
import com.projeto.cortex.financeiro.access.FinancialAccessService;
import com.projeto.cortex.financeiro.access.FinancialPermission;
import com.projeto.cortex.storage.StoredObjectService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.server.ResponseStatusException;

class FiscalDocumentExtractionControllerAuthorizationTest {

    @Test
    void deniedUploadNeverStoresOrExtractsTheFile() {
        FinancialAccessService access = mock(FinancialAccessService.class);
        CurrentUserService currentUser = mock(CurrentUserService.class);
        StoredObjectService storage = mock(StoredObjectService.class);
        FiscalDocumentExtractionService extraction =
                mock(FiscalDocumentExtractionService.class);
        doThrow(new ResponseStatusException(HttpStatus.FORBIDDEN))
                .when(access).requirePermission(
                        "obra-1",
                        FinancialPermission.FINANCEIRO_OPERAR
                );
        FiscalDocumentExtractionController controller =
                new FiscalDocumentExtractionController(
                        access, currentUser, storage, extraction
                );
        MockMultipartFile file = new MockMultipartFile(
                "arquivo", "nota.xml", "application/xml", "<NFe/>".getBytes()
        );

        assertThatThrownBy(() -> controller.upload(
                file, "obra-1", "mutation-1", null, null, null
        )).isInstanceOf(ResponseStatusException.class);

        verifyNoInteractions(currentUser, storage, extraction);
    }
}
