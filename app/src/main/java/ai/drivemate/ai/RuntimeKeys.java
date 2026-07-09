package ai.drivemate.ai;

import android.util.Base64;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

public class RuntimeKeys {
    private final Map<String, String> values = new LinkedHashMap<>();

    public String get(String key) { return values.get(key); }
    public boolean has(String key) { String v = get(key); return v != null && !v.trim().isEmpty(); }
    public void putIfNotEmpty(String key, String value) { if (value != null && !value.trim().isEmpty()) values.put(key, value.trim()); }

    public static RuntimeKeys fetch(String[] urls, String decryptSecret) {
        RuntimeKeys keys = new RuntimeKeys();
        for (String url : urls) {
            try {
                String body = getText(url);
                String decoded = decodePayload(body, decryptSecret);
                parse(decoded, keys);
                if (!keys.values.isEmpty()) return keys;
            } catch (Exception ignored) { }
        }
        return keys;
    }

    private static String getText(String url) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(9000);
        connection.setReadTimeout(12000);
        connection.setRequestProperty("Accept", "text/plain,application/json");
        BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8));
        StringBuilder body = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) body.append(line).append('\n');
        return body.toString().trim();
    }

    private static String decodePayload(String payload, String secret) throws Exception {
        String trimmed = payload.trim();
        if (trimmed.startsWith("{") || trimmed.contains("=")) return trimmed;
        if (secret == null || secret.isEmpty()) return trimmed;
        byte[] raw = Base64.decode(trimmed, Base64.DEFAULT);
        byte[] key = MessageDigest.getInstance("SHA-256").digest(secret.getBytes(StandardCharsets.UTF_8));
        if (raw.length > 29) {
            try {
                byte[] iv = new byte[12];
                byte[] cipherText = new byte[raw.length - 12];
                System.arraycopy(raw, 0, iv, 0, 12);
                System.arraycopy(raw, 12, cipherText, 0, cipherText.length);
                Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
                cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, iv));
                return new String(cipher.doFinal(cipherText), StandardCharsets.UTF_8);
            } catch (Exception ignored) { }
        }
        if (raw.length > 16) {
            byte[] iv = new byte[16];
            byte[] cipherText = new byte[raw.length - 16];
            System.arraycopy(raw, 0, iv, 0, 16);
            System.arraycopy(raw, 16, cipherText, 0, cipherText.length);
            Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));
            return new String(cipher.doFinal(cipherText), StandardCharsets.UTF_8);
        }
        return trimmed;
    }

    private static void parse(String text, RuntimeKeys keys) throws Exception {
        String trimmed = text.trim();
        if (trimmed.startsWith("{")) {
            JSONObject object = new JSONObject(trimmed);
            for (String name : new String[]{"GAPGPT_API_KEY", "LIARA_API_KEY", "AI_API_KEY", "NESHAN_API_KEY", "MAPIR_API_KEY"}) {
                keys.putIfNotEmpty(name, object.optString(name, null));
            }
            return;
        }
        for (String line : trimmed.split("\\r?\\n")) {
            String clean = line.trim();
            if (clean.isEmpty() || clean.startsWith("#")) continue;
            int idx = clean.indexOf('=');
            if (idx > 0) keys.putIfNotEmpty(clean.substring(0, idx).trim(), clean.substring(idx + 1).trim().replace("\"", ""));
        }
    }
}
