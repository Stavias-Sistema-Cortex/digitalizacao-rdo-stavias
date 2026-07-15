package com.projeto.cortex.auth.otp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class ClientAddressResolverTest {

    @Test
    void ignoresForwardedHeaderFromAnUntrustedPeer() {
        ClientAddressResolver resolver = new ClientAddressResolver(
                "10.0.0.0/8"
        );
        MockHttpServletRequest request = request("203.0.113.7");
        request.addHeader("X-Forwarded-For", "198.51.100.9");

        assertThat(resolver.resolve(request)).isEqualTo("203.0.113.7");
    }

    @Test
    void walksRightToLeftOnlyAcrossExplicitlyTrustedProxies() {
        ClientAddressResolver resolver = new ClientAddressResolver(
                "10.0.0.0/8,2001:db8:1::/48"
        );
        MockHttpServletRequest request = request("10.2.2.2");
        request.addHeader(
                "X-Forwarded-For",
                "198.51.100.9, 10.1.1.1"
        );

        assertThat(resolver.resolve(request)).isEqualTo("198.51.100.9");
    }

    @Test
    void rejectsAmbiguousOrMalformedForwardingChains() {
        ClientAddressResolver resolver = new ClientAddressResolver(
                "10.0.0.0/8"
        );
        MockHttpServletRequest duplicate = request("10.2.2.2");
        duplicate.addHeader("X-Forwarded-For", "198.51.100.9");
        duplicate.addHeader("X-Forwarded-For", "192.0.2.9");
        MockHttpServletRequest malformed = request("10.2.2.2");
        malformed.addHeader(
                "X-Forwarded-For",
                "198.51.100.9, attacker.example"
        );

        assertThat(resolver.resolve(duplicate)).isEqualTo("10.2.2.2");
        assertThat(resolver.resolve(malformed)).isEqualTo("10.2.2.2");
    }

    @Test
    void ignoresUntrustedTextToTheLeftOfTheResolvedClient() {
        ClientAddressResolver resolver = new ClientAddressResolver(
                "10.0.0.0/8"
        );
        MockHttpServletRequest request = request("10.2.2.2");
        request.addHeader(
                "X-Forwarded-For",
                "attacker.example, 198.51.100.9, 10.1.1.1"
        );

        assertThat(resolver.resolve(request)).isEqualTo("198.51.100.9");
    }

    @Test
    void canonicalizesDirectIpv4AndSupportsTrustedIpv6Cidr() {
        ClientAddressResolver resolver = new ClientAddressResolver(
                "2001:db8:1::/48"
        );
        MockHttpServletRequest direct = request("203.000.113.007");
        MockHttpServletRequest proxied = request("2001:db8:1::2");
        proxied.addHeader("X-Forwarded-For", "198.51.100.10");

        assertThat(resolver.resolve(direct)).isEqualTo("203.0.113.7");
        assertThat(resolver.resolve(proxied)).isEqualTo("198.51.100.10");

        MockHttpServletRequest mapped = request("::ffff:203.0.113.7");
        assertThat(resolver.resolve(mapped)).isEqualTo("203.0.113.7");
    }

    @Test
    void invalidTrustedProxyConfigurationFailsClosedWithoutEchoingIt() {
        String invalid = "secret-proxy.example/33";

        assertThatThrownBy(() -> new ClientAddressResolver(invalid))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Configuração de proxy confiável inválida.")
                .hasMessageNotContaining(invalid);

        assertThatThrownBy(() -> new ClientAddressResolver(
                "10.1.2.3/8"
        )).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new ClientAddressResolver(
                "10.0.0.0/+8"
        )).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new ClientAddressResolver(
                "0.0.0.0/0"
        )).isInstanceOf(IllegalStateException.class);
    }

    private MockHttpServletRequest request(String remoteAddress) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr(remoteAddress);
        return request;
    }
}
