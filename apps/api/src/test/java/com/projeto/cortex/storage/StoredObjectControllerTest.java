package com.projeto.cortex.storage;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class StoredObjectControllerTest {

    private final StoredObjectService service = mock(StoredObjectService.class);
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(
                new StoredObjectController(service)
        ).build();
    }

    @Test
    void newUploadReturnsCreatedAndServerDerivedMetadata() throws Exception {
        byte[] body = "evidencia".getBytes(StandardCharsets.UTF_8);
        when(service.upload(
                eq("obra-1"),
                eq("evidencia.txt"),
                eq("text/plain"),
                eq((long) body.length),
                any()
        )).thenReturn(new StoredObjectUploadResult(record(body.length), true));
        MockMultipartFile file = new MockMultipartFile(
                "arquivo",
                "evidencia.txt",
                "text/plain",
                body
        );

        mockMvc.perform(multipart("/api/objetos")
                        .file(file)
                        .param("obraId", "obra-1"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value("object-1"))
                .andExpect(jsonPath("$.sha256").value("a".repeat(64)))
                .andExpect(jsonPath("$.deduplicado").value(false));
    }

    @Test
    void downloadIsPrivateNoStoreAndUsesSafeAttachmentHeaders()
            throws Exception {
        byte[] body = "evidencia".getBytes(StandardCharsets.UTF_8);
        StoredObjectRecord record = record(body.length);
        when(service.download("object-1")).thenReturn(
                new StoredObjectDownload(
                        record,
                        new ObjectStorageContent(
                                new ByteArrayInputStream(body),
                                body.length,
                                "text/plain"
                        )
                )
        );

        mockMvc.perform(get("/api/objetos/object-1/conteudo"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string(
                        "Content-Disposition",
                        org.hamcrest.Matchers.containsString("attachment")
                ))
                .andExpect(content().bytes(body));
    }

    private StoredObjectRecord record(long size) {
        return new StoredObjectRecord(
                "object-1",
                "user-1",
                "obra-1",
                "dedupe",
                "a".repeat(64),
                "LOCAL",
                "objects/aa/object-1/hash",
                "evidencia.txt",
                "text/plain",
                "text/plain",
                size,
                "DISPONIVEL",
                LocalDateTime.of(2026, 7, 14, 12, 0)
        );
    }
}
