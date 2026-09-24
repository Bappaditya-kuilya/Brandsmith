package com.brandsmith.api.session;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;

public final class OwnerAuth {

    private static final SecureRandom RANDOM = new SecureRandom();

    private OwnerAuth() {
    }

    public static String newToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public static String sha256Hex(String token) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(token.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public static boolean matches(String token, String storedHash) {
        if (token == null || storedHash == null) {
            return false;
        }
        return MessageDigest.isEqual(sha256Hex(token).getBytes(java.nio.charset.StandardCharsets.UTF_8),
                storedHash.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
}
