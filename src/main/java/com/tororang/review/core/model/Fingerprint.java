package com.tororang.review.core.model;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * fingerprint = sha1(ruleId + file + normalizedEvidence) — 중복 제거용 (설계서 7.3).
 */
public final class Fingerprint {

    private Fingerprint() {
    }

    public static String of(String ruleId, String file, String evidence) {
        String input = ruleId + file + normalize(evidence);
        return sha1Hex(input);
    }

    private static String normalize(String evidence) {
        return evidence.strip().replaceAll("\\s+", " ");
    }

    private static String sha1Hex(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-1 algorithm not available", e);
        }
    }
}
