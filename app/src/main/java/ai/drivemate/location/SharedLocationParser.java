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
    private static final Pattern COORDINATES = Pattern.compile("(-?\\d{1,2}\\.\\d+)[, ]+(-?\\d{1,3}\\.\\d+)");
    private static final Pattern GOOGLE_DATA_COORDINATES = Pattern.compile("!3d(-?\\d{1,2}\\.\\d+)!4d(-?\\d{1,3}\\.\\d+)");
    private static final Pattern META_TITLE = Pattern.compile("(?is)<meta[^>]+(?:property|name)=[\"'](?:og:title|title)[\"'][^>]+content=[\"']([^\"']+)");
    private static final Pattern HTML_TITLE = Pattern.compile("(?is)<title[^>]*>(.*?)</title>");
    private static final Pattern MAPS_PATH_NAME = Pattern.compile("/maps/(?:place|search)/([^/?#]+)", Pattern.CASE_INSENSITIVE);

    private SharedLocationParser() { }

    public static void resolve(Context context, String sharedText, Callback callback) {
        new Thread(() -> {
            try {
                String resolved = decode(resolveRedirectAndPage(sharedText));
                String candidateName = candidateName(sharedText, resolved);
                SavedPlace coordinatePlace = findCoordinates(context, resolved, candidateName);
                if (coordinatePlace != null) { callback.onResolved(coordinatePlace); return; }
                Pattern latPattern = Pattern.compile("(?:[?&]|\\b)lat(?:itude)?=([-?\\d.]+)", Pattern.CASE_INSENSITIVE);
                Pattern lngPattern = Pattern.compile("(?:[?&]|\\b)(?:lng|lon|longitude)=([-?\\d.]+)", Pattern.CASE_INSENSITIVE);
                Matcher latMatch = latPattern.matcher(resolved);
                Matcher lngMatch = lngPattern.matcher(resolved);
                if (latMatch.find() && lngMatch.find()) {
                    double latitude = Double.parseDouble(latMatch.group(1));
                    double longitude = Double.parseDouble(lngMatch.group(1));
                    if (Math.abs(latitude) <= 90 && Math.abs(longitude) <= 180) {
                        callback.onResolved(place(candidateName, latitude, longitude, AddressResolver.resolve(context, latitude, longitude)));
                        return;
                    }
                }
                List<Address> matches = new Geocoder(context, new Locale("fa", "IR")).getFromLocationName(candidateName, 1);
                if (!isGenericName(candidateName) && matches != null && !matches.isEmpty()) {
                    Address address = matches.get(0);
                    callback.onResolved(place(candidateName, address.getLatitude(), address.getLongitude(), address.getAddressLine(0)));
                    return;
                }
            } catch (Exception ignored) { }
            callback.onFailure();
        }).start();
    }

    private static SavedPlace findCoordinates(Context context, String source, String name) {
        Matcher googleData = GOOGLE_DATA_COORDINATES.matcher(source);
        if (googleData.find()) return placeIfValid(context, name, googleData.group(1), googleData.group(2));
        Matcher generic = COORDINATES.matcher(source);
        if (generic.find()) return placeIfValid(context, name, generic.group(1), generic.group(2));
        return null;
    }

    private static SavedPlace placeIfValid(Context context, String name, String lat, String lng) {
        try {
            double latitude = Double.parseDouble(lat);
            double longitude = Double.parseDouble(lng);
            if (Math.abs(latitude) <= 90 && Math.abs(longitude) <= 180) {
                return place(name, latitude, longitude, AddressResolver.resolve(context, latitude, longitude));
            }
        } catch (NumberFormatException ignored) { }
        return null;
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

    private static String candidateName(String originalText, String resolved) {
        String visible = label(originalText);
        if (!isGenericName(visible)) return visible;
        Matcher meta = META_TITLE.matcher(resolved);
        if (meta.find()) {
            String title = stripHtml(meta.group(1));
            if (!isGenericName(title) && !title.toLowerCase(Locale.ROOT).contains("google maps")) return title;
        }
        Matcher title = HTML_TITLE.matcher(resolved);
        if (title.find()) {
            String pageTitle = stripHtml(title.group(1));
            if (!isGenericName(pageTitle) && !pageTitle.toLowerCase(Locale.ROOT).contains("google maps")) return pageTitle;
        }
        Matcher path = MAPS_PATH_NAME.matcher(resolved);
        if (path.find()) {
            String pathName = decode(path.group(1)).replace('+', ' ').trim();
            if (!isGenericName(pathName)) return pathName;
        }
        return visible;
    }

    private static String decode(String value) {
        try { return URLDecoder.decode(value, StandardCharsets.UTF_8.name()).replace("\\u0026", "&").replace("\\u003d", "="); }
        catch (Exception ignored) { return value; }
    }

    private static String stripHtml(String value) {
        return value.replaceAll("<[^>]+>", "").replace("&amp;", "&").trim();
    }

    private static boolean isGenericName(String value) {
        return value == null || value.trim().isEmpty() || value.equals("مکان اشتراک‌گذاری‌شده") || value.equalsIgnoreCase("Google Maps");
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
