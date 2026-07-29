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
 * Free, keyless nationwide place/address search backed by OpenStreetMap's public Nominatim
 * service. Neshan and map.ir are business-registration indexes and, by their own documented
 * coverage, are denser in some provinces (Tehran/central-west) than others (e.g. the northeast
 * around Mashhad) - see PlaceSearchRepository's note on this. Nominatim indexes every named OSM
 * place/address across the whole country with no such regional bias, so it is queried for every
 * general text search (not just recognized nearby categories) to close that coverage gap.
 *
 * Nominatim's usage policy (https://operations.osmfoundation.org/policies/nominatim/) requires a
 * descriptive User-Agent and caps free use at roughly one request per second; callers must not
 * hammer this from a tight loop.
 */
final class NominatimSearchProvider {

    List<SavedPlace> search(String term, double latitude, double longitude) throws Exception {
        if (term == null || term.trim().isEmpty()) return Collections.emptyList();
        String url = "https://nominatim.openstreetmap.org/search?format=jsonv2&addressdetails=1&limit=15"
                + "&countrycodes=ir&accept-language=fa"
                + "&viewbox=" + (longitude - 4d) + "," + (latitude + 4d) + "," + (longitude + 4d) + "," + (latitude - 4d)
                + "&bounded=0&q=" + URLEncoder.encode(term, StandardCharsets.UTF_8.name());
        JSONArray items = getJsonArray(url);
        ArrayList<SavedPlace> places = new ArrayList<>();
        if (items == null) return places;
        for (int index = 0; index < items.length() && index < 15; index++) {
            JSONObject item = items.optJSONObject(index);
            if (item == null) continue;
            double resultLat = item.optDouble("lat", Double.NaN);
            double resultLon = item.optDouble("lon", Double.NaN);
            if (Double.isNaN(resultLat) || Double.isNaN(resultLon) || !isInIran(resultLat, resultLon)) continue;
            String title = item.optString("name", "");
            String displayName = item.optString("display_name", term);
            if (title.trim().isEmpty()) title = firstSegment(displayName);
            String kind = item.optString("type", item.optString("class", "place"));
            places.add(new SavedPlace(title, "search_osm_nominatim_" + kind + "_" + item.optLong("place_id"),
                    resultLat, resultLon, displayName, System.currentTimeMillis(), false));
        }
        return places;
    }

    private String firstSegment(String displayName) {
        if (displayName == null || displayName.trim().isEmpty()) return "مکان";
        int comma = displayName.indexOf(',');
        return comma > 0 ? displayName.substring(0, comma).trim() : displayName.trim();
    }

    private JSONArray getJsonArray(String url) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(7000);
        connection.setReadTimeout(9000);
        connection.setRequestMethod("GET");
        connection.setRequestProperty("Accept", "application/json");
        // Required by Nominatim's usage policy; identifies the app rather than a generic client.
        connection.setRequestProperty("User-Agent", "DriveMate-AI/1.0 (Android navigation assistant)");
        int code = connection.getResponseCode();
        BufferedReader reader = new BufferedReader(new InputStreamReader(
                code >= 200 && code < 300 ? connection.getInputStream() : connection.getErrorStream(),
                StandardCharsets.UTF_8));
        StringBuilder body = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) body.append(line);
        if (code < 200 || code >= 300) throw new IllegalStateException("HTTP " + code + ": " + body);
        String text = body.toString().trim();
        return text.startsWith("[") ? new JSONArray(text) : null;
    }

    private boolean isInIran(double latitude, double longitude) {
        return latitude >= 24.0d && latitude <= 40.5d && longitude >= 44.0d && longitude <= 64.5d;
    }
}
