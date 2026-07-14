package com.projeto.cortex.auth.otp;

import jakarta.servlet.http.HttpServletRequest;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Resolves a client IP without trusting caller-controlled proxy headers. */
@Component
public class ClientAddressResolver {

    private static final String FORWARDED_FOR = "X-Forwarded-For";
    private static final int MAX_HEADER_LENGTH = 1_024;
    private static final int MAX_HOPS = 20;
    private static final int MAX_TRUSTED_RANGES = 64;

    private final List<Cidr> trustedProxies;

    public ClientAddressResolver(
            @Value("${cortex.auth.trusted-proxy-cidrs:}")
            String trustedProxyCidrs
    ) {
        this.trustedProxies = parseTrustedProxies(trustedProxyCidrs);
    }

    public String resolve(HttpServletRequest request) {
        if (request == null) {
            return AuthRequestNormalizer.INVALID_VALUE;
        }
        String direct = AuthRequestNormalizer.clientIp(
                request.getRemoteAddr()
        );
        if (AuthRequestNormalizer.INVALID_VALUE.equals(direct)
                || trustedProxies.isEmpty()
                || !isTrusted(direct)) {
            return direct;
        }

        List<String> headerValues = Collections.list(
                safeHeaders(request)
        );
        if (headerValues.size() != 1) {
            return direct;
        }
        String header = headerValues.get(0);
        if (header == null
                || header.isBlank()
                || header.length() > MAX_HEADER_LENGTH
                || header.contains("\r")
                || header.contains("\n")) {
            return direct;
        }
        String[] rawHops = header.split(",", -1);
        if (rawHops.length < 1) {
            return direct;
        }
        String leftmostTrusted = direct;
        int trustedHops = 0;
        for (int index = rawHops.length - 1; index >= 0; index--) {
            String hop = AuthRequestNormalizer.clientIp(rawHops[index]);
            if (AuthRequestNormalizer.INVALID_VALUE.equals(hop)) {
                return direct;
            }
            if (!isTrusted(hop)) {
                return hop;
            }
            trustedHops++;
            if (trustedHops > MAX_HOPS) {
                return direct;
            }
            leftmostTrusted = hop;
        }
        return leftmostTrusted;
    }

    private Enumeration<String> safeHeaders(HttpServletRequest request) {
        Enumeration<String> values = request.getHeaders(FORWARDED_FOR);
        return values == null ? Collections.emptyEnumeration() : values;
    }

    private boolean isTrusted(String canonicalIp) {
        byte[] address = literalAddress(canonicalIp);
        return address != null
                && trustedProxies.stream().anyMatch(
                        range -> range.contains(address)
                );
    }

    private List<Cidr> parseTrustedProxies(String configured) {
        if (configured == null || configured.isBlank()) {
            return List.of();
        }
        if (configured.length() > 4_096
                || configured.contains("\r")
                || configured.contains("\n")) {
            throw invalidConfiguration();
        }
        String[] values = configured.split(",", -1);
        if (values.length > MAX_TRUSTED_RANGES) {
            throw invalidConfiguration();
        }
        List<Cidr> ranges = new ArrayList<>(values.length);
        for (String raw : values) {
            String value = raw.strip();
            String[] parts = value.split("/", -1);
            if (parts.length != 2) {
                throw invalidConfiguration();
            }
            String canonical = AuthRequestNormalizer.clientIp(parts[0]);
            byte[] address = literalAddress(canonical);
            if (!parts[1].matches("[0-9]{1,3}")) {
                throw invalidConfiguration();
            }
            int prefix = Integer.parseInt(parts[1]);
            if (address == null
                    || prefix < 1
                    || prefix > address.length * Byte.SIZE) {
                throw invalidConfiguration();
            }
            byte[] network = network(address, prefix);
            if (!Arrays.equals(network, address)) {
                throw invalidConfiguration();
            }
            ranges.add(new Cidr(network, prefix));
        }
        return List.copyOf(ranges);
    }

    private byte[] literalAddress(String canonicalIp) {
        if (canonicalIp == null
                || AuthRequestNormalizer.INVALID_VALUE.equals(canonicalIp)) {
            return null;
        }
        try {
            return InetAddress.getByName(canonicalIp).getAddress();
        } catch (UnknownHostException exception) {
            return null;
        }
    }

    private byte[] network(byte[] address, int prefix) {
        byte[] network = Arrays.copyOf(address, address.length);
        int fullBytes = prefix / Byte.SIZE;
        int remainingBits = prefix % Byte.SIZE;
        if (remainingBits != 0 && fullBytes < network.length) {
            int mask = 0xFF << (Byte.SIZE - remainingBits);
            network[fullBytes] = (byte) (network[fullBytes] & mask);
            fullBytes++;
        }
        Arrays.fill(network, fullBytes, network.length, (byte) 0);
        return network;
    }

    private IllegalStateException invalidConfiguration() {
        return new IllegalStateException(
                "Configuração de proxy confiável inválida."
        );
    }

    private record Cidr(byte[] network, int prefix) {

        private Cidr {
            network = Arrays.copyOf(network, network.length);
        }

        boolean contains(byte[] address) {
            if (address.length != network.length) {
                return false;
            }
            int fullBytes = prefix / Byte.SIZE;
            int remainingBits = prefix % Byte.SIZE;
            for (int index = 0; index < fullBytes; index++) {
                if (address[index] != network[index]) {
                    return false;
                }
            }
            if (remainingBits == 0) {
                return true;
            }
            int mask = 0xFF << (Byte.SIZE - remainingBits);
            return (address[fullBytes] & mask)
                    == (network[fullBytes] & mask);
        }
    }
}
