package com.projeto.cortex.auth.webauthn;

import com.projeto.cortex.common.SecurityRuntimeMode;
import com.yubico.webauthn.CredentialRepository;
import com.yubico.webauthn.RelyingParty;
import com.yubico.webauthn.data.AttestationConveyancePreference;
import com.yubico.webauthn.data.RelyingPartyIdentity;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.Ordered;
import com.projeto.cortex.auth.otp.ClientAddressResolver;

/** Creates the single strict WebAuthn relying party used by all ceremonies. */
@Configuration
public class WebAuthnConfiguration {

    @Bean
    WebAuthnProperties webAuthnProperties(
            @Value("${cortex.auth.webauthn.rp-id:}") String rpId,
            @Value("${cortex.auth.webauthn.rp-name:}") String rpName,
            @Value("${cortex.auth.webauthn.allowed-origins:}")
            String allowedOrigins,
            @Value("${cortex.auth.webauthn.challenge-ttl-seconds:300}")
            int challengeTtlSeconds,
            Environment environment
    ) {
        return new WebAuthnProperties(
                rpId,
                rpName,
                allowedOrigins,
                challengeTtlSeconds,
                SecurityRuntimeMode.isProduction(environment)
        );
    }

    @Bean
    WebAuthnRateLimitPolicy webAuthnRateLimitPolicy(
            @Value("${cortex.auth.webauthn.rate-limit.max-requests:20}")
            int maxRequests,
            @Value("${cortex.auth.webauthn.rate-limit.global-max-requests:2000}")
            int globalMaxRequests,
            @Value("${cortex.auth.webauthn.rate-limit.window-seconds:900}")
            int windowSeconds
    ) {
        return new WebAuthnRateLimitPolicy(
                maxRequests,
                globalMaxRequests,
                windowSeconds
        );
    }

    @Bean
    WebAuthnRequestPolicy webAuthnRequestPolicy(
            @Value("${cortex.auth.webauthn.max-request-body-bytes:65536}")
            int maxRequestBodyBytes
    ) {
        return new WebAuthnRequestPolicy(maxRequestBodyBytes);
    }

    @Bean
    FilterRegistrationBean<WebAuthnPreMvcFilter>
            webAuthnPreMvcFilterRegistration(
                    ClientAddressResolver clientAddresses,
                    WebAuthnRateLimiter rateLimiter,
                    WebAuthnRequestPolicy requestPolicy
            ) {
        FilterRegistrationBean<WebAuthnPreMvcFilter> registration =
                new FilterRegistrationBean<>(new WebAuthnPreMvcFilter(
                        clientAddresses,
                        rateLimiter,
                        requestPolicy
                ));
        registration.setName("cortexWebAuthnPreMvcFilter");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
        registration.addUrlPatterns("/api/auth/passkeys/*");
        return registration;
    }

    @Bean
    RelyingParty relyingParty(
            WebAuthnProperties properties,
            CredentialRepository credentialRepository
    ) {
        return buildRelyingParty(properties, credentialRepository);
    }

    static RelyingParty buildRelyingParty(
            WebAuthnProperties properties,
            CredentialRepository credentialRepository
    ) {
        RelyingPartyIdentity identity = RelyingPartyIdentity.builder()
                .id(properties.rpId())
                .name(properties.rpName())
                .build();
        return RelyingParty.builder()
                .identity(identity)
                .credentialRepository(credentialRepository)
                .origins(properties.origins())
                .allowOriginPort(false)
                .allowOriginSubdomain(false)
                .attestationConveyancePreference(
                        AttestationConveyancePreference.NONE
                )
                .validateSignatureCounter(true)
                .build();
    }
}
