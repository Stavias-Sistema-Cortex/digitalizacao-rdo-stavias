package com.projeto.cortex.auth.password;

public interface PasswordHashService {

    String hash(String rawPassword);

    boolean matches(String rawPassword, String encodedHash);
}
