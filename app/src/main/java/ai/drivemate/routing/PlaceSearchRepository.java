package ai.drivemate.routing;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

import ai.drivemate.model.SavedPlace;

/** Searches a destination near the driver. Neshan is tried first, then map.ir. */
public class PlaceSearchRepository {
    public interface SuccessCallback { void onSuccess(SavedPlace place); }
    public interface ErrorCallback { void onError(String message); }

    private final NeshanRoutingProvider neshan;
    private final MapIrRoutingProvider mapir;

    public PlaceSearchRepository(NeshanRoutingProvider neshan, MapIrRoutingProvider mapir) {
        this.neshan = neshan;
        this.mapir = mapir;
    }

    public void search(String term, double latitude, double longitude, SuccessCallback success, ErrorCallback error) {
        new Thread(() -> {
            try { success.onSuccess(searchNeshan(term, latitude, longitude)); }
            catch (Exception primary) {
                try { success.onSuccess(searchMapIr(term, latitude, longitude)); }
                catch (Exception fallback) { error.onError("مکان «" + term + "» پیدا نشد."); }
            }
        }).start();
    }

    private SavedPlace searchNeshan(String term, double lat, double lng) throws Exception {
        String key = neshan.apiKey();
        if (key == null) throw new IllegalStateException();
        String url = "https://api.neshan.org/v1/search?term=" + URLEncoder.encode(term, StandardCharsets.UTF_8.name()) + "&lat=" + lat + "&lng=" + lng;
        JSONObject body = RoutingHttp.getJson(url, "Api-Key", key);
        JSONArray items = body.optJSONArray("items");
        if (items == null || items.length() == 0) throw new IllegalStateException();
        JSONObject item = items.getJSONObject(0);
        JSONObject location = item.optJSONObject("location");
        if (location == null) throw new IllegalStateException();
        return result(item.optString("title", term), location.optDouble("y"), location.optDouble("x"), item.optString("address", term));
    }

    private SavedPlace searchMapIr(String term, double lat, double lng) throws Exception {
        String key = mapir.apiKey();
        if (key == null) throw new IllegalStateException();
        String url = "https://map.ir/search/v2/autocomplete?text=" + URLEncoder.encode(term, StandardCharsets.UTF_8.name()) + "&lat=" + lat + "&lon=" + lng;
        JSONObject body = RoutingHttp.getJson(url, "x-api-key", key);
        JSONArray items = body.optJSONArray("value");
        if (items == null || items.length() == 0) items = body.optJSONArray("items");
        if (items == null || items.length() == 0) throw new IllegalStateException();
        JSONObject item = items.getJSONObject(0);
        JSONObject geom = item.optJSONObject("geom");
        JSONArray coordinates = geom == null ? null : geom.optJSONArray("coordinates");
        if (coordinates == null) throw new IllegalStateException();
        return result(item.optString("title", term), coordinates.optDouble(1), coordinates.optDouble(0), item.optString("address", term));
    }

    private SavedPlace result(String name, double lat, double lng, String address) {
        return new SavedPlace(name, "search_" + System.currentTimeMillis(), lat, lng, address, System.currentTimeMillis(), false);
    }
}
