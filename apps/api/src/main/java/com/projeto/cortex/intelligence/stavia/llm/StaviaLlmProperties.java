package com.projeto.cortex.intelligence.stavia.llm;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "cortex.stavia.llm")
public class StaviaLlmProperties {

    private String baseUrl = "http://localhost:11434/v1";
    private String model = "gemma4:latest";
    private String apiKey = "";
    private int connectTimeoutMs = 2000;
    private int readTimeoutMs = 45000;
    private int maxEvidences = 50;
    private double confidenceThreshold = 0.45;
    private int breakerFailureThreshold = 3;
    private int breakerOpenSeconds = 30;

    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String v) { this.baseUrl = v; }
    public String getModel() { return model; }
    public void setModel(String v) { this.model = v; }
    public String getApiKey() { return apiKey; }
    public void setApiKey(String v) { this.apiKey = v; }
    public int getConnectTimeoutMs() { return connectTimeoutMs; }
    public void setConnectTimeoutMs(int v) { this.connectTimeoutMs = v; }
    public int getReadTimeoutMs() { return readTimeoutMs; }
    public void setReadTimeoutMs(int v) { this.readTimeoutMs = v; }
    public int getMaxEvidences() { return maxEvidences; }
    public void setMaxEvidences(int v) { this.maxEvidences = v; }
    public double getConfidenceThreshold() { return confidenceThreshold; }
    public void setConfidenceThreshold(double v) { this.confidenceThreshold = v; }
    public int getBreakerFailureThreshold() { return breakerFailureThreshold; }
    public void setBreakerFailureThreshold(int v) { this.breakerFailureThreshold = v; }
    public int getBreakerOpenSeconds() { return breakerOpenSeconds; }
    public void setBreakerOpenSeconds(int v) { this.breakerOpenSeconds = v; }
}
