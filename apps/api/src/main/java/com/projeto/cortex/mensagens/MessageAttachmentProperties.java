package com.projeto.cortex.mensagens;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

import java.util.ArrayList;
import java.util.List;

@Component
@Validated
@ConfigurationProperties(prefix = "cortex.attachments")
public class MessageAttachmentProperties {

    @NotBlank
    private String storagePath = "./var/cortex-attachments";
    @Positive
    private long maxFileSizeBytes = 10L * 1024L * 1024L;
    @Positive
    private long maxMessageSizeBytes = 25L * 1024L * 1024L;
    @Positive
    private int rateLimitUploadsPerMinute = 20;
    @NotEmpty
    private List<String> allowedMimeTypes = new ArrayList<>(List.of(
            "image/jpeg",
            "image/png",
            "image/webp",
            "application/pdf",
            "text/plain",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
    ));

    public String getStoragePath() {
        return storagePath;
    }

    public void setStoragePath(String storagePath) {
        this.storagePath = storagePath;
    }

    public long getMaxFileSizeBytes() {
        return maxFileSizeBytes;
    }

    public void setMaxFileSizeBytes(long maxFileSizeBytes) {
        this.maxFileSizeBytes = maxFileSizeBytes;
    }

    public long getMaxMessageSizeBytes() {
        return maxMessageSizeBytes;
    }

    public void setMaxMessageSizeBytes(long maxMessageSizeBytes) {
        this.maxMessageSizeBytes = maxMessageSizeBytes;
    }

    public int getRateLimitUploadsPerMinute() {
        return rateLimitUploadsPerMinute;
    }

    public void setRateLimitUploadsPerMinute(int rateLimitUploadsPerMinute) {
        this.rateLimitUploadsPerMinute = rateLimitUploadsPerMinute;
    }

    public List<String> getAllowedMimeTypes() {
        return List.copyOf(allowedMimeTypes);
    }

    public void setAllowedMimeTypes(List<String> allowedMimeTypes) {
        this.allowedMimeTypes = allowedMimeTypes == null
                ? new ArrayList<>()
                : new ArrayList<>(allowedMimeTypes);
    }

    @AssertTrue(message = "max-message-size-bytes deve ser maior ou igual ao limite por arquivo")
    public boolean isMessageSizeLimitValid() {
        return maxMessageSizeBytes >= maxFileSizeBytes;
    }
}
