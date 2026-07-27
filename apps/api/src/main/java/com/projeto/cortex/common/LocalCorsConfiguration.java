package com.projeto.cortex.common;

import java.net.URI;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.filter.CorsFilter;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/** Credentialed CORS restricted to an explicit list of complete origins. */
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

    private final String[] allowedOrigins;

    @Autowired
    public LocalCorsConfiguration(
            @Value("${cortex.cors.allowed-origins:"
                    + DEFAULT_ALLOWED_ORIGINS
                    + "}")
            String configuredOrigins,
            Environment environment
    ) {
        this(
                configuredOrigins,
                SecurityRuntimeMode.isProduction(environment)
        );
    }

    LocalCorsConfiguration(String configuredOrigins, boolean production) {
        this.allowedOrigins = validateOrigins(configuredOrigins, production);
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOrigins(allowedOrigins)
                .allowedMethods(
                        "GET",
                        "HEAD",
                        "POST",
                        "PUT",
                        "PATCH",
                        "DELETE",
                        "OPTIONS"
                )
                .allowedHeaders(
                        "Accept",
                        "Content-Type",
                        "X-CSRF-Token",
                        "X-Cortex-Client-Instance"
                )
                .allowCredentials(true)
                .allowPrivateNetwork(false)
                .maxAge(3600);
    }

    /** Ensures auth/CSRF filter failures still carry the exact CORS headers. */
    @Bean
    FilterRegistrationBean<CorsFilter> cortexCorsFilterRegistration() {
        UrlBasedCorsConfigurationSource source =
                new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", servletCorsConfiguration());
        FilterRegistrationBean<CorsFilter> registration =
                new FilterRegistrationBean<>(new CorsFilter(source));
        registration.setName("cortexCorsFilter");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        registration.addUrlPatterns("/*");
        return registration;
    }

    private CorsConfiguration servletCorsConfiguration() {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(Arrays.asList(allowedOrigins));
        configuration.setAllowedMethods(Arrays.asList(
                HttpMethod.GET.name(),
                HttpMethod.HEAD.name(),
                HttpMethod.POST.name(),
                HttpMethod.PUT.name(),
                HttpMethod.PATCH.name(),
                HttpMethod.DELETE.name(),
                HttpMethod.OPTIONS.name()
        ));
        configuration.setAllowedHeaders(Arrays.asList(
                HttpHeaders.ACCEPT,
                HttpHeaders.CONTENT_TYPE,
                "X-CSRF-Token",
                "X-Cortex-Client-Instance"
        ));
        configuration.setAllowCredentials(true);
        configuration.setAllowPrivateNetwork(false);
        configuration.setMaxAge(3600L);
        return configuration;
    }

    private static String[] validateOrigins(
            String configuredOrigins,
            boolean production
    ) {
        Set<String> origins = new LinkedHashSet<>();
        if (configuredOrigins != null) {
            Arrays.stream(configuredOrigins.split(","))
                    .map(String::strip)
                    .filter(candidate -> !candidate.isBlank())
                    .forEach(origins::add);
        }
        if (origins.isEmpty()) {
            throw new IllegalArgumentException(
                    "Ao menos uma origem CORS exata deve ser configurada."
            );
        }

        for (String origin : origins) {
            validateOrigin(origin, production);
        }
        return origins.toArray(String[]::new);
    }

    private static void validateOrigin(String origin, boolean production) {
        if (origin.indexOf('*') >= 0) {
            throw new IllegalStateException(
                    "CORS não aceita origens com curinga."
            );
        }

        URI uri;
        try {
            uri = URI.create(origin);
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException(
                    "Origem CORS inválida.",
                    exception
            );
        }

        String scheme = uri.getScheme() == null
                ? ""
                : uri.getScheme().toLowerCase(Locale.ROOT);
        boolean validScheme = "http".equals(scheme) || "https".equals(scheme);
        boolean validPort = uri.getPort() == -1
                || (uri.getPort() >= 1 && uri.getPort() <= 65535);
        if (!validScheme
                || uri.getHost() == null
                || uri.getHost().isBlank()
                || uri.getRawUserInfo() != null
                || (uri.getRawPath() != null && !uri.getRawPath().isEmpty())
                || uri.getRawQuery() != null
                || uri.getRawFragment() != null
                || !validPort) {
            throw new IllegalArgumentException(
                    "Origem CORS deve conter somente esquema, host e porta."
            );
        }
        if (production && !"https".equals(scheme)) {
            throw new IllegalStateException(
                    "CORS de produção exige origens HTTPS explícitas."
            );
        }
    }
}
