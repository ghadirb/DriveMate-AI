package ai.drivemate.routing;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import ai.drivemate.model.SavedPlace;

/** Searches a destination near the driver. Neshan is tried first, then map.ir. */
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
            try {
                List<SavedPlace> places = searchNeshan(term, latitude, longitude);
                if (places.isEmpty()) throw new IllegalStateException("Neshan returned no search result.");
                success.onSuccess(places);
            } catch (Exception primary) {
                try {
                    List<SavedPlace> places = searchMapIr(term, latitude, longitude);
                    if (places.isEmpty()) throw new IllegalStateException("map.ir returned no search result.");
                    success.onSuccess(places);
                } catch (Exception fallback) {
                    error.onError("Search failed. Neshan: " + messageOf(primary) + " | map.ir: " + messageOf(fallback));
                }
            }
        }).start();
    }

    private List<SavedPlace> searchNeshan(String term, double lat, double lng) throws Exception {
        String key = neshan.apiKey();
        if (key == null) throw new IllegalStateException("Neshan API key is not configured.");
        String url = "https://api.neshan.org/v1/search?term=" + URLEncoder.encode(term, StandardCharsets.UTF_8.name())
                + "&lat=" + lat + "&lng=" + lng;
        JSONObject body = RoutingHttp.getJson(url, "Api-Key", key);
        JSONArray items = body.optJSONArray("items");
        ArrayList<SavedPlace> results = new ArrayList<>();
        if (items != null) for (int i = 0; i < items.length() && i < 8; i++) {
            JSONObject item = items.optJSONObject(i);
            JSONObject location = item == null ? null : item.optJSONObject("location");
            if (location != null) results.add(result(item.optString("title", term), location.optDouble("y"),
                    location.optDouble("x"), item.optString("address", term)));
        }
        return results;
    }

    private List<SavedPlace> searchMapIr(String term, double lat, double lng) throws Exception {
        String key = mapir.apiKey();
        if (key == null) throw new IllegalStateException("map.ir API key is not configured.");
        String url = "https://map.ir/search/v2/autocomplete?text=" + URLEncoder.encode(term, StandardCharsets.UTF_8.name())
                + "&lat=" + lat + "&lon=" + lng;
        JSONObject body = RoutingHttp.getJson(url, "x-api-key", key);
        JSONArray items = body.optJSONArray("value");
        if (items == null) items = body.optJSONArray("items");
        ArrayList<SavedPlace> results = new ArrayList<>();
        if (items != null) for (int i = 0; i < items.length() && i < 8; i++) {
            JSONObject item = items.optJSONObject(i);
            JSONObject geom = item == null ? null : item.optJSONObject("geom");
            JSONArray coordinates = geom == null ? null : geom.optJSONArray("coordinates");
            if (coordinates != null) results.add(result(item.optString("title", term), coordinates.optDouble(1),
                    coordinates.optDouble(0), item.optString("address", term)));
        }
        return results;
    }

    private String messageOf(Exception error) {
        String message = error.getMessage();
        return message == null || message.trim().isEmpty() ? "Unknown error" : message;
    }

    private SavedPlace result(String name, double lat, double lng, String address) {
        return new SavedPlace(name, "search_" + System.currentTimeMillis() + "_" + lat, lat, lng, address,
                System.currentTimeMillis(), false);
    }
}
