package com.projeto.cortex.mensagens;

import com.projeto.cortex.auth.CurrentUserService;
import com.projeto.cortex.auth.JwtService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

import java.io.ByteArrayInputStream;
import java.time.Instant;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(value = MessageAttachmentController.class)
@AutoConfigureMockMvc(addFilters = false)
class MessageAttachmentControllerMockMvcTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private MessageAttachmentService service;

    @MockBean
    private CurrentUserService currentUserService;

    @MockBean
    private JwtService jwtService;

    @Test
    void uploadsContentWithIdempotentPutAndAuthoritativePathIds()
            throws Exception {
        MensagemAnexoResponse available = attachment();
        when(service.upload(eq("message-1"), eq("attachment-1"), any()))
                .thenReturn(available);
        MockMultipartFile file = new MockMultipartFile(
                "arquivo", "relatorio-final.pdf", "application/pdf",
                "%PDF-1.7".getBytes(java.nio.charset.StandardCharsets.US_ASCII)
        );

        mockMvc.perform(multipart(
                        HttpMethod.PUT,
                        "/api/mensagens/message-1/anexos/attachment-1/conteudo"
                ).file(file))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("attachment-1"))
                .andExpect(jsonPath("$.status").value("DISPONIVEL"));

        verify(service).upload(
                eq("message-1"), eq("attachment-1"), any()
        );
    }

    @Test
    void downloadsAsPrivateAttachmentWithoutExposingStorageKey()
            throws Exception {
        byte[] bytes = "%PDF-1.7".getBytes(
                java.nio.charset.StandardCharsets.US_ASCII
        );
        when(service.download("message-1", "attachment-1"))
                .thenReturn(new MessageAttachmentDownload(
                        attachment(), new ByteArrayInputStream(bytes)
                ));

        mockMvc.perform(get(
                        "/api/mensagens/message-1/anexos/attachment-1/conteudo"
                ))
                .andExpect(status().isOk())
                .andExpect(content().bytes(bytes))
                .andExpect(content().contentType("application/pdf"))
                .andExpect(header().string(
                        "Content-Disposition",
                        org.hamcrest.Matchers.containsString("relatorio-final.pdf")
                ))
                .andExpect(header().string("Cache-Control", "private, no-store"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().doesNotExist("X-Storage-Key"));
    }

    private MensagemAnexoResponse attachment() {
        Instant now = Instant.parse("2026-07-15T11:00:00Z");
        return new MensagemAnexoResponse(
                "attachment-1", "message-1", "client-attachment-1",
                "relatorio-final.pdf", "relatorio-final.pdf",
                MediaType.APPLICATION_PDF_VALUE, 8,
                "a".repeat(64), "DISPONIVEL", null,
                now.minusSeconds(60), now, now, 2
        );
    }
}
