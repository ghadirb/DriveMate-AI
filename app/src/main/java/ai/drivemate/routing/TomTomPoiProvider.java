package ai.drivemate.routing;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import ai.drivemate.model.SavedPlace;

/** Optional POI fallback. It is used only after the Iranian providers return too few local results. */
final class TomTomPoiProvider {
    private String apiKey;
    private boolean enabled = true;

    TomTomPoiProvider(String apiKey) {
        setApiKey(apiKey);
    }

    void setApiKey(String value) { apiKey = value == null ? "" : value.trim(); }

    boolean isConfigured() {
        return enabled && apiKey.length() >= 20;
    }

    void setEnabled(boolean enabled) { this.enabled = enabled; }

    List<SavedPlace> searchNearby(String term, double latitude, double longitude) throws Exception {
        if (!isConfigured()) return Collections.emptyList();
        String url = "https://api.tomtom.com/search/2/poiSearch/"
                + URLEncoder.encode(term, StandardCharsets.UTF_8.name())
                + ".json?key=" + URLEncoder.encode(apiKey, StandardCharsets.UTF_8.name())
                + "&lat=" + latitude + "&lon=" + longitude
                + "&radius=15000&limit=20&countrySet=IR&language=fa-IR";
        JSONObject body = RoutingHttp.getJson(url);
        JSONArray items = body.optJSONArray("results");
        ArrayList<SavedPlace> places = new ArrayList<>();
        if (items == null) return places;
        for (int index = 0; index < items.length() && index < 20; index++) {
            JSONObject item = items.optJSONObject(index);
            JSONObject position = item == null ? null : item.optJSONObject("position");
            if (position == null) continue;
            double resultLat = position.optDouble("lat", Double.NaN);
            double resultLng = position.optDouble("lon", Double.NaN);
            if (!isInIran(resultLat, resultLng)) continue;
            JSONObject poi = item.optJSONObject("poi");
            JSONObject address = item.optJSONObject("address");
            String title = poi == null ? "" : poi.optString("name", "");
            if (title.trim().isEmpty()) title = item.optString("address", term);
            String subtitle = address == null ? "" : address.optString("freeformAddress", "");
            if (subtitle.trim().isEmpty() && address != null) subtitle = address.optString("countrySubdivision", "");
            String category = poi == null ? "place" : poi.optString("categories", "place");
            places.add(new SavedPlace(title, "search_tomtom_" + category + "_"
                    + Math.round(resultLat * 100000d) + "_" + Math.round(resultLng * 100000d), resultLat,
                    resultLng, subtitle, System.currentTimeMillis(), false));
        }
        return places;
    }

    private boolean isInIran(double latitude, double longitude) {
        return latitude >= 24.0d && latitude <= 40.5d && longitude >= 44.0d && longitude <= 64.5d;
    }
}
