package com.projeto.cortex.auth.offline;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "cortex.auth.offline-grant")
public class OfflineGrantProperties {

    static final long MAX_TTL_SECONDS = 86_400;
    static final int MAX_WORKSITES_HARD_LIMIT = 800;

    private String keyId = "";
    private String privateKeyFile = "";
    private String publicKeyFile = "";
    private long ttlSeconds = MAX_TTL_SECONDS;
    private int maxWorksites = 500;

    public String getKeyId() {
        return keyId;
    }

    public void setKeyId(String keyId) {
        this.keyId = keyId;
    }

    public String getPrivateKeyFile() {
        return privateKeyFile;
    }

    public void setPrivateKeyFile(String privateKeyFile) {
        this.privateKeyFile = privateKeyFile;
    }

    public String getPublicKeyFile() {
        return publicKeyFile;
    }

    public void setPublicKeyFile(String publicKeyFile) {
        this.publicKeyFile = publicKeyFile;
    }

    public long getTtlSeconds() {
        return ttlSeconds;
    }

    public void setTtlSeconds(long ttlSeconds) {
        this.ttlSeconds = ttlSeconds;
    }

    public int getMaxWorksites() {
        return maxWorksites;
    }

    public void setMaxWorksites(int maxWorksites) {
        this.maxWorksites = maxWorksites;
    }

    long requireValidTtlSeconds() {
        if (ttlSeconds <= 0 || ttlSeconds > MAX_TTL_SECONDS) {
            throw new IllegalStateException(
                    "cortex.auth.offline-grant.ttl-seconds deve estar entre "
                            + "1 e 86400."
            );
        }
        return ttlSeconds;
    }

    int requireValidMaxWorksites() {
        if (maxWorksites <= 0
                || maxWorksites > MAX_WORKSITES_HARD_LIMIT) {
            throw new IllegalStateException(
                    "cortex.auth.offline-grant.max-worksites deve estar entre "
                            + "1 e 800."
            );
        }
        return maxWorksites;
    }
}
