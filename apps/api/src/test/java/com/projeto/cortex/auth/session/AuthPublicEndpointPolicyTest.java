package com.projeto.cortex.auth.session;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletRequest;

class AuthPublicEndpointPolicyTest {

    private static final String OPTIONS =
            "/api/auth/passkeys/authentication/options";
    private static final String VERIFY =
            "/api/auth/passkeys/authentication/verify";
    private static final String EMAIL_CHALLENGES =
            "/api/auth/email/challenges";
    private static final String EMAIL_VERIFY =
            "/api/auth/email/challenges/"
                    + "00000000-0000-4000-8000-000000000001/verify";

    @Test
    void barePostgresqlPublishesOnlyPasskeyAuthenticationPosts() {
        AuthPublicEndpointPolicy policy =
                new AuthPublicEndpointPolicy(true, false);

        List<String> publicPosts = List.of(
                OPTIONS,
                VERIFY,
                EMAIL_CHALLENGES,
                EMAIL_VERIFY,
                "/api/auth/login",
                "/api/auth/passkeys/authentication/options/extra",
                "/api/auth/passkeys/registration/options"
        ).stream()
                .filter(path -> policy.isPublicAuthenticationRequest(
                        request("POST", path)
                ))
                .toList();

        assertThat(publicPosts).containsExactly(OPTIONS, VERIFY);
    }

    @Test
    void localPostgresqlAlsoPublishesDirectCpfLogin() {
        AuthPublicEndpointPolicy policy = policy("local", "postgresql");

        assertThat(policy.isPublicAuthenticationRequest(
                request("POST", "/api/auth/login")
        )).isTrue();
        assertThat(policy.isPublicAuthenticationRequest(
                request("POST", OPTIONS)
        )).isTrue();
        assertThat(policy.isPublicAuthenticationRequest(
                request("POST", VERIFY)
        )).isTrue();
    }

    @Test
    void directCpfLoginFailsClosedOutsideLocalOrTestProfiles() {
        assertThat(policy().isPublicAuthenticationRequest(
                request("POST", "/api/auth/login")
        )).isFalse();
        for (String profile : List.of(
                "postgresql",
                "staging",
                "prod",
                "production"
        )) {
            assertThat(policy(profile).isPublicAuthenticationRequest(
                    request("POST", "/api/auth/login")
            )).as(profile).isFalse();
        }
    }

    @Test
    void activationPostgresqlPublishesOnlyExactEmailOtpPosts() {
        AuthPublicEndpointPolicy policy =
                new AuthPublicEndpointPolicy(false, true);

        assertThat(policy.isPublicAuthenticationRequest(
                request("POST", EMAIL_CHALLENGES)
        )).isTrue();
        assertThat(policy.isPublicAuthenticationRequest(
                request("POST", EMAIL_VERIFY)
        )).isTrue();
        assertThat(policy.isPublicAuthenticationRequest(
                request(
                        "POST",
                        "/api/auth/email/challenges/not-a-uuid/verify"
                )
        )).isFalse();
        assertThat(policy.isPublicAuthenticationRequest(
                request("POST", EMAIL_VERIFY + "/extra")
        )).isFalse();
        assertThat(policy.isPublicAuthenticationRequest(
                request("POST", OPTIONS)
        )).isFalse();
        assertThat(policy.isPublicAuthenticationRequest(
                request("POST", VERIFY)
        )).isFalse();
        assertThat(policy.isPublicAuthenticationRequest(
                request("POST", "/api/auth/login")
        )).isFalse();
    }

    private MockHttpServletRequest request(String method, String path) {
        MockHttpServletRequest request =
                new MockHttpServletRequest(method, path);
        request.setRequestURI(path);
        return request;
    }

    private AuthPublicEndpointPolicy policy(String... profiles) {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles(profiles);
        return new AuthPublicEndpointPolicy(environment);
    }
}
