package ai.drivemate.routing;

import org.json.JSONArray;
import org.json.JSONObject;

import ai.drivemate.model.RouteResult;

public class NeshanRoutingProvider implements RoutingProvider {
    private String apiKey;

    public NeshanRoutingProvider(String apiKey) {
        this.apiKey = apiKey;
    }

    public void setApiKey(String apiKey) {
        if (apiKey != null && !apiKey.trim().isEmpty()) {
            this.apiKey = apiKey.trim();
        }
    }

    @Override
    public String name() {
        return "نشان";
    }

    @Override
    public RouteResult route(double originLat, double originLng, double destinationLat, double destinationLng) throws Exception {
        if (apiKey == null || apiKey.isEmpty()) {
            throw new IllegalStateException("کلید API نشان تنظیم نشده است.");
        }
        String url = "https://api.neshan.org/v4/direction?type=car"
                + "&origin=" + originLat + "," + originLng
                + "&destination=" + destinationLat + "," + destinationLng;
        JSONObject object = RoutingHttp.getJson(url, "Api-Key", apiKey);
        JSONArray routes = object.optJSONArray("routes");
        if (routes == null || routes.length() == 0) {
            throw new IllegalStateException("مسیری از نشان دریافت نشد.");
        }
        JSONObject leg = routes.getJSONObject(0).getJSONArray("legs").getJSONObject(0);
        int distance = leg.optJSONObject("distance") != null ? leg.getJSONObject("distance").optInt("value") : 0;
        int duration = leg.optJSONObject("duration") != null ? leg.getJSONObject("duration").optInt("value") : 0;
        return new RouteResult(name(), distance, duration, leg.optString("summary"));
    }
}
