package ai.drivemate.routing;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import ai.drivemate.model.SavedPlace;

/** Public OpenStreetMap fallback for nearby categories that Iranian commercial indexes miss. */
final class OverpassPoiProvider {
    List<SavedPlace> searchNearby(String term, double latitude, double longitude) throws Exception {
        String selector = selectorFor(term);
        if (selector == null) return Collections.emptyList();
        String query = "[out:json][timeout:8];(nwr" + selector + "(around:10000," + latitude + "," + longitude
                + "););out center 25;";
        JSONObject body = request(query);
        JSONArray items = body.optJSONArray("elements");
        ArrayList<SavedPlace> places = new ArrayList<>();
        if (items == null) return places;
        for (int index = 0; index < items.length() && index < 25; index++) {
            JSONObject item = items.optJSONObject(index);
            if (item == null) continue;
            JSONObject center = item.optJSONObject("center");
            double resultLat = item.has("lat") ? item.optDouble("lat", Double.NaN)
                    : center == null ? Double.NaN : center.optDouble("lat", Double.NaN);
            double resultLng = item.has("lon") ? item.optDouble("lon", Double.NaN)
                    : center == null ? Double.NaN : center.optDouble("lon", Double.NaN);
            if (!isInIran(resultLat, resultLng)) continue;
            JSONObject tags = item.optJSONObject("tags");
            String title = tags == null ? "" : tags.optString("name:fa", tags.optString("name", ""));
            if (title.trim().isEmpty()) title = fallbackTitle(term);
            String address = tags == null ? "" : joinAddress(tags);
            String kind = tags == null ? "poi" : tags.optString("amenity", tags.optString("shop", "poi"));
            places.add(new SavedPlace(title, "search_osm_" + kind + "_" + item.optLong("id"), resultLat,
                    resultLng, address, System.currentTimeMillis(), false));
        }
        return places;
    }

    private JSONObject request(String query) throws Exception {
        Exception firstFailure;
        try {
            return RoutingHttp.postFormJson("https://overpass-api.de/api/interpreter", "data", query);
        } catch (Exception exception) {
            firstFailure = exception;
        }
        try {
            return RoutingHttp.postFormJson("https://overpass.private.coffee/api/interpreter", "data", query);
        } catch (Exception fallbackFailure) {
            throw new IllegalStateException("Overpass unavailable: " + firstFailure.getMessage()
                    + " | " + fallbackFailure.getMessage());
        }
    }

    private String selectorFor(String rawTerm) {
        String term = normalize(rawTerm);
        if (contains(term, "\u067e\u0645\u067e", "fuel", "gas station")) return "[\"amenity\"=\"fuel\"]";
        if (contains(term, "\u062f\u0627\u0631\u0648\u062e\u0627\u0646\u0647", "pharmacy")) return "[\"amenity\"=\"pharmacy\"]";
        if (contains(term, "\u0628\u06cc\u0645\u0627\u0631\u0633\u062a\u0627\u0646", "hospital")) return "[\"amenity\"=\"hospital\"]";
        if (contains(term, "\u0631\u0633\u062a\u0648\u0631\u0627\u0646", "restaurant")) return "[\"amenity\"=\"restaurant\"]";
        if (contains(term, "\u06a9\u0627\u0641\u0647", "cafe")) return "[\"amenity\"=\"cafe\"]";
        if (contains(term, "\u067e\u0627\u0631\u06a9\u06cc\u0646\u06af", "parking")) return "[\"amenity\"=\"parking\"]";
        if (contains(term, "\u0627\u0633\u062a\u0631\u0627\u062d\u062a", "rest area", "services")) return "[\"highway\"=\"services\"]";
        if (contains(term, "\u0628\u0627\u0646\u06a9", "bank")) return "[\"amenity\"=\"bank\"]";
        if (contains(term, "cng")) return "[\"amenity\"=\"fuel\"]";
        if (contains(term, "\u062a\u0639\u0645\u06cc\u0631\u06af\u0627\u0647", "mechanic")) return "[\"shop\"=\"car_repair\"]";
        if (contains(term, "\u067e\u0646\u0686\u0631", "tyre", "tire")) return "[\"shop\"=\"tyres\"]";
        if (contains(term, "\u0628\u0627\u062a\u0631\u06cc")) return "[\"shop\"=\"car_parts\"]";
        if (contains(term, "\u062f\u0631\u0645\u0627\u0646\u06af\u0627\u0647", "clinic")) return "[\"amenity\"=\"clinic\"]";
        if (contains(term, "\u06a9\u0644\u0627\u0646\u062a\u0631\u06cc", "police")) return "[\"amenity\"=\"police\"]";
        if (contains(term, "\u0645\u0633\u062c\u062f", "mosque")) return "[\"amenity\"=\"place_of_worship\"]";
        if (contains(term, "\u0633\u0631\u0648\u06cc\u0633", "toilet", "restroom")) return "[\"amenity\"=\"toilets\"]";
        if (contains(term, "\u062e\u0648\u062f\u067e\u0631\u062f\u0627\u0632", "atm")) return "[\"amenity\"=\"atm\"]";
        return null;
    }

    private boolean contains(String value, String... candidates) {
        for (String candidate : candidates) if (value.contains(candidate)) return true;
        return false;
    }

    private String normalize(String value) {
        return value == null ? "" : value.replace('\u064A', '\u06CC').replace('\u0643', '\u06A9')
                .replace("\u200C", " ").trim().toLowerCase();
    }

    private String fallbackTitle(String term) {
        return term == null || term.trim().isEmpty() ? "\u0645\u06a9\u0627\u0646 \u0627\u0637\u0631\u0627\u0641" : term;
    }

    private String joinAddress(JSONObject tags) {
        String street = tags.optString("addr:street", "");
        String city = tags.optString("addr:city", "");
        if (street.isEmpty()) return city;
        return city.isEmpty() ? street : street + " - " + city;
    }

    private boolean isInIran(double latitude, double longitude) {
        return latitude >= 24.0d && latitude <= 40.5d && longitude >= 44.0d && longitude <= 64.5d;
    }
}
