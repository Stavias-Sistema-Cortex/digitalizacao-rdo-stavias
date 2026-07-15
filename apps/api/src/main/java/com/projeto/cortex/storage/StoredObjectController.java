package com.projeto.cortex.storage;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

@RestController
public class StoredObjectController {

    private final StoredObjectService service;

    public StoredObjectController(StoredObjectService service) {
        this.service = service;
    }

    @PostMapping(
            value = "/api/objetos",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE
    )
    public ResponseEntity<StoredObjectResponse> upload(
            @RequestPart("arquivo") MultipartFile arquivo,
            @RequestParam(required = false) String obraId
    ) {
        StoredObjectUploadResult result = service.upload(
                obraId,
                arquivo.getOriginalFilename(),
                arquivo.getContentType(),
                arquivo.getSize(),
                arquivo::getInputStream
        );
        return ResponseEntity.status(
                result.created() ? HttpStatus.CREATED : HttpStatus.OK
        ).body(StoredObjectResponse.from(result));
    }

    @GetMapping("/api/objetos/{objectId}/conteudo")
    public ResponseEntity<StreamingResponseBody> download(
            @PathVariable String objectId
    ) {
        StoredObjectDownload download = service.download(objectId);
        StoredObjectRecord object = download.object();
        StreamingResponseBody stream = output -> {
            try (download) {
                download.content().inputStream().transferTo(output);
            } catch (ObjectStorageException exception) {
                throw new IOException(
                        "Falha ao finalizar o stream do objeto.",
                        exception
                );
            }
        };

        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .header("X-Content-Type-Options", "nosniff")
                .header(
                        HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment()
                                .filename(
                                        object.originalName(),
                                        StandardCharsets.UTF_8
                                )
                                .build()
                                .toString()
                )
                .contentType(MediaType.parseMediaType(
                        object.detectedMediaType()
                ))
                .contentLength(object.size())
                .body(stream);
    }
}
