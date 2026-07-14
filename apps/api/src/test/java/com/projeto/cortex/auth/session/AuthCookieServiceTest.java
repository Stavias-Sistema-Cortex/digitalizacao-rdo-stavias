package com.projeto.cortex.auth.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.env.MockEnvironment;

class AuthCookieServiceTest {

    @Test
    void noProfileRejectsInsecureCookiesAsProduction() {
        assertThatThrownBy(() -> new AuthCookieService(
                new AuthSessionProperties(43_200),
                false,
                "Lax",
                new MockEnvironment()
        )).isInstanceOf(IllegalStateException.class);

        MockEnvironment local = new MockEnvironment();
        local.setActiveProfiles("local");
        assertThat(new AuthCookieService(
                new AuthSessionProperties(43_200),
                false,
                "Lax",
                local
        )).isNotNull();
    }

    @Test
    void writesHostOnlySessionAndCsrfCookiesWithBoundedAttributes() {
        AuthCookieService cookies = service(true, "Lax", true);
        MockHttpServletResponse response = new MockHttpServletResponse();
        String session = SessionTokenFixtures.token((byte) 1);
        String csrf = SessionTokenFixtures.token((byte) 2);

        cookies.write(response, new IssuedAuthSession(
                "20000000-0000-0000-0000-000000000002",
                session,
                csrf,
                Instant.parse("2030-01-02T03:04:05Z")
        ));

        assertThat(response.getHeaders("Set-Cookie")).hasSize(2);
        String sessionCookie = response.getHeaders("Set-Cookie").stream()
                .filter(value -> value.startsWith("cortex_session="))
                .findFirst()
                .orElseThrow();
        String csrfCookie = response.getHeaders("Set-Cookie").stream()
                .filter(value -> value.startsWith("cortex_csrf="))
                .findFirst()
                .orElseThrow();
        assertThat(sessionCookie)
                .contains("cortex_session=" + session)
                .contains("Path=/")
                .contains("Max-Age=43200")
                .contains("Secure")
                .contains("HttpOnly")
                .contains("SameSite=Lax")
                .doesNotContain("Domain=");
        assertThat(csrfCookie)
                .contains("cortex_csrf=" + csrf)
                .contains("Secure")
                .contains("SameSite=Lax")
                .doesNotContain("HttpOnly")
                .doesNotContain("Domain=");
    }

    @Test
    void rejectsAmbiguousDuplicateCookiesInsteadOfChoosingOne() {
        AuthCookieService cookies = service(false, "Strict", false);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(
                "Cookie",
                "cortex_session=" + SessionTokenFixtures.token((byte) 3)
                        + "; cortex_session="
                        + SessionTokenFixtures.token((byte) 4)
        );

        assertThat(cookies.readSessionToken(request)).isEmpty();
    }

    @Test
    void readsExactlyOneWellFormedCookieAcrossBoundedHeaders() {
        AuthCookieService cookies = service(false, "Strict", false);
        String session = SessionTokenFixtures.token((byte) 5);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Cookie", "theme=light; cortex_session=" + session);

        assertThat(cookies.readSessionToken(request)).contains(session);
        assertThat(cookies.readCsrfToken(request)).isEmpty();
    }

    @Test
    void clearsBothCookiesWithoutEchoingPreviousCredentials() {
        AuthCookieService cookies = service(true, "Lax", true);
        MockHttpServletResponse response = new MockHttpServletResponse();

        cookies.clear(response);

        assertThat(response.getHeaders("Set-Cookie"))
                .hasSize(2)
                .allSatisfy(value -> assertThat(value)
                        .contains("Max-Age=0")
                        .contains("Path=/")
                        .contains("Secure")
                        .contains("SameSite=Lax"));
    }

    @Test
    void productionRequiresSecureCookiesAndNoneAlsoRequiresSecure() {
        assertThatThrownBy(() -> service(false, "Lax", true))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> service(false, "None", false))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> service(true, "invalid", false))
                .isInstanceOf(IllegalStateException.class);
    }

    private AuthCookieService service(
            boolean secure,
            String sameSite,
            boolean production
    ) {
        return new AuthCookieService(
                new AuthSessionProperties(43_200),
                secure,
                sameSite,
                production
        );
    }
}
