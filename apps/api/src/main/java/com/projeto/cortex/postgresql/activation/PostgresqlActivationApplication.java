package com.projeto.cortex.postgresql.activation;

import com.projeto.cortex.auth.AuthController;
import com.projeto.cortex.auth.AuthLoginRateLimiter;
import com.projeto.cortex.auth.DirectCpfLoginPolicy;
import com.projeto.cortex.auth.EmailOtpAuthenticationPolicy;
import com.projeto.cortex.auth.activation.PostgresqlActivationSessionProfileResolver;
import com.projeto.cortex.auth.identity.PostgresqlEmailOtpIdentityLookup;
import com.projeto.cortex.auth.otp.AuthRateLimiter;
import com.projeto.cortex.auth.otp.ClientAddressResolver;
import com.projeto.cortex.auth.otp.EmailOtpChallengeIssuer;
import com.projeto.cortex.auth.otp.EmailOtpChallengeService;
import com.projeto.cortex.auth.otp.OtpDeliveryAfterCommitListener;
import com.projeto.cortex.auth.otp.OtpDeliveryConfiguration;
import com.projeto.cortex.auth.otp.OtpDeliveryDispatcher;
import com.projeto.cortex.auth.otp.OtpSecurityConfiguration;
import com.projeto.cortex.auth.otp.PostgresqlEmailIdentifierNormalizer;
import com.projeto.cortex.auth.otp.PostgresqlEmailOtpChallengeRepository;
import com.projeto.cortex.auth.otp.PostgresqlRateLimitBucketRepository;
import com.projeto.cortex.auth.session.AuthCookieService;
import com.projeto.cortex.auth.session.AuthFilterConfiguration;
import com.projeto.cortex.auth.session.AuthPublicEndpointPolicy;
import com.projeto.cortex.auth.session.AuthSessionProperties;
import com.projeto.cortex.auth.session.AuthSessionService;
import com.projeto.cortex.auth.session.PostgresqlAuthSessionRepository;
import com.projeto.cortex.common.HealthController;
import com.projeto.cortex.common.LocalCorsConfiguration;
import com.projeto.cortex.common.PostgresqlActivationGateConfiguration;
import com.projeto.cortex.common.PostgresqlActivationReadiness;
import com.projeto.cortex.common.PostgresqlActivationReadinessController;
import com.projeto.cortex.common.RuntimeRevision;
import com.projeto.cortex.config.PostgresqlModeConfigurationGuard;
import com.projeto.cortex.config.PostgresqlSchemaReadinessGuard;
import com.projeto.cortex.email.EmailConfiguration;
import com.projeto.cortex.email.FakeEmailGateway;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.data.jpa.JpaRepositoriesAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.context.annotation.Import;

/**
 * Reserved servlet launcher for the PostgreSQL e-mail activation slice.
 * It intentionally imports no legacy MySQL authentication, WebAuthn, storage,
 * sync, scheduler, or operational controller.
 */
@SpringBootConfiguration(proxyBeanMethods = false)
@EnableAutoConfiguration(exclude = {
        HibernateJpaAutoConfiguration.class,
        JpaRepositoriesAutoConfiguration.class
})
@Import({
        PostgresqlModeConfigurationGuard.class,
        PostgresqlSchemaReadinessGuard.class,
        PostgresqlActivationReadiness.class,
        PostgresqlActivationReadinessController.class,
        LocalCorsConfiguration.class,
        HealthController.class,
        RuntimeRevision.class,
        AuthController.class,
        // O login direto por CPF está desligado neste modo (DirectCpfLoginPolicy
        // responde GONE), mas o controller continua exigindo o disjuntor para
        // ser construído — e tê-lo aqui é o que garante que ligar o modo
        // normal não encontre a porta sem freio.
        AuthLoginRateLimiter.class,
        EmailOtpAuthenticationPolicy.class,
        DirectCpfLoginPolicy.class,
        PostgresqlActivationSessionProfileResolver.class,
        AuthSessionProperties.class,
        AuthSessionService.class,
        AuthCookieService.class,
        AuthPublicEndpointPolicy.class,
        AuthFilterConfiguration.class,
        PostgresqlActivationGateConfiguration.class,
        ClientAddressResolver.class,
        OtpSecurityConfiguration.class,
        OtpDeliveryConfiguration.class,
        PostgresqlEmailIdentifierNormalizer.class,
        PostgresqlEmailOtpIdentityLookup.class,
        PostgresqlEmailOtpChallengeRepository.class,
        PostgresqlRateLimitBucketRepository.class,
        PostgresqlAuthSessionRepository.class,
        AuthRateLimiter.class,
        EmailOtpChallengeIssuer.class,
        EmailOtpChallengeService.class,
        OtpDeliveryDispatcher.class,
        OtpDeliveryAfterCommitListener.class,
        EmailConfiguration.class,
        FakeEmailGateway.class
})
public class PostgresqlActivationApplication {

    private PostgresqlActivationApplication() {
    }

    public static void main(String[] args) {
        SpringApplication.run(PostgresqlActivationApplication.class, args);
    }
}
