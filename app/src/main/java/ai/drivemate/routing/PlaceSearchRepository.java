package ai.drivemate.routing;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import ai.drivemate.model.SavedPlace;

/**
 * Combines both providers and ranks Persian-normalized exact name/address matches before
 * merely nearby fuzzy matches. This prevents an unrelated result from hiding a village search.
 */
public class PlaceSearchRepository {
    public interface SuccessCallback { void onSuccess(SavedPlace place); }
    public interface SearchCallback { void onSuccess(List<SavedPlace> places); }
    public interface ErrorCallback { void onError(String message); }

    private final NeshanRoutingProvider neshan;
    private final MapIrRoutingProvider mapir;

    public PlaceSearchRepository(NeshanRoutingProvider neshan, MapIrRoutingProvider mapir) {
        this.neshan = neshan;
        this.mapir = mapir;
    }

    public void search(String term, double latitude, double longitude, SuccessCallback success, ErrorCallback error) {
        searchAll(term, latitude, longitude, places -> success.onSuccess(places.get(0)), error);
    }

    public void searchAll(String term, double latitude, double longitude, SearchCallback success, ErrorCallback error) {
        new Thread(() -> {
            ArrayList<SavedPlace> results = new ArrayList<>();
            ArrayList<String> failures = new ArrayList<>();

            // Geocoding handles villages and full addresses that nearby POI search can rank poorly.
            try { addUnique(results, searchNeshanGeocoding(term)); }
            catch (Exception exception) { failures.add("Neshan geocoding: " + messageOf(exception)); }
            try { addUnique(results, searchNeshan(term, latitude, longitude)); }
            catch (Exception exception) { failures.add("Neshan search: " + messageOf(exception)); }
            try { addUnique(results, searchMapIr(term, latitude, longitude)); }
            catch (Exception exception) { failures.add("map.ir: " + messageOf(exception)); }

            rank(results, term, latitude, longitude);
            if (!results.isEmpty()) success.onSuccess(results.subList(0, Math.min(12, results.size())));
            else error.onError("Search failed. " + join(failures));
        }).start();
    }

    private List<SavedPlace> searchNeshanGeocoding(String term) throws Exception {
        String key = neshan.apiKey();
        if (key == null) throw new IllegalStateException("Neshan API key is not configured.");
        String url = "https://api.neshan.org/v4/geocoding?address=" + URLEncoder.encode(term, StandardCharsets.UTF_8.name());
        JSONObject body = RoutingHttp.getJson(url, "Api-Key", key);
        JSONObject location = body.optJSONObject("location");
        if (location == null) return Collections.emptyList();
        String address = body.optString("formatted_address", body.optString("address", term));
        String title = body.optString("title", "");
        if (title.trim().isEmpty()) title = address;
        return Collections.singletonList(place(title, location.optDouble("y"), location.optDouble("x"), address));
    }

    private List<SavedPlace> searchNeshan(String term, double latitude, double longitude) throws Exception {
        String key = neshan.apiKey();
        if (key == null) throw new IllegalStateException("Neshan API key is not configured.");
        String url = "https://api.neshan.org/v1/search?term=" + URLEncoder.encode(term, StandardCharsets.UTF_8.name())
                + "&lat=" + latitude + "&lng=" + longitude;
        JSONObject body = RoutingHttp.getJson(url, "Api-Key", key);
        JSONArray items = body.optJSONArray("items");
        ArrayList<SavedPlace> results = new ArrayList<>();
        if (items != null) for (int i = 0; i < items.length() && i < 12; i++) {
            JSONObject item = items.optJSONObject(i);
            JSONObject location = item == null ? null : item.optJSONObject("location");
            if (location != null) results.add(place(item.optString("title", term), location.optDouble("y"),
                    location.optDouble("x"), item.optString("address", term)));
        }
        return results;
    }

    private List<SavedPlace> searchMapIr(String term, double latitude, double longitude) throws Exception {
        String key = mapir.apiKey();
        if (key == null) throw new IllegalStateException("map.ir API key is not configured.");
        String url = "https://map.ir/search/v2/autocomplete?text=" + URLEncoder.encode(term, StandardCharsets.UTF_8.name())
                + "&lat=" + latitude + "&lon=" + longitude;
        JSONObject body = RoutingHttp.getJson(url, "x-api-key", key);
        JSONArray items = body.optJSONArray("value");
        if (items == null) items = body.optJSONArray("items");
        ArrayList<SavedPlace> results = new ArrayList<>();
        if (items != null) for (int i = 0; i < items.length() && i < 12; i++) {
            JSONObject item = items.optJSONObject(i);
            JSONObject geom = item == null ? null : item.optJSONObject("geom");
            JSONArray coordinates = geom == null ? null : geom.optJSONArray("coordinates");
            if (coordinates != null) results.add(place(item.optString("title", term), coordinates.optDouble(1),
                    coordinates.optDouble(0), item.optString("address", term)));
        }
        return results;
    }

    private void rank(List<SavedPlace> places, String query, double originLatitude, double originLongitude) {
        final String normalizedQuery = normalize(query);
        places.sort(Comparator.comparingInt((SavedPlace place) -> -score(place, normalizedQuery, originLatitude, originLongitude))
                .thenComparing(place -> place.name == null ? "" : place.name));
    }

    private int score(SavedPlace place, String query, double originLatitude, double originLongitude) {
        String name = normalize(place.name);
        String address = normalize(place.address);
        int score = 0;
        if (name.equals(query)) score += 12000;
        else if (address.equals(query)) score += 11000;
        else if (name.startsWith(query)) score += 7000;
        else if (name.contains(query)) score += 5500;
        else if (address.contains(query)) score += 3500;
        for (String token : query.split(" ")) {
            if (token.length() < 2) continue;
            if (name.equals(token)) score += 4000;
            else if (name.startsWith(token)) score += 1600;
            else if (name.contains(token) || address.contains(token)) score += 700;
        }
        double distanceKm = distanceKm(originLatitude, originLongitude, place.latitude, place.longitude);
        return score + Math.max(0, 250 - (int) Math.min(250, distanceKm));
    }

    private void addUnique(List<SavedPlace> target, List<SavedPlace> additions) {
        if (additions == null) return;
        Set<String> seen = new HashSet<>();
        for (SavedPlace place : target) seen.add(keyOf(place));
        for (SavedPlace place : additions) if (seen.add(keyOf(place))) target.add(place);
    }

    private String keyOf(SavedPlace place) {
        return normalize(place.name) + "|" + Math.round(place.latitude * 10000d) + "|" + Math.round(place.longitude * 10000d);
    }

    private String normalize(String value) {
        if (value == null) return "";
        return value.replace('\u064A', '\u06CC').replace('\u0649', '\u06CC').replace('\u0643', '\u06A9')
                .replace('\u06C0', '\u0647').replace("\u200C", " ").replace("\u0640", "")
                .replaceAll("[\u064B-\u065F]", "").replaceAll("[^\\p{L}\\p{Nd}]+", " ")
                .trim().replaceAll("\\s+", " ").toLowerCase();
    }

    private double distanceKm(double latitudeA, double longitudeA, double latitudeB, double longitudeB) {
        double latitudeDelta = Math.toRadians(latitudeB - latitudeA);
        double longitudeDelta = Math.toRadians(longitudeB - longitudeA);
        double a = Math.sin(latitudeDelta / 2d) * Math.sin(latitudeDelta / 2d)
                + Math.cos(Math.toRadians(latitudeA)) * Math.cos(Math.toRadians(latitudeB))
                * Math.sin(longitudeDelta / 2d) * Math.sin(longitudeDelta / 2d);
        return 6371d * 2d * Math.atan2(Math.sqrt(a), Math.sqrt(1d - a));
    }

    private String join(List<String> items) {
        if (items == null || items.isEmpty()) return "No provider result.";
        return String.join(" | ", items);
    }

    private String messageOf(Exception error) {
        String message = error.getMessage();
        return message == null || message.trim().isEmpty() ? "Unknown error" : message;
    }

    private SavedPlace place(String name, double latitude, double longitude, String address) {
        return new SavedPlace(name, "search_" + System.currentTimeMillis() + "_" + latitude, latitude, longitude,
                address, System.currentTimeMillis(), false);
    }
}
