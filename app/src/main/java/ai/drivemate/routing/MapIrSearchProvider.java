package ai.drivemate.routing;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import ai.drivemate.model.SavedPlace;

/**
 * map.ir's own place/address search (endpoint /search/v2), documented to handle Iranian address
 * structures and typo tolerance particularly well. Uses the same map.ir key already configured
 * for routing, so it only ever runs when that key is present - never a new key/cost surface.
 *
 * The exact response wrapper is not fully pinned down in the vendor documentation available (it
 * lists the per-item fields - address, title, province, county, city, region, neighborhood,
 * geom{type,coordinates} - but not the containing structure), so the raw body is parsed
 * defensively here as either a JSON object with one of several known wrapper keys, or a bare JSON
 * array, and simply returns no results if neither shape matches rather than throwing - this
 * provider is one optional source among several in PlaceSearchRepository, so a parsing miss
 * degrades gracefully instead of breaking search.
 */
final class MapIrSearchProvider {
    private final MapIrRoutingProvider routingProvider;

    MapIrSearchProvider(MapIrRoutingProvider routingProvider) {
        this.routingProvider = routingProvider;
    }

    boolean isConfigured() { return routingProvider.isConfigured(); }

    List<SavedPlace> search(String term, double latitude, double longitude) throws Exception {
        String apiKey = routingProvider.apiKey();
        if (apiKey == null || term == null || term.trim().isEmpty()) return Collections.emptyList();
        String url = "https://map.ir/search/v2?text=" + URLEncoder.encode(term, StandardCharsets.UTF_8.name())
                + "&lat=" + latitude + "&lon=" + longitude;
        JSONArray items = fetchItems(url, apiKey);
        ArrayList<SavedPlace> places = new ArrayList<>();
        if (items == null) return places;
        for (int index = 0; index < items.length() && index < 15; index++) {
            JSONObject item = items.optJSONObject(index);
            if (item == null) continue;
            SavedPlace place = toPlace(item, term);
            if (place != null) places.add(place);
        }
        return places;
    }

    private JSONArray fetchItems(String url, String apiKey) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(9000);
        connection.setReadTimeout(12000);
        connection.setRequestMethod("GET");
        connection.setRequestProperty("x-api-key", apiKey);
        connection.setRequestProperty("Accept", "application/json");
        int code = connection.getResponseCode();
        BufferedReader reader = new BufferedReader(new InputStreamReader(
                code >= 200 && code < 300 ? connection.getInputStream() : connection.getErrorStream(),
                StandardCharsets.UTF_8));
        StringBuilder body = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) body.append(line);
        if (code < 200 || code >= 300) throw new IllegalStateException("HTTP " + code + ": " + body);
        String text = body.toString().trim();
        if (text.startsWith("[")) return new JSONArray(text);
        if (!text.startsWith("{")) return null;
        JSONObject response = new JSONObject(text);
        for (String key : new String[]{"value", "items", "results", "data", "features"}) {
            JSONArray array = response.optJSONArray(key);
            if (array != null) return array;
        }
        return null;
    }

    private SavedPlace toPlace(JSONObject item, String term) {
        double[] coordinates = geomCoordinates(item);
        if (coordinates == null) return null;
        double longitude = coordinates[0];
        double latitude = coordinates[1];
        if (!isInIran(latitude, longitude)) return null;
        String title = firstNonEmpty(item.optString("title", null), item.optString("name", null),
                item.optString("last", null), term);
        String address = item.optString("address", title);
        String kind = item.optString("type", item.optString("fclass", "place"));
        return new SavedPlace(title, "search_mapir_" + kind + "_" + Math.round(latitude * 100000d)
                + "_" + Math.round(longitude * 100000d), latitude, longitude, address,
                System.currentTimeMillis(), false);
    }

    /** geom.coordinates is GeoJSON order: [longitude, latitude]. */
    private double[] geomCoordinates(JSONObject item) {
        JSONObject geom = item.optJSONObject("geom");
        JSONArray coordinates = geom != null ? geom.optJSONArray("coordinates") : null;
        if (coordinates == null || coordinates.length() < 2) return null;
        double longitude = coordinates.optDouble(0, Double.NaN);
        double latitude = coordinates.optDouble(1, Double.NaN);
        if (Double.isNaN(longitude) || Double.isNaN(latitude)) return null;
        return new double[]{longitude, latitude};
    }

    private String firstNonEmpty(String... values) {
        for (String value : values) if (value != null && !value.trim().isEmpty()) return value.trim();
        return "مکان";
    }

    private boolean isInIran(double latitude, double longitude) {
        return latitude >= 24.0d && latitude <= 40.5d && longitude >= 44.0d && longitude <= 64.5d;
    }
}

