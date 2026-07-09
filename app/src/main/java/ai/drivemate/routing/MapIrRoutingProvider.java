package ai.drivemate.routing;

import org.json.JSONArray;
import org.json.JSONObject;

import ai.drivemate.model.RouteResult;

public class MapIrRoutingProvider implements RoutingProvider {
    private String apiKey;

    public MapIrRoutingProvider(String apiKey) {
        this.apiKey = apiKey;
    }

    public void setApiKey(String apiKey) {
        if (apiKey != null && !apiKey.trim().isEmpty()) {
            this.apiKey = apiKey.trim();
        }
    }

    @Override
    public String name() {
        return "map.ir";
    }

    @Override
    public RouteResult route(double originLat, double originLng, double destinationLat, double destinationLng) throws Exception {
        if (apiKey == null || apiKey.isEmpty()) {
            throw new IllegalStateException("کلید API map.ir تنظیم نشده است.");
        }
        String url = "https://map.ir/routes/route/v1/driving/"
                + originLng + "," + originLat + ";" + destinationLng + "," + destinationLat
                + "?alternatives=false&steps=true&overview=false";
        JSONObject object = RoutingHttp.getJson(url, "x-api-key", apiKey);
        JSONArray routes = object.optJSONArray("routes");
        if (routes == null || routes.length() == 0) {
            throw new IllegalStateException("مسیری از map.ir دریافت نشد.");
        }
        JSONObject route = routes.getJSONObject(0);
        return new RouteResult(name(), route.optInt("distance"), route.optInt("duration"), route.optString("weight_name"));
    }
}
