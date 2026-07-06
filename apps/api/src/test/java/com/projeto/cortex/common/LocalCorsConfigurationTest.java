package com.projeto.cortex.common;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class LocalCorsConfigurationTest {

    @Test
    void shouldAllowLoopbackAndPrivateNetworkDevelopmentOrigins() {
        CorsConfiguration cors =
                corsConfigurationForDefaults();

        assertThat(cors.checkOrigin("http://localhost:5173"))
                .isEqualTo("http://localhost:5173");
        assertThat(cors.checkOrigin("http://127.0.0.1:5180"))
                .isEqualTo("http://127.0.0.1:5180");
        assertThat(cors.checkOrigin("http://192.168.15.20:5173"))
                .isEqualTo("http://192.168.15.20:5173");
        assertThat(cors.checkOrigin("https://10.0.0.8:4173"))
                .isEqualTo("https://10.0.0.8:4173");
        assertThat(cors.checkOrigin("http://172.31.12.9:5173"))
                .isEqualTo("http://172.31.12.9:5173");
        assertThat(cors.getAllowPrivateNetwork()).isTrue();
    }

    @Test
    void shouldKeepPublicOriginsBlockedByDefault() {
        CorsConfiguration cors =
                corsConfigurationForDefaults();

        assertThat(cors.checkOrigin("https://example.com"))
                .isNull();
        assertThat(cors.checkOrigin("http://172.15.12.9:5173"))
                .isNull();
        assertThat(cors.checkHttpMethod(HttpMethod.POST))
                .contains(HttpMethod.POST);
    }

    private CorsConfiguration corsConfigurationForDefaults() {
        LocalCorsConfiguration configuration =
                new LocalCorsConfiguration(
                        LocalCorsConfiguration.DEFAULT_ALLOWED_ORIGINS,
                        LocalCorsConfiguration.DEFAULT_ALLOWED_ORIGIN_PATTERNS
                );

        InspectableCorsRegistry registry =
                new InspectableCorsRegistry();
        configuration.addCorsMappings(registry);

        return registry.configurations().get("/api/**");
    }

    private static class InspectableCorsRegistry extends CorsRegistry {
        Map<String, CorsConfiguration> configurations() {
            return getCorsConfigurations();
        }
    }
}
