package com.projeto.cortex.common;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/**
 * Registra o teto de corpo depois do CORS e antes de todo o resto.
 *
 * <p>Depois do CORS porque a recusa também precisa chegar ao navegador com os
 * cabeçalhos certos — um 413 que o navegador esconde atrás de erro de origem
 * não ajuda ninguém a entender o que aconteceu.
 *
 * <p>Antes dos tetos do OTP e do WebAuthn, que são bem menores e continuam
 * valendo depois deste: aqui está o limite generoso que vale para tudo, lá está
 * o limite apertado de cada porta pública.
 */
@Configuration(proxyBeanMethods = false)
public class ApiRequestLimitConfiguration {

    static final int BODY_LIMIT_FILTER_ORDER = Ordered.HIGHEST_PRECEDENCE + 5;

    @Bean
    FilterRegistrationBean<ApiRequestBodyLimitFilter> apiBodyLimitRegistration(
            @Value("${cortex.api.max-request-body-bytes:8388608}")
            long maxBodyBytes
    ) {
        FilterRegistrationBean<ApiRequestBodyLimitFilter> registration =
                new FilterRegistrationBean<>(
                        new ApiRequestBodyLimitFilter(maxBodyBytes)
                );
        registration.setName("cortexApiBodyLimitFilter");
        registration.setOrder(BODY_LIMIT_FILTER_ORDER);
        registration.addUrlPatterns("/*");
        return registration;
    }
}
