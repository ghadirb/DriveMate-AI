package ai.drivemate.location;

import android.content.Context;
import android.location.Address;
import android.location.Geocoder;

import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLDecoder;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import ai.drivemate.model.SavedPlace;

/** Extracts coordinates from Google Maps/Neshan shared links, including shortened redirects. */
public final class SharedLocationParser {
    public interface Callback { void onResolved(SavedPlace place); void onFailure(); }
    private static final Pattern COORDINATES = Pattern.compile("(-?\\d{1,2}\\.\\d{4,})[, ]+(-?\\d{1,3}\\.\\d{4,})");

    private SharedLocationParser() { }

    public static void resolve(Context context, String sharedText, Callback callback) {
        new Thread(() -> {
            try {
                String resolved = resolveRedirectAndPage(sharedText);
                Matcher matcher = COORDINATES.matcher(URLDecoder.decode(resolved, StandardCharsets.UTF_8.name()));
                if (matcher.find()) {
                    double latitude = Double.parseDouble(matcher.group(1));
                    double longitude = Double.parseDouble(matcher.group(2));
                    if (Math.abs(latitude) <= 90 && Math.abs(longitude) <= 180) {
                        callback.onResolved(place(label(sharedText), latitude, longitude, AddressResolver.resolve(context, latitude, longitude)));
                        return;
                    }
                }
                Pattern latPattern = Pattern.compile("(?:[?&]|\\b)lat(?:itude)?=([-?\\d.]+)", Pattern.CASE_INSENSITIVE);
                Pattern lngPattern = Pattern.compile("(?:[?&]|\\b)(?:lng|lon|longitude)=([-?\\d.]+)", Pattern.CASE_INSENSITIVE);
                Matcher latMatch = latPattern.matcher(resolved);
                Matcher lngMatch = lngPattern.matcher(resolved);
                if (latMatch.find() && lngMatch.find()) {
                    double latitude = Double.parseDouble(latMatch.group(1));
                    double longitude = Double.parseDouble(lngMatch.group(1));
                    if (Math.abs(latitude) <= 90 && Math.abs(longitude) <= 180) {
                        callback.onResolved(place(label(sharedText), latitude, longitude, AddressResolver.resolve(context, latitude, longitude)));
                        return;
                    }
                }
                List<Address> matches = new Geocoder(context, new Locale("fa", "IR")).getFromLocationName(label(sharedText), 1);
                if (matches != null && !matches.isEmpty()) {
                    Address address = matches.get(0);
                    callback.onResolved(place(label(sharedText), address.getLatitude(), address.getLongitude(), address.getAddressLine(0)));
                    return;
                }
            } catch (Exception ignored) { }
            callback.onFailure();
        }).start();
    }

    private static String resolveRedirectAndPage(String text) throws Exception {
        int start = text.indexOf("http");
        if (start < 0) return text;
        String link = text.substring(start).split("\\s+")[0];
        HttpURLConnection connection = (HttpURLConnection) new URL(link).openConnection();
        connection.setInstanceFollowRedirects(true);
        connection.setConnectTimeout(8000);
        connection.setReadTimeout(12000);
        connection.setRequestMethod("GET");
        connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Android) DriveMate/1.0");
        try {
            int code = connection.getResponseCode();
            StringBuilder page = new StringBuilder(connection.getURL().toString());
            if (code >= 200 && code < 400) {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
                    char[] buffer = new char[4096];
                    int count;
                    while (page.length() < 300000 && (count = reader.read(buffer)) != -1) page.append(buffer, 0, count);
                }
            }
            return page.toString();
        } finally { connection.disconnect(); }
    }

    private static String label(String text) {
        String clean = text == null ? "مکان اشتراک‌گذاری‌شده" : text.replaceAll("https?://\\S+", "").trim();
        return clean.isEmpty() ? "مکان اشتراک‌گذاری‌شده" : clean;
    }

    private static SavedPlace place(String name, double latitude, double longitude, String address) {
        return new SavedPlace(name, "shared_" + System.currentTimeMillis(), latitude, longitude,
                address == null ? "مکان اشتراک‌گذاری‌شده" : address, System.currentTimeMillis(), true);
    }
}
