package ai.drivemate.ai;

import android.util.Base64;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import javax.crypto.SecretKeyFactory;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

public class RuntimeKeys {
    public static final String[] DEFAULT_URLS = new String[]{
            "https://abrehamrahi.ir/o/public/eUFcsXOX",
            "https://gist.githubusercontent.com/ghadirb/626a804df3009e49045a2948dad89fe5/raw/c93c06d1b2f38c65ee30f092c134a89998326d12/keys.txt"
    };
    private final Map<String, String> values = new LinkedHashMap<>();

    public String get(String key) { return values.get(key); }
    public boolean has(String key) { String v = get(key); return v != null && !v.trim().isEmpty(); }
    public void putIfNotEmpty(String key, String value) { if (value != null && !value.trim().isEmpty()) values.put(key, value.trim()); }

    public boolean getBoolean(String key, boolean defaultValue) {
        String value = get(key);
        if (value == null || value.trim().isEmpty()) return defaultValue;
        String normalized = value.trim().toLowerCase();
        if (normalized.equals("true") || normalized.equals("1") || normalized.equals("on") || normalized.equals("enabled")) return true;
        if (normalized.equals("false") || normalized.equals("0") || normalized.equals("off") || normalized.equals("disabled")) return false;
        return defaultValue;
    }

    public boolean providerEnabled(String provider, boolean defaultValue) {
        String normalized = provider == null ? "" : provider.trim().toUpperCase();
        if (has(normalized + "_ROUTING_ENABLED")) return getBoolean(normalized + "_ROUTING_ENABLED", defaultValue);
        return getBoolean(normalized + "_ENABLED", defaultValue);
    }

    public static RuntimeKeys fetchDefault(String decryptSecret) {
        return fetch(DEFAULT_URLS, decryptSecret);
    }

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
        try {
            StringBuilder body = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) body.append(line).append('\n');
            }
            return body.toString().trim();
        } finally { connection.disconnect(); }
    }

    private static String decodePayload(String payload, String secret) throws Exception {
        String trimmed = payload.trim();
        if (trimmed.startsWith("{") || looksLikePlainKeyValue(trimmed) || secret == null || secret.trim().isEmpty()) return trimmed;
        byte[] raw = Base64.decode(trimmed, Base64.DEFAULT);
        // Matches encrypt_keys.py: 16-byte salt + 12-byte nonce + AES-GCM ciphertext/tag.
        if (raw.length < 16 + 12 + 16) throw new IllegalArgumentException("Encrypted key payload is too short");
        byte[] salt = new byte[16];
        byte[] iv = new byte[12];
        byte[] cipherText = new byte[raw.length - 28];
        System.arraycopy(raw, 0, salt, 0, salt.length);
        System.arraycopy(raw, 16, iv, 0, iv.length);
        System.arraycopy(raw, 28, cipherText, 0, cipherText.length);
        PBEKeySpec spec = new PBEKeySpec(secret.toCharArray(), salt, 20000, 256);
        byte[] key = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, iv));
        return new String(cipher.doFinal(cipherText), StandardCharsets.UTF_8);
    }

    /**
     * A real encrypted payload is one unbroken standard-Base64 block: only A-Z a-z 0-9 + / and,
     * at most, two '=' padding characters at the very end. Anything else — a ':' anywhere, an '='
     * that is not trailing padding, or any character outside the Base64 alphabet (e.g. the '_' or
     * '-' commonly found in real API keys) — means this is a plaintext KEY=VALUE / KEY: VALUE
     * file, whether or not it happens to be a single line with no trailing newline.
     */
    private static boolean looksLikePlainKeyValue(String trimmed) {
        if (trimmed.contains("\n") || trimmed.contains(":")) return true;
        int lastNonPadding = trimmed.length();
        while (lastNonPadding > 0 && trimmed.charAt(lastNonPadding - 1) == '=') lastNonPadding--;
        if (trimmed.length() - lastNonPadding > 2) return true; // more than 2 '=' -> not real padding
        for (int i = 0; i < lastNonPadding; i++) {
            char c = trimmed.charAt(i);
            boolean isBase64Char = (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '+' || c == '/';
            if (!isBase64Char) return true;
        }
        return false;
    }

    private static void parse(String text, RuntimeKeys keys) throws Exception {
        String trimmed = text.trim();
        if (trimmed.startsWith("{")) {
            JSONObject object = new JSONObject(trimmed);
            java.util.Iterator<String> names = object.keys();
            while (names.hasNext()) {
                String rawName = names.next();
                String name = canonicalName(rawName);
                Object value = object.opt(rawName);
                if (value != null && value != JSONObject.NULL) keys.putIfNotEmpty(name, String.valueOf(value));
            }
            return;
        }
        for (String line : trimmed.split("\\r?\\n")) {
            String clean = line.trim();
            if (clean.isEmpty() || clean.startsWith("#")) continue;
            int idx = clean.indexOf('=');
            if (idx <= 0) idx = clean.indexOf(':');
            if (idx > 0) {
                String name = clean.substring(0, idx).trim();
                String value = clean.substring(idx + 1).trim().replace("\"", "");
                String canonical = canonicalName(name);
                // Keep the first credential for a provider; some payloads contain several account tokens.
                if (!keys.has(canonical)) keys.putIfNotEmpty(canonical, value);
            }
        }
    }

    private static String canonicalName(String name) {
        String normalized = name == null ? "" : name.trim().toLowerCase();
        if (normalized.equals("gapgpt") || normalized.equals("gap_gpt")) return "GAPGPT_API_KEY";
        if (normalized.equals("liara")) return "LIARA_API_KEY";
        if (normalized.equals("neshan") || normalized.equals("nshan")) return "NESHAN_API_KEY";
        if (normalized.equals("mapir") || normalized.equals("map.ir") || normalized.equals("map_ir")) return "MAPIR_API_KEY";
        if (normalized.equals("tomtom") || normalized.equals("tom_tom")) return "TOMTOM_API_KEY";
        if (normalized.equals("ors") || normalized.equals("openrouteservice") || normalized.equals("heigit") || normalized.equals("heigit.org")) return "OPENROUTESERVICE_API_KEY";
        if (normalized.equals("tomtom_api_key")) return "TOMTOM_API_KEY";
        if (normalized.equals("openrouteservice_api_key") || normalized.equals("heigit_api_key")) return "OPENROUTESERVICE_API_KEY";
        if (normalized.equals("neshan_api_key")) return "NESHAN_API_KEY";
        if (normalized.equals("mapir_api_key") || normalized.equals("map_ir_api_key")) return "MAPIR_API_KEY";
        if (normalized.endsWith("_routing_enabled") || normalized.endsWith("_enabled")) return normalized.toUpperCase();
        return name;
    }
}
