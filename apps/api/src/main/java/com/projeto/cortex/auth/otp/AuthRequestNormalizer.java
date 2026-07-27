package com.projeto.cortex.auth.otp;

import com.projeto.cortex.auth.identity.CpfNormalizer;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Locale;

/** Canonicalizes transient rate-limit inputs before they are HMAC protected. */
final class AuthRequestNormalizer {

    static final String INVALID_VALUE = "<invalid>";

    private AuthRequestNormalizer() {
    }

    static String identifier(String raw) {
        String bounded = bounded(raw);
        if (bounded == null) {
            return INVALID_VALUE;
        }
        try {
            return CpfNormalizer.requireValid(bounded);
        } catch (IllegalArgumentException ignored) {
            return INVALID_VALUE;
        }
    }

    static String clientIp(String raw) {
        String bounded = bounded(raw);
        if (bounded == null) {
            return INVALID_VALUE;
        }
        String ipv4 = canonicalIpv4(bounded);
        if (ipv4 != null) {
            return ipv4;
        }
        if (!bounded.contains(":")
                || !bounded.matches("[0-9A-Fa-f:.]+")) {
            return INVALID_VALUE;
        }
        try {
            InetAddress address = InetAddress.getByName(bounded);
            return address instanceof Inet6Address
                    ? address.getHostAddress().toLowerCase(Locale.ROOT)
                    : address.getHostAddress();
        } catch (UnknownHostException exception) {
            return INVALID_VALUE;
        }
    }

    private static String canonicalIpv4(String value) {
        if (!value.matches("[0-9]{1,3}(\\.[0-9]{1,3}){3}")) {
            return null;
        }
        String[] parts = value.split("\\.", -1);
        StringBuilder canonical = new StringBuilder(15);
        for (int index = 0; index < parts.length; index++) {
            int part;
            try {
                part = Integer.parseInt(parts[index]);
            } catch (NumberFormatException exception) {
                return null;
            }
            if (part > 255) {
                return null;
            }
            if (index > 0) {
                canonical.append('.');
            }
            canonical.append(part);
        }
        return canonical.toString();
    }

    private static String bounded(String value) {
        if (value == null
                || value.length() > 512
                || value.isBlank()
                || value.contains("\r")
                || value.contains("\n")) {
            return null;
        }
        return value.strip();
    }
}
