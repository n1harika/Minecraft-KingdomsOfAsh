package com.kingdomsofash.util;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * HMAC-SHA256 signatures over item identity payloads. The secret lives in {@code secret.key} inside the plugin
 * folder, so a forged item (renamed block, hand-written NBT, creative-mode copy of an unsigned item) cannot
 * carry a valid signature.
 */
public final class Signer {
    private final byte[] secret;

    public Signer(byte[] secret) {
        this.secret = secret.clone();
    }

    public static Signer loadOrCreate(Path dir) throws IOException {
        Files.createDirectories(dir);
        Path f = dir.resolve("secret.key");
        byte[] key;
        if (Files.exists(f)) {
            key = Base64.getDecoder().decode(Files.readString(f, StandardCharsets.UTF_8).trim());
            if (key.length < 16) throw new IOException("secret.key is too short");
        } else {
            key = new byte[48];
            new SecureRandom().nextBytes(key);
            Files.writeString(f, Base64.getEncoder().encodeToString(key) + "\n", StandardCharsets.UTF_8);
        }
        return new Signer(key);
    }

    public String sign(String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            byte[] full = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            byte[] cut = new byte[18];
            System.arraycopy(full, 0, cut, 0, cut.length);
            return Base64.getUrlEncoder().withoutPadding().encodeToString(cut);
        } catch (Exception e) {
            throw new IllegalStateException("HMAC unavailable", e);
        }
    }

    public boolean verify(String payload, String signature) {
        if (signature == null) return false;
        return MessageDigest.isEqual(sign(payload).getBytes(StandardCharsets.UTF_8), signature.getBytes(StandardCharsets.UTF_8));
    }
}
