package com.projeto.cortex.common;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.Arrays;

@Configuration
public class LocalCorsConfiguration implements WebMvcConfigurer {

    static final String DEFAULT_ALLOWED_ORIGINS =
            "http://127.0.0.1:5173,"
                    + "http://127.0.0.1:5174,"
                    + "http://127.0.0.1:5175,"
                    + "http://127.0.0.1:5176,"
                    + "http://127.0.0.1:5177,"
                    + "http://127.0.0.1:5178,"
                    + "http://127.0.0.1:5179,"
                    + "http://localhost:5173,"
                    + "http://localhost:5174,"
                    + "http://localhost:5175,"
                    + "http://localhost:5176,"
                    + "http://localhost:5177,"
                    + "http://localhost:5178,"
                    + "http://localhost:5179,"
                    + "http://127.0.0.1:4173,"
                    + "http://127.0.0.1:4174,"
                    + "http://127.0.0.1:4175,"
                    + "http://127.0.0.1:4176,"
                    + "http://127.0.0.1:4177,"
                    + "http://127.0.0.1:4178,"
                    + "http://127.0.0.1:4179,"
                    + "http://localhost:4173,"
                    + "http://localhost:4174,"
                    + "http://localhost:4175,"
                    + "http://localhost:4176,"
                    + "http://localhost:4177,"
                    + "http://localhost:4178,"
                    + "http://localhost:4179";

    static final String DEFAULT_ALLOWED_ORIGIN_PATTERNS =
            "http://127.0.0.1:*,"
                    + "https://127.0.0.1:*,"
                    + "http://localhost:*,"
                    + "https://localhost:*,"
                    + "http://[::1]:*,"
                    + "https://[::1]:*,"
                    + "http://0.0.0.0:*,"
                    + "http://10.*.*.*:*,"
                    + "https://10.*.*.*:*,"
                    + "http://192.168.*.*:*,"
                    + "https://192.168.*.*:*,"
                    + "http://172.16.*.*:*,"
                    + "http://172.17.*.*:*,"
                    + "http://172.18.*.*:*,"
                    + "http://172.19.*.*:*,"
                    + "http://172.20.*.*:*,"
                    + "http://172.21.*.*:*,"
                    + "http://172.22.*.*:*,"
                    + "http://172.23.*.*:*,"
                    + "http://172.24.*.*:*,"
                    + "http://172.25.*.*:*,"
                    + "http://172.26.*.*:*,"
                    + "http://172.27.*.*:*,"
                    + "http://172.28.*.*:*,"
                    + "http://172.29.*.*:*,"
                    + "http://172.30.*.*:*,"
                    + "http://172.31.*.*:*,"
                    + "https://172.16.*.*:*,"
                    + "https://172.17.*.*:*,"
                    + "https://172.18.*.*:*,"
                    + "https://172.19.*.*:*,"
                    + "https://172.20.*.*:*,"
                    + "https://172.21.*.*:*,"
                    + "https://172.22.*.*:*,"
                    + "https://172.23.*.*:*,"
                    + "https://172.24.*.*:*,"
                    + "https://172.25.*.*:*,"
                    + "https://172.26.*.*:*,"
                    + "https://172.27.*.*:*,"
                    + "https://172.28.*.*:*,"
                    + "https://172.29.*.*:*,"
                    + "https://172.30.*.*:*,"
                    + "https://172.31.*.*:*";

    private final String[] allowedOrigins;
    private final String[] allowedOriginPatterns;

    public LocalCorsConfiguration(
            @Value("${cortex.cors.allowed-origins:${cortex.local-cors.allowed-origins:"
                    + DEFAULT_ALLOWED_ORIGINS
                    + "}}")
            String allowedOrigins,
            @Value("${cortex.cors.allowed-origin-patterns:${cortex.local-cors.allowed-origin-patterns:"
                    + DEFAULT_ALLOWED_ORIGIN_PATTERNS
                    + "}}")
            String allowedOriginPatterns
    ) {
        this.allowedOrigins =
                splitCsv(allowedOrigins);
        this.allowedOriginPatterns =
                splitCsv(allowedOriginPatterns);
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOrigins(allowedOrigins)
                .allowedOriginPatterns(allowedOriginPatterns)
                .allowedMethods(
                        "GET",
                        "POST",
                        "PUT",
                        "PATCH",
                        "DELETE",
                        "OPTIONS"
                )
                .allowedHeaders("*")
                .allowPrivateNetwork(true)
                .maxAge(3600);
    }

    private static String[] splitCsv(String value) {
        return Arrays.stream(value.split(","))
                .map(String::trim)
                .filter(candidate -> !candidate.isBlank())
                .toArray(String[]::new);
    }
}
