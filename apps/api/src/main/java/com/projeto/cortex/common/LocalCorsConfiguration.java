package com.projeto.cortex.common;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.Arrays;

@Configuration
@Profile("local")
public class LocalCorsConfiguration implements WebMvcConfigurer {

    private final String[] allowedOrigins;

    public LocalCorsConfiguration(
            @Value("${cortex.local-cors.allowed-origins:"
                    + "http://127.0.0.1:5173,"
                    + "http://localhost:5173,"
                    + "http://127.0.0.1:4173,"
                    + "http://localhost:4173}")
            String allowedOrigins
    ) {
        this.allowedOrigins =
                Arrays.stream(allowedOrigins.split(","))
                        .map(String::trim)
                        .filter(value -> !value.isBlank())
                        .toArray(String[]::new);
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOrigins(allowedOrigins)
                .allowedMethods(
                        "GET",
                        "POST",
                        "PUT",
                        "PATCH",
                        "DELETE",
                        "OPTIONS"
                )
                .allowedHeaders("*")
                .maxAge(3600);
    }
}
